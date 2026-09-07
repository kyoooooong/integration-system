package com.integration.stay.domain.pricing;

import com.integration.stay.domain.DomainInvariantViolation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * 표준 요금. <b>총액(gross)을 표준으로 삼는다.</b>
 *
 * <p>한 공급사는 일별 단가와 세액을 주고(net), 다른 공급사는 기간 총액만 준다(gross).
 * 일별 → 총액의 합산은 계약이 보장하는 정확한 값이라 무손실이지만, 총액 → 일별의 분해는
 * 정보가 없어 불가능하다. <b>무손실 방향으로만 변환한다.</b>
 * 452,000 / 3 = 150,666 은 어느 날에도 실재하지 않는 금액이며, 검색 금액과 예약 금액이
 * 달라지는 사고로 이어진다.
 *
 * <p>잃는 것을 감추지 않는다. 총액만 주는 공급사의 상품은 일별 요금 비교도, 부분 취소
 * 금액 산정도, 세액 분리도 불가능하다. 그것을 {@code Optional.empty()} 로 명시한다.
 * <b>"세금이 얼마인지 모른다" 와 "세금이 0원이다" 는 다르다.</b>
 *
 * @param total 요청 기간 전체의 고객 결제 금액. 항상 세금 포함(gross)
 * @param taxAmount 계약상 알 수 없으면 empty. 0원이 아니다
 * @param nightlyBreakdown 일반적으로 컬렉션에 Optional 을 쓰지 않지만, 여기서는
 *     "빈 분해"(불가능한 상태)와 "분해를 제공하지 않는 공급사"(실재하는 상태)가 다른
 *     의미이므로 빈 리스트로 부재를 표현할 수 없다
 */
public record StayPrice(Money total, Optional<Money> taxAmount, Optional<List<NightlyPrice>> nightlyBreakdown) {

    /**
     * 일별 요금과 세액을 실제로 아는 공급사의 요금.
     *
     * <p>이름이 있는 생성 지점을 둔 이유는, 이 도메인의 핵심 구분이 <b>"일별 정보를 가진
     * 공급사" 와 "총액만 아는 공급사"</b> 이기 때문이다. 호출부에서
     * {@code new StayPrice(total, Optional.of(tax), Optional.of(breakdown))} 를 읽으면
     * Optional 세 개가 보이지만, {@code itemized(...)} 를 읽으면 그 구분이 보인다.
     *
     * <p>record 의 표준 생성자는 숨길 수 없다 — 접근 제한자가 레코드 자체보다 좁을 수 없다.
     * 다만 아래 compact 생성자가 불변식을 강제하므로, 표준 생성자를 직접 부르더라도
     * <b>잘못된 객체는 만들어지지 않는다.</b> 팩토리는 강제가 아니라 의도 표현이다.
     */
    public static StayPrice itemized(Money total, Money taxAmount, List<NightlyPrice> nightlyBreakdown) {
        return new StayPrice(total, Optional.of(taxAmount), Optional.of(nightlyBreakdown));
    }

    /**
     * 기간 총액만 아는 공급사의 요금.
     *
     * <p>세액과 일별 분해는 {@code empty} 다. <b>0이 아니라 "모른다" 는 뜻이다.</b>
     * 총액을 박수로 나눠 채우지 않는다 — 그렇게 만든 금액은 어느 날에도 실재하지 않고,
     * 검색 금액과 예약 금액이 달라지는 사고로 이어진다.
     */
    public static StayPrice totalOnly(Money total) {
        return new StayPrice(total, Optional.empty(), Optional.empty());
    }

    public StayPrice {
        Objects.requireNonNull(total, "total");
        Objects.requireNonNull(taxAmount, "taxAmount");
        Objects.requireNonNull(nightlyBreakdown, "nightlyBreakdown");
        nightlyBreakdown = nightlyBreakdown.map(List::copyOf);
        if (nightlyBreakdown.filter(List::isEmpty).isPresent()) {
            throw new DomainInvariantViolation("present nightlyBreakdown must not be empty");
        }
    }
}
