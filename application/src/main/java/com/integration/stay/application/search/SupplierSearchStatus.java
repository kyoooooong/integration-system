package com.integration.stay.application.search;

public enum SupplierSearchStatus {
    SUCCESS,
    /** 일부 배치는 성공하고 일부는 실패했다. */
    PARTIAL,
    FAILED,
    /** 동기화됐고 활성 숙소가 0개다. 부분 실패가 아니다. */
    NO_TARGETS,
    /** 한 번도 동기화되지 않아 호출조차 못 했다. 부분 실패다. */
    CATALOG_UNAVAILABLE
}
