package com.integration.stay.supplier;

import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.application.catalog.SupplierTargets;
import com.integration.stay.application.search.FailureType;
import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.application.search.SearchTelemetry;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.application.search.SupplierSearchPort;
import com.integration.stay.domain.SupplierId;
import com.integration.stay.domain.offer.StayOffer;
import com.integration.stay.supplier.normalize.OfferNormalizationResult;
import com.integration.stay.supplier.normalize.RejectReason;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.reactor.bulkhead.operator.BulkheadOperator;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeoutException;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 공급사 재고·요금 조회의 공통 골격. 배칭·유계 동시성·타임아웃·격리 단위가 여기 한 번만 있다.
 *
 * <p>공급사별로 다른 것은 URI 모양, 응답 봉투 해석, 정규화뿐이므로 그것만 추상 메서드로 남긴다.
 * 이 코드를 공급사마다 복제하면 {@code onErrorResume} 의 위치나 {@code switchIfEmpty} 의
 * 유무처럼 <b>한 줄만 틀려도 조용히 깨지는</b> 부분이 공급사 수만큼 늘어난다.
 *
 * @param <R> 공급사 응답의 원시 타입
 */
public abstract class AbstractSupplierSearchAdapter<R> implements SupplierSearchPort {

    /**
     * 공급사 프로토콜 상한 (CONTRACT). 설정으로 빼지 않는다 — 설정 실수 하나로 프로토콜 위반이 된다.
     * 공급사 C 의 상한이 20이면 C 어댑터에 20이 들어가고, 오케스트레이터는 이 값을 모른다.
     */
    protected static final int MAX_PROPERTY_CODES_PER_REQUEST = 50;

    private final SupplierId supplierId;
    private final WebClient webClient;
    private final SupplierClientProperties properties;
    private final SearchTelemetry telemetry;
    private final Bulkhead bulkhead;

    protected AbstractSupplierSearchAdapter(
            SupplierId supplierId,
            WebClient webClient,
            SupplierClientProperties properties,
            SearchTelemetry telemetry) {
        this.supplierId = supplierId;
        this.webClient = webClient;
        this.properties = properties;
        this.telemetry = telemetry;
        this.bulkhead = createBulkhead(supplierId, properties);
    }

    /**
     * 상류 호출의 동시성 상한. 어댑터가 스스로 소유한다.
     *
     * <p>Composition Root 에 두지 않은 이유는 이것이 <b>조립 결정이 아니라 어댑터가 상류를
     * 보호하는 방식</b>이기 때문이다. 밖으로 빼면 resilience 라이브러리가 app 모듈까지
     * 올라와서, 검색 오케스트레이션이 어댑터의 내부 보호 수단을 알게 된다.
     *
     * <p><b>공급사마다 따로 둔다.</b> 하나로 묶으면 A 의 포화가 B 의 몫을 잠식해서
     * 공급사 격리라는 설계 목표가 자원 계층에서 무너진다.
     *
     * <p>{@code maxWaitDuration = 0} 은 <b>DESIGN_INVARIANT</b> 다. "0ms 가 빨라서" 가 아니라
     * <b>큐를 만들지 않는 실패 정책</b>이다. 리액티브에서 permit 을 기다리면 이벤트 루프를
     * 막고, 대기 큐는 포화 상황에서 백로그를 쌓아 지연을 증폭시킨다.
     * 포화 시에는 즉시 {@link FailureType#LOCAL_SATURATION} 으로 degrade 한다.
     */
    private static Bulkhead createBulkhead(SupplierId supplierId, SupplierClientProperties properties) {
        return Bulkhead.of(
                "supplier-" + supplierId.value(),
                BulkheadConfig.custom()
                        .maxConcurrentCalls(properties.bulkheadMaxConcurrentCalls())
                        .maxWaitDuration(Duration.ZERO)
                        .build());
    }

    @Override
    public final SupplierId supplierId() {
        return supplierId;
    }

    protected abstract URI uriFor(UriBuilder builder, SearchCommand command, List<String> batch);

    protected abstract Class<R> responseType();

