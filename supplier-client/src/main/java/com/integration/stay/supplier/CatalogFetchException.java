package com.integration.stay.supplier;

import com.integration.stay.domain.SupplierId;

/**
 * 카탈로그 조회·해석 실패.
 *
 * <p>항목 하나를 빼고 진행하지 않고 던진다. 카탈로그는 "전체 목록" 계약이고 반영 후
 * 이번에 없던 것을 비활성화하므로, 해석 못 한 항목을 빼면 그 숙소가 "공급사에서 사라진 것"
 * 으로 오해되어 검색에서 빠진다.
 */
public class CatalogFetchException extends RuntimeException {

    public CatalogFetchException(SupplierId supplierId, String detail) {
        super("catalog fetch failed: supplier=%s, %s".formatted(supplierId.value(), detail));
    }

    public CatalogFetchException(SupplierId supplierId, String detail, Throwable cause) {
        super("catalog fetch failed: supplier=%s, %s".formatted(supplierId.value(), detail), cause);
    }
}
