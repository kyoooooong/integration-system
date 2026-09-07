package com.integration.stay.application.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integration.stay.application.DuplicateSupplierException;
import com.integration.stay.application.catalog.CatalogQuery;
import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.domain.StayPeriod;
import com.integration.stay.domain.SupplierId;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/**
 * 오케스트레이션의 최종 격리선.
 *
 * <p>어댑터 안쪽의 실패는 어댑터가 값으로 바꿔 돌려주지만, <b>어댑터 파이프라인 조립부의
 * 버그</b>는 예외로 새어 나온다. 그것을 여기서 잡지 못하면 한 공급사의 우리 쪽 버그가
 * 다른 공급사의 정상 결과까지 통째로 날린다.
 *
 * <p>Spring 없이 돈다. application 계층에 프레임워크 의존이 없다는 것이 여기서도 값을 한다.
 */
class SearchStaysServiceTest {

    private static final SupplierId A = new SupplierId("A");
    private static final SupplierId B = new SupplierId("B");
    private static final SearchCommand COMMAND = new SearchCommand(
            new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);

    /** 등록된 공급사를 그대로 동기화된 것으로 취급하는 카탈로그. */
    private record StubCatalogQuery(List<SupplierId> synced) implements CatalogQuery {
        @Override
        public CatalogSnapshot snapshot(Collection<SupplierId> registered) {
            CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
            synced.forEach(builder::synced);
            return builder.build();
        }
    }

    private record StubPort(SupplierId supplierId, Mono<SupplierOutcome> result) implements SupplierSearchPort {
        @Override
        public Mono<SupplierOutcome> searchAll(SearchCommand command, CatalogSnapshot snapshot) {
            return result;
        }
    }

    private static final class RecordingTelemetry implements SearchTelemetry {
        final List<Throwable> pipelineFailures = new ArrayList<>();
        SearchResult lastResult;

        @Override
        public void adapterPipelineFailed(SupplierId supplierId, Throwable cause) {
            pipelineFailures.add(cause);
        }

        @Override
        public void offersRejected(SupplierId supplierId, String reason, int count) {}

        @Override
        public void searchCompleted(SearchResult result) {
            lastResult = result;
        }
    }

    @Test
    @DisplayName("한 공급사 파이프라인이 예외를 던져도 다른 공급사 결과는 살아남는다")
    void 한_공급사_파이프라인이_예외를_던져도_다른_공급사_결과는_살아남는다() {
        var telemetry = new RecordingTelemetry();
        var service = new SearchStaysService(
                new StubCatalogQuery(List.of(A, B)),
                List.of(
                        new StubPort(A, Mono.error(new IllegalStateException("조립부 버그"))),
                        new StubPort(B, Mono.just(SupplierOutcome.noTargets(B)))),
                telemetry);

        SearchResult result = service.search(COMMAND).block();

        assertThat(result).isNotNull();
        assertThat(result.outcomes()).hasSize(2);
        assertThat(result.hasUsableSupplier()).isTrue();
        assertThat(result.partial()).isTrue();
    }

    @Test
    @DisplayName("삼킨 예외는 반드시 관측된다")
    void 삼킨_예외는_반드시_관측된다() {
        // 관측 없이 catch 하면 그냥 버그를 삼키는 catch 다.
        var telemetry = new RecordingTelemetry();
        var boom = new IllegalStateException("조립부 버그");
        var service = new SearchStaysService(
                new StubCatalogQuery(List.of(A)), List.of(new StubPort(A, Mono.error(boom))), telemetry);

        service.search(COMMAND).block();

        assertThat(telemetry.pipelineFailures).containsExactly(boom);
    }

    @Test
    @DisplayName("우리 버그는 공급사 실패와 다른 타입으로 기록된다")
    void 우리_버그는_공급사_실패와_다른_타입으로_기록된다() {
        // 공급사 성공률 지표에 우리 버그가 섞이면 그 지표는 아무 의미가 없다.
        var service = new SearchStaysService(
                new StubCatalogQuery(List.of(A)),
                List.of(new StubPort(A, Mono.error(new IllegalStateException("boom")))),
                new RecordingTelemetry());

        SearchResult result = service.search(COMMAND).block();

        assertThat(result.outcomes().getFirst().failure()).contains(FailureType.INTERNAL_ERROR);
        assertThat(result.hasOurFault()).isTrue();
        assertThat(result.hasDeterministicBug()).isTrue();
    }

    @Test
    @DisplayName("모든 공급사가 정상이면 partial 이 아니다")
    void 모든_공급사가_정상이면_partial이_아니다() {
        // 반대 방향 확인. 없으면 "항상 partial 이다" 로도 위 테스트들이 통과한다.
        var service = new SearchStaysService(
                new StubCatalogQuery(List.of(A, B)),
                List.of(
                        new StubPort(A, Mono.just(SupplierOutcome.success(A, List.of()))),
                        new StubPort(B, Mono.just(SupplierOutcome.noTargets(B)))),
                new RecordingTelemetry());

        SearchResult result = service.search(COMMAND).block();

        assertThat(result.partial()).isFalse();
        assertThat(result.hasOurFault()).isFalse();
    }

    @Test
    @DisplayName("같은 공급사가 두 번 등록되면 기동이 실패한다")
    void 같은_공급사가_두_번_등록되면_기동이_실패한다() {
        // 공급사를 추가하며 기존 어댑터를 복사한 뒤 식별자를 안 바꾸면 이렇게 된다.
        // 허용하면 같은 공급사를 두 번 호출해 응답의 suppliers 에 같은 이름이 두 번 나오고
        // 상품도 중복된다. 예외가 나지 않으므로 지표에도 잡히지 않는다 —
        // 가장 흔한 실수인데 가장 조용하다.
        assertThatThrownBy(() -> new SearchStaysService(
                        new StubCatalogQuery(List.of(A)),
                        List.of(
                                new StubPort(A, Mono.just(SupplierOutcome.noTargets(A))),
                                new StubPort(A, Mono.just(SupplierOutcome.noTargets(A)))),
                        new RecordingTelemetry()))
                .isInstanceOf(DuplicateSupplierException.class)
                .hasMessageContaining("A");
    }

    @Test
    @DisplayName("서로 다른 공급사는 당연히 통과한다")
    void 서로_다른_공급사는_당연히_통과한다() {
        // 반대 방향 확인. 없으면 "항상 거부한다" 로도 위 테스트가 통과한다.
        assertThatCode(() -> new SearchStaysService(
                        new StubCatalogQuery(List.of(A, B)),
                        List.of(
                                new StubPort(A, Mono.just(SupplierOutcome.noTargets(A))),
                                new StubPort(B, Mono.just(SupplierOutcome.noTargets(B)))),
                        new RecordingTelemetry()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("검색 결과는 반드시 관측된다")
    void 검색_결과는_반드시_관측된다() {
        var telemetry = new RecordingTelemetry();
        var service = new SearchStaysService(
                new StubCatalogQuery(List.of(A)),
                List.of(new StubPort(A, Mono.just(SupplierOutcome.success(A, List.of())))),
                telemetry);

        service.search(COMMAND).block();

        assertThat(telemetry.lastResult).isNotNull();
        assertThat(telemetry.lastResult.outcomes()).hasSize(1);
    }
}