    /** 응답 수준 계약 검증. HTTP 200 으로 실패를 알리는 공급사는 여기서 걸러진다. */
    protected abstract Mono<R> validateResponseEnvelope(R response);

    /** item 단위 정규화. 역직렬화에 성공한 item 의 의미적 검증 실패만 여기서 격리된다. */
    protected abstract BatchResult normalizeAll(
            R response, SearchCommand command, List<String> batch, CatalogSnapshot snapshot);

    @Override
    public Mono<SupplierOutcome> searchAll(SearchCommand command, CatalogSnapshot snapshot) {
        SupplierTargets targets = snapshot.targets(supplierId());
        if (targets.neverSynced()) {
            // 호출조차 못 한다. 고객에게는 결과가 빠졌다는 사실이 전달되어야 한다.
            return Mono.just(SupplierOutcome.catalogUnavailable(supplierId()));
        }
        if (targets.propertyCodes().isEmpty()) {
            // 정상적으로 동기화됐고 팔 물건이 없다. 부분 실패가 아니다.
            return Mono.just(SupplierOutcome.noTargets(supplierId()));
        }

        List<List<String>> batches = partition(targets.propertyCodes(), MAX_PROPERTY_CODES_PER_REQUEST);

        return Flux.fromIterable(batches)
                // onErrorResume 이 flatMap "안쪽" 이어야 한다. 바깥이면 첫 배치 실패가 나머지
                // 배치를 취소해 버린다. onErrorResume 은 대체 Publisher 하나를 내보내고 끝나므로
                // 결과에 실패 하나만 남고 성공한 배치들이 통째로 사라진다.
                .flatMap(
                        batch -> callBatch(command, batch, snapshot)
                                .onErrorResume(e -> Mono.just(
                                        new BatchResult.Failure(batch, classify(e), List.of()))),
                        properties.batchConcurrency())
                .collectList()
                .map(results -> aggregate(results, batches.size()));
    }

    /**
     * 원격 경계. 여기서 나는 실패가 공급사 가용성 통계의 대상이다.
     */
    private Mono<R> fetchBatch(SearchCommand command, List<String> batch) {
        return webClient
                .get()
                .uri(builder -> uriFor(builder, command, batch))
                .retrieve()
                .onStatus(HttpStatusCode::isError, this::toFailure)
                .bodyToMono(responseType())
                // empty 는 에러가 아니라서 onErrorResume 이 잡지 못한다. 없으면 그 배치가
                // 성공도 실패도 아닌 채로 조용히 사라진다.
                .switchIfEmpty(Mono.error(SupplierFailure.invalidResponse(supplierId(), "empty response body")))
                .flatMap(this::validateResponseEnvelope)
                .timeout(properties.responseTimeout())
                // Bulkhead 가 최외곽이다. 안쪽이면 timeout 이 bulkhead 를 감싸게 되어
                // BulkheadFullException 이 "타임아웃" 으로 둔갑하고, (서킷을 도입했을 때)
                // 우리 자원 포화가 공급사 장애 통계에 들어가 자기 유발 장애가 증폭된다.
                //
                // transform 이 아니라 transformDeferred 다. transform 은 조립 시점에 한 번만
                // 평가되므로 permit 이 구독마다 획득되지 않는다. 그러면 상한이 걸리지 않는데
                // 코드는 걸린 것처럼 보인다.
                .transformDeferred(BulkheadOperator.of(bulkhead));
    }

    /**
     * 지역 변환. <b>원격 경계 밖이다.</b>
     *
     * <p>정규화를 timeout 안에 두면 정규화 버그로 인한 지연·예외가 "공급사 장애" 로 집계된다.
     * 우리 코드 문제를 공급사 가용성 통계에 섞지 않는다. 다시 timeout 으로 감싸지도 않는다 —
     * 감싸면 우리 버그가 "타임아웃" 으로 위장된다.
     *
     * <p>주의: 스케줄러 전환이 없으므로 normalizeAll 은 Reactor Netty 이벤트 루프에서 돈다.
     * 여기에 블로킹이 들어오면 그 루프에 물린 다른 공급사의 응답 처리까지 멈춘다.
     * 정규화를 블로킹 없는 유계 CPU 변환으로 제한하는 것이 전제다.
     */
    private Mono<BatchResult> callBatch(SearchCommand command, List<String> batch, CatalogSnapshot snapshot) {
        return Mono.defer(() -> {
            long startNanos = System.nanoTime();
            return fetchBatch(command, batch)
                    .doOnSuccess(response -> recordCallDuration("SUCCESS", startNanos))
                    .doOnError(error -> recordCallDuration(classify(error).name(), startNanos))
                    .map(response -> normalizeAll(response, command, batch, snapshot));
        });
    }

