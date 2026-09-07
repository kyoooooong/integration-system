package com.integration.stay.domain.offer;

/**
 * 상품의 판매 조건.
 *
 * <p>{@code RatePlan} 이라 부르지 않는다. RatePlan 은 숙박 유통에서 "동일 객실에 병렬
 * 존재하는 복수 판매 상품" 을 뜻하는 확립된 용어인데, 현재 계약은 객실 타입당 한 벌만
 * 내려준다. 그 이름을 쓰면 모델이 사실보다 넓게 말하게 된다.
 * <b>이름은 계약보다 앞서면 안 된다.</b>
 *
 * <p>조식 포함 여부를 반드시 유지한다. A 429,000(조식 미포함)과 B 452,000(조식 포함)의
 * 차이 중 일부는 조식이다. 이 필드를 버리면 비교 불가능한 것을 비교 가능한 척하게 된다.
 */
public record OfferConditions(boolean breakfastIncluded) {}
