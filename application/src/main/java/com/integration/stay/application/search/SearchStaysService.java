package com.integration.stay.application.search;

import com.integration.stay.application.DuplicateSupplierException;
import com.integration.stay.application.catalog.CatalogQuery;
import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.domain.SupplierId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 통합 검색 오케스트레이션.
 *
 * <p><b>공급사별 분기가 여기 없다.</b> 공급사 C 가 추가돼도 이 클래스는 바뀌지 않는다.
 * 그것이 ACL 의 목적이고, 공급사 식별자를 enum 으로 두지 않은 이유이기도 하다.
 *
 * <p>{@code @Service} 를 붙이지 않는다. application 계층에 Spring 의존이 없고,
 * 조립은 Composition Root 가 {@code @Bean} 으로 한다.
 */
public final class SearchStaysService {

    private final CatalogQuery catalogQuery;
    private final List<SupplierSearchPort> ports;
    private final SearchTelemetry telemetry;

    public SearchStaysService(CatalogQuery catalogQuery, List<SupplierSearchPort> ports, SearchTelemetry telemetry) {
        this.catalogQuery = catalogQuery;
        this.ports = List.copyOf(ports);
        this.telemetry = telemetry;
        requireDistinctSuppliers(this.ports);
    }

    /**
     * 같은 식별자의 어댑터가 둘 이상이면 기동을 실패시킨다.
     *
     * <p>중복을 허용하면 같은 공급사를 두 번 호출해 응답의 {@code suppliers} 에 같은 이름이
     * 두 번 나오고 상품도 중복된다. <b>예외가 나지 않으므로 지표에도 잡히지 않는다.</b>
     */
    private static void requireDistinctSuppliers(List<SupplierSearchPort> ports) {
        Set<SupplierId> seen = new HashSet<>(ports.size());
        for (SupplierSearchPort port : ports) {
            if (!seen.add(port.supplierId())) {
                throw new DuplicateSupplierException("SupplierSearchPort", port.supplierId());
            }
        }
    }

    public Mono<SearchResult> search(SearchCommand command) {
        // 서블릿 스레드에서 동기 실행된다. Mono 조립 전이므로 이벤트 루프와 무관하다.
        // 요청당 1회 확보하고 정규화까지 같은 스냅샷을 쓴다.
        CatalogSnapshot snapshot = catalogQuery.snapshot(registeredIds());

        return Flux.fromIterable(ports)
                // Flux.merge 가 아니라 flatMap 인 이유는 동시성 상한이 인자로 명시되기 때문이다.
                // merge 는 암묵적이라 누군가 concat 으로 바꿔도 컴파일되고 동작만 조용히 순차가 된다.
                .flatMap(
                        port -> port.searchAll(command, snapshot)
                                // 어댑터 파이프라인 조립부 버그의 최종 격리.
                                // 한 공급사의 우리 쪽 버그가 다른 공급사 결과까지 날리지 않는다.
                                .onErrorResume(e -> {
                                    telemetry.adapterPipelineFailed(port.supplierId(), e);
                                    return Mono.just(
                                            SupplierOutcome.failed(port.supplierId(), FailureType.INTERNAL_ERROR));
                                }),
                        ports.size())
                .collectList()
                .map(SearchResult::new)
                .doOnNext(telemetry::searchCompleted);
    }

    private List<SupplierId> registeredIds() {
        return ports.stream().map(SupplierSearchPort::supplierId).toList();
    }
}
