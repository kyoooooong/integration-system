package com.integration.stay.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Currency;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    @DisplayName("같은 통화끼리 합산한다")
    void 같은_통화끼리_합산한다() {
        Money sum = new Money(120_000, KRW).plus(new Money(12_000, KRW));

        assertThat(sum).isEqualTo(new Money(132_000, KRW));
    }

    @Test
    @DisplayName("이종 통화 연산은 예외다")
    void 이종_통화_연산은_예외다() {
        // 환율 변환은 하지 않는다. 조용히 더하면 의미 없는 숫자가 나온다.
        assertThatThrownBy(() -> new Money(1_000, KRW).plus(new Money(1_000, USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("합산 오버플로는 조용히 음수가 되지 않는다")
    void 합산_오버플로는_조용히_음수가_되지_않는다() {
        // '+' 는 조용히 음수를 만든다. 공급사가 극단값을 보내면 실제로 가능하다.
        assertThatThrownBy(() -> new Money(Long.MAX_VALUE, KRW).plus(new Money(1, KRW)))
                .isInstanceOf(MoneyOverflowException.class);
    }

    @Test
    @DisplayName("음수 금액은 거부한다")
    void 음수_금액은_거부한다() {
        assertThatThrownBy(() -> new Money(-1, KRW)).isInstanceOf(NegativeMoneyException.class);
    }
}
