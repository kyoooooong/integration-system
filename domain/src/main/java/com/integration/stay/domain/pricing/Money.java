package com.integration.stay.domain.pricing;

import java.util.Currency;
import java.util.Objects;

/**
 * 금액. {@code amount} 는 해당 통화의 <b>최소 단위 정수</b>다
 * (KRW 120000 = ₩120,000, USD 12345 = $123.45).
 *
 * <p>공급사와 우리 응답이 같은 표현이므로 변환 자체가 없고, 그래서 무손실이다.
 * major unit 으로의 변환은 표시 계층의 관심사다.
 *
 * <p>{@code BigDecimal} 이 아닌 이유는 계약이 이미 최소 단위 정수이기 때문이다.
 * 스케일을 우리가 관리하지 않아도 되고, equals 가 스케일까지 비교하는 함정도 없다.
 * BigDecimal 의 강점인 나눗셈은 우리가 하지 않는다 — 총액을 박수로 나누는 것은
 * 없는 정보를 만들어내는 일이라 설계에서 배제했다.
 *
 * <p>{@code Comparable} 을 구현하지 않는다. 현재 범위에 금액 비교가 없다.
 * 조식 포함 여부 같은 조건이 다른 상품끼리 금액만 비교하는 것을 구조적으로 막는다.
 */
public record Money(long amount, Currency currency) {

    public Money {
        Objects.requireNonNull(currency, "currency");
        if (amount < 0) {
            throw new NegativeMoneyException(amount, currency);
        }
    }

    public static Money zero(Currency currency) {
        return new Money(0L, currency);
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        try {
            // 30박 합산에서 오버플로는 현실적으로 불가능하지만 공급사가 극단값을 보내면 가능하고,
            // '+' 는 조용히 음수를 만든다. 경계를 명시적으로 검사한다.
            return new Money(Math.addExact(amount, other.amount), currency);
        } catch (ArithmeticException e) {
            throw new MoneyOverflowException(this, other);
        }
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new CurrencyMismatchException(currency, other.currency);
        }
    }
}
