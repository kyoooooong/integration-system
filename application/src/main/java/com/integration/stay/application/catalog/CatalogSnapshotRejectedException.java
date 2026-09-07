package com.integration.stay.application.catalog;

import com.integration.stay.domain.SupplierId;

/**
 * 스냅샷 전체를 거부한다. <b>항목 하나만 빼고 진행하지 않는다.</b>
 *
 * <p>카탈로그는 "전체 목록" 계약이고, 반영 후 이번에 없던 것을 비활성화한다.
 * 따라서 파싱·검증에 실패한 항목 하나를 빼고 진행하면, 그 항목이 "공급사에서 사라진 것"
 * 으로 오해되어 비활성화된다. 실패 하나가 멀쩡한 숙소를 검색에서 사라지게 만든다.
 */
public class CatalogSnapshotRejectedException extends RuntimeException {

    public CatalogSnapshotRejectedException(SupplierId supplierId, String detail) {
        super("catalog snapshot rejected: supplier=%s, %s".formatted(supplierId.value(), detail));
    }
}
