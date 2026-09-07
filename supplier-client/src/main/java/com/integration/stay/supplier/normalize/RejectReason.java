package com.integration.stay.supplier.normalize;

/**
 * 정규화 거부 사유. <b>지표 레이블로 쓰인다.</b>
 *
 * <p>하나로 뭉치지 않는 이유는 각 사유가 서로 다른 사람의 서로 다른 행동을 유발하기 때문이다.
 * DATE_COVERAGE_MISMATCH 는 공급사에 문의할 일이고, UNMAPPED_ROOM_TYPE 은 우리 카탈로그가
 * 낡은 것이며, UNKNOWN_CURRENCY 는 우리 파싱 문제일 수도 있다.
 */
public enum RejectReason {
    /** 응답에 필수 필드가 없다. */
    MISSING_REQUIRED_FIELD,
    /** 우리가 요청하지 않은 숙소가 응답에 있다. 카탈로그에 있어도 거부한다. */
    UNEXPECTED_PROPERTY,
    /** 그 공급사 카탈로그에 이 숙소 코드가 없다. */
    UNMAPPED_PROPERTY,
    /** 숙소는 있는데 그 안에 이 객실 타입 코드가 없다. */
    UNMAPPED_ROOM_TYPE,
    /** ISO 4217 코드로 해석되지 않는다. */
    UNKNOWN_CURRENCY,
    /** 요청 기간의 날짜를 정확히 덮지 않는다. */
    DATE_COVERAGE_MISMATCH,
    /** 계약상 불가능한 재고 값. */
    INVALID_INVENTORY,
    /** 계약상 불가능한 금액 값. */
    INVALID_AMOUNT,
    /**
     * 데이터가 잘못된 게 아니라 <b>우리 가정이 깨졌다.</b>
     * 지표에 뜨면 공급사에 문의할 게 아니라 우리 코드를 고쳐야 한다.
     */
    CONTRACT_ASSUMPTION_BROKEN
}