    private void recordCallDuration(String outcome, long startNanos) {
        telemetry.supplierCallCompleted(supplierId, outcome, Duration.ofNanos(System.nanoTime() - startNanos));
    }

    private Mono<Throwable> toFailure(ClientResponse response) {
        int status = response.statusCode().value();
        return response.bodyToMono(String.class).defaultIfEmpty("").map(body -> {
            FailureType type = classifyStatus(status);
            return new SupplierFailure(supplierId(), type, "status=%d body=%s".formatted(status, truncate(body)));
        });
    }

    private FailureType classifyStatus(int status) {
        if (status == 429) {
            return FailureType.RATE_LIMITED;
        }
        if (status >= 500) {
            return FailureType.UNAVAILABLE;
        }
        // 4xx 는 공급사가 정상 동작하면서 "네 요청이 잘못됐다" 고 답한 것이다.
        // 잘못된 날짜 범위도, 50개 초과도, 인증 실패도 전부 우리 문제다.
        // 이것을 공급사 가용성으로 집계하면 공급사 성공률 지표가 의미를 잃는다.
        return FailureType.INTERNAL_ERROR;
    }

    /**
     * 커넥션 풀 포화를 나타내는 예외의 클래스 이름.
     *
     * <p>{@code PoolAcquireTimeoutException} 은 {@link TimeoutException} 을 상속한다.
     * 따라서 상속만 보고 분류하면 <b>우리 풀이 고갈된 것이 공급사 타임아웃으로 집계된다.</b>
     * 자기 유발 장애를 공급사 탓으로 돌리면 대응이 "공급사에 문의" 로 잘못 흘러간다.
     *
     * <p>클래스를 직접 import 하지 않고 이름으로 판별한다. 그 클래스는
     * {@code reactor.netty.internal.shaded.reactor.pool} 에 있는데, 이름 그대로 internal 이고
     * shading 된 것이라 컴파일 의존으로 잡으면 <b>우리 잘못이 아닌 이유</b>(라이브러리의 shading
     * 전략 변경)로 빌드가 깨진다.
     *
     * <p><b>잃는 것:</b> 클래스가 이동하면 조용히 오분류로 돌아간다.
     * 조용한 회귀는 시끄러운 실패보다 나쁘므로, 그것을 막는 회귀 테스트를 함께 둔다.
     * 테스트가 있으면 조용하지 않다.
     */
    private static final Set<String> POOL_SATURATION_EXCEPTIONS =
            Set.of("PoolAcquireTimeoutException", "PoolAcquirePendingLimitException");

    private FailureType classify(Throwable error) {
        if (error instanceof SupplierFailure failure) {
            return failure.type();
        }
        // 우리가 명시적으로 정한 한계다. 공급사는 아무 잘못이 없다.
        if (error instanceof BulkheadFullException) {
            return FailureType.LOCAL_SATURATION;
        }
        // 포화 판정을 타임아웃보다 먼저 한다. 상속 관계 때문에 순서가 곧 의미다.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (POOL_SATURATION_EXCEPTIONS.contains(cause.getClass().getSimpleName())) {
                return FailureType.LOCAL_SATURATION;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        // 연결 실패는 WebClientRequestException 으로 오고 원인이 ConnectTimeoutException 이다.
        // 둘 다 "공급사에 닿지 못했다" 이므로 같이 다룬다.
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) {
                return FailureType.TIMEOUT;
            }
            if (cause instanceof java.io.IOException) {
                return FailureType.UNAVAILABLE;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return FailureType.INTERNAL_ERROR;
    }

