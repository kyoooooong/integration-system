package com.integration.stay.application.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integration.stay.application.DuplicateSupplierException;
import com.integration.stay.domain.SupplierId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogSyncServiceTest {

    private static final SupplierId A = new SupplierId("A");
    private static final SupplierId B = new SupplierId("B");

    /** 적용된 스냅샷을 기록만 하는 store. */
    private static final class RecordingStore implements CatalogSnapshotStore {

        private final List<SupplierId> applied = new ArrayList<>();

        @Override
        public CatalogSyncResult apply(SupplierId supplierId, UUID runId, List<CatalogProperty> snapshot) {
            applied.add(supplierId);
            return new CatalogSyncResult(snapshot.size(), 0, 0, 0);
        }
    }

    private record StubPort(SupplierId supplierId, List<CatalogProperty> result, RuntimeException failure)
            implements CatalogPort {

        @Override
        public List<CatalogProperty> fetchCatalog() {
            if (failure != null) {
                throw failure;
            }
            return result;
        }
    }

    @Test
    @DisplayName("A 카탈로그 실패가 B 동기화를 막지 않는다")
    void A_카탈로그_실패가_B_동기화를_막지_않는다() {
        // 검색만 공급사 격리를 하고 카탈로그에서 A 가 B 를 죽이면 일관성이 없다.
        var store = new RecordingStore();
        var service = new CatalogSyncService(
                List.of(
                        new StubPort(A, null, new IllegalStateException("supplier A down")),
                        new StubPort(B, List.of(new CatalogProperty("B77120", "Riverside", List.of())), null)),
                store);

        List<SupplierSyncOutcome> outcomes = service.syncAll();

        assertThat(store.applied).containsExactly(B);
        assertThat(outcomes).hasSize(2);
        assertThat(outcomes.get(0)).isInstanceOf(SupplierSyncOutcome.Failed.class);
        assertThat(outcomes.get(1)).isInstanceOf(SupplierSyncOutcome.Succeeded.class);
    }

    @Test
    @DisplayName("같은 공급사가 두 번 등록되면 기동이 실패한다")
    void 같은_공급사가_두_번_등록되면_기동이_실패한다() {
        // 검색 쪽보다 이쪽이 더 위험하다. 같은 식별자로 두 번 동기화하면
        // 두 번째 실행이 첫 번째가 넣은 매핑을 비활성화한다 —
        // 미관측 항목의 비활성화가 스냅샷 적용의 일부이기 때문이다.
        // 그러면 검색 대상이 조용히 절반으로 줄어든다.
        assertThatThrownBy(() -> new CatalogSyncService(
                        List.of(new StubPort(A, List.of(), null), new StubPort(A, List.of(), null)),
                        new RecordingStore()))
                .isInstanceOf(DuplicateSupplierException.class)
                .hasMessageContaining("CatalogPort");
    }

    @Test
    @DisplayName("검증 실패한 스냅샷은 store 에 닿지 않는다")
    void 검증_실패한_스냅샷은_store에_닿지_않는다() {
        // 항목 하나를 빼고 진행하면 그 항목이 "공급사에서 사라진 것" 으로 오해되어
        // 비활성화된다. 그래서 전체를 거부한다.
        var store = new RecordingStore();
        var duplicated = List.of(
                new CatalogProperty("A-10023", "Riverside", List.of()),
                new CatalogProperty("A-10023", "Riverside Again", List.of()));
        var service = new CatalogSyncService(List.of(new StubPort(A, duplicated, null)), store);

        List<SupplierSyncOutcome> outcomes = service.syncAll();

        assertThat(store.applied).isEmpty();
        assertThat(outcomes.getFirst())
                .isInstanceOf(SupplierSyncOutcome.Failed.class)
                .extracting(o -> ((SupplierSyncOutcome.Failed) o).cause())
                .isInstanceOf(CatalogSnapshotRejectedException.class);
    }
}
