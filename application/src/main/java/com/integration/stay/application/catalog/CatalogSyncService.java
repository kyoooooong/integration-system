package com.integration.stay.application.catalog;

import com.integration.stay.application.DuplicateSupplierException;
import com.integration.stay.domain.SupplierId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 공급사 카탈로그 동기화.
 *
 * <p>공급사별로 try-catch 해서 격리한다. 검색만 공급사 격리를 하고 카탈로그에서 A 가 B 를
 * 죽이면 일관성이 없다. 리액티브 병렬이나 executor 를 만들지 않는다 — 카탈로그는 고객 지연
 * 경로가 아니므로 순차로 충분하고, 격리에 필요한 것은 try-catch 하나뿐이다.
 */
public final class CatalogSyncService {

    private final List<CatalogPort> ports;
    private final CatalogSnapshotStore store;

    public CatalogSyncService(List<CatalogPort> ports, CatalogSnapshotStore store) {
        this.ports = List.copyOf(ports);
        this.store = store;
        requireDistinctSuppliers(this.ports);
    }

    /**
     * 같은 식별자의 카탈로그 포트가 둘 이상이면 기동을 실패시킨다.
     *
     * <p>검색 쪽보다 이쪽이 더 위험하다. 같은 식별자로 두 번 동기화하면
     * <b>두 번째 실행이 첫 번째가 넣은 매핑을 비활성화한다</b> — 미관측 항목의 비활성화가
     * 스냅샷 적용의 일부이기 때문이다. 그러면 검색 대상이 조용히 절반으로 줄어든다.
     */
    private static void requireDistinctSuppliers(List<CatalogPort> ports) {
        Set<SupplierId> seen = new HashSet<>(ports.size());
        for (CatalogPort port : ports) {
            if (!seen.add(port.supplierId())) {
                throw new DuplicateSupplierException("CatalogPort", port.supplierId());
            }
        }
    }

    public List<SupplierSyncOutcome> syncAll() {
        List<SupplierSyncOutcome> outcomes = new ArrayList<>(ports.size());
        for (CatalogPort port : ports) {
            outcomes.add(syncOne(port));
        }
        return outcomes;
    }

    private SupplierSyncOutcome syncOne(CatalogPort port) {
        try {
            List<CatalogProperty> snapshot = port.fetchCatalog();
            CatalogSnapshotValidator.validate(port.supplierId(), snapshot);
            // runId 는 이 실행을 가리키는 마커다. WHERE code NOT IN (:codes) 였다면
            // 숙소 2,000개에 바인딩 2,000개가 필요하지만, 마커는 파라미터 하나로 끝나고
            // "이번 스냅샷에서 관측된 것" 을 정확히 표현한다.
            UUID runId = UUID.randomUUID();
            return new SupplierSyncOutcome.Succeeded(
                    port.supplierId(), store.apply(port.supplierId(), runId, snapshot));
        } catch (Exception e) {
            return new SupplierSyncOutcome.Failed(port.supplierId(), e);
        }
    }
}
