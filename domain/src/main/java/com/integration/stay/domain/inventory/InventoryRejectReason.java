package com.integration.stay.domain.inventory;

/**
 * 재고 판정 거부 사유.
 *
 * <p>하나로 합치지 않는 이유는 지표에서 원인마다 <b>다른 사람의 다른 행동</b>을 유발하기
 * 때문이다. DATE_COVERAGE_MISMATCH 는 공급사 계약 위반이라 문의 대상이고,
 * INVALID_INVENTORY 는 계약상 불가능한 값이 온 것이다.
 */
public enum InventoryRejectReason {
    /** 필수 필드가 없다. 공급사가 아무 말도 안 했다. */
    MISSING_FIELD,
    /** 요청 기간의 날짜를 정확히 덮지 않는다. 누락·중복·기간 밖을 모두 포함한다. */
    DATE_COVERAGE_MISMATCH,
    /** 계약상 불가능한 값. 음수 재고 등. */
    INVALID_INVENTORY
}