    private SupplierOutcome aggregate(List<BatchResult> results, int expectedBatches) {
        // Normalizer 는 순수 함수라 지표를 올리지 않는다. 진단을 결과값으로 돌려주고
        // 기록은 여기서 한다. 그래서 정규화 테스트에 Micrometer 가 한 줄도 안 들어간다.
        recordRejections(results);

        List<StayOffer> offers = new ArrayList<>();
        int succeeded = 0;
        for (BatchResult result : results) {
            if (result instanceof BatchResult.Success success) {
                succeeded++;
                offers.addAll(success.offers());
            }
        }
        if (succeeded == 0) {
            return SupplierOutcome.failed(supplierId(), dominant(results));
        }
        if (succeeded < expectedBatches) {
            return SupplierOutcome.partial(supplierId(), offers, dominant(results));
        }
        // 전부 만실이어도 SUCCESS 다. 결과 0건과 조회 실패는 다르다.
        return SupplierOutcome.success(supplierId(), offers);
    }

    private void recordRejections(List<BatchResult> results) {
        Map<String, Integer> byReason = new HashMap<>();
        for (BatchResult result : results) {
            for (OfferNormalizationResult.Rejected rejected : result.rejections()) {
                byReason.merge(rejected.reason().name(), 1, Integer::sum);
            }
        }
        byReason.forEach((reason, count) -> telemetry.offersRejected(supplierId, reason, count));
    }

    /**
     * 조치가 가장 다른 것을 위로 올린다. 우리 잘못이 공급사 장애에 가려지면 안 된다.
     */
    private FailureType dominant(List<BatchResult> results) {
        FailureType chosen = null;
        for (BatchResult result : results) {
            if (result instanceof BatchResult.Failure failure) {
                if (failure.type().isOurFault()) {
                    return failure.type();
                }
                if (chosen == null) {
                    chosen = failure.type();
                }
            }
        }
        return chosen != null ? chosen : FailureType.INVALID_RESPONSE;
    }

    /**
     * item 정규화 결과들을 배치 결과로 접는다.
     *
     * <pre>
     * items == []                -> Success   정상 빈 결과
     * items != [] && offer >= 1  -> Success   일부 거부 허용
     * items != [] && offer == 0  -> Failure   사용 불가
     * </pre>
     *
     * 전부 매핑 실패라면 공급사 장애가 아니라 우리 카탈로그가 낡은 것이므로 구분해서 낸다.
     */
    protected BatchResult assembleBatch(List<String> batch, List<OfferNormalizationResult> results) {
        if (results.isEmpty()) {
            // 공급사가 조건에 맞는 상품이 없다고 정상 응답한 경우다. 실패가 아니다.
            return new BatchResult.Success(batch, List.of(), List.of());
        }
        List<StayOffer> offers = new ArrayList<>();
        List<OfferNormalizationResult.Rejected> rejections = new ArrayList<>();
        for (OfferNormalizationResult result : results) {
            if (result instanceof OfferNormalizationResult.Success success) {
                offers.add(success.offer());
            } else {
                rejections.add((OfferNormalizationResult.Rejected) result);
            }
        }
        if (offers.isEmpty()) {
            boolean allUnmapped = rejections.stream()
                    .allMatch(r -> r.reason() == RejectReason.UNMAPPED_PROPERTY
                            || r.reason() == RejectReason.UNMAPPED_ROOM_TYPE);
            return new BatchResult.Failure(
                    batch, allUnmapped ? FailureType.CATALOG_UNAVAILABLE : FailureType.INVALID_RESPONSE, rejections);
        }
        return new BatchResult.Success(batch, offers, rejections);
    }

    protected static List<List<String>> partition(List<String> codes, int size) {
        List<List<String>> batches = new ArrayList<>((codes.size() + size - 1) / size);
        for (int i = 0; i < codes.size(); i += size) {
            batches.add(List.copyOf(codes.subList(i, Math.min(i + size, codes.size()))));
        }
        return batches;
    }

    private static String truncate(String body) {
        return body.length() <= 200 ? body : body.substring(0, 200) + "...";
    }
}
