package com.integration.stay.domain.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integration.stay.domain.DomainInvariantViolation;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StayPriceTest {

    private static final Currency KRW = Currency.getInstance("KRW");

    @Test
    @DisplayName("세액을 모르는 것과 세액이 0원인 것은 다르다")
    void 세액을_모르는_것과_세액이_0원인_것은_다르다() {
        StayPrice unknown = new StayPrice(new Money(452_000, KRW), Optional.empty(), Optional.empty());
        StayPrice zero = new StayPrice(new Money(452_000, KRW), Optional.of(Money.zero(KRW)), Optional.empty());

        assertThat(unknown.taxAmount()).isEmpty();
        assertThat(zero.taxAmount()).contains(Money.zero(KRW));
        assertThat(unknown).isNotEqualTo(zero);
    }

    @Test
    @DisplayName("빈 분해는 불가능한 상태라 거부한다")
    void 빈_분해는_불가능한_상태라_거부한다() {
        // "분해를 제공하지 않는 공급사" 는 empty 로 표현한다. 빈 리스트는 그 자리를 뺏는다.
        // 이것은 클라이언트 입력이 아니라 우리 조립 코드의 문제이므로 불변식 위반이다.
        assertThatThrownBy(() ->
                        new StayPrice(new Money(1_000, KRW), Optional.empty(), Optional.of(List.of())))
                .isInstanceOf(DomainInvariantViolation.class);
    }

    @Test
    @DisplayName("분해 리스트는 방어적으로 복사된다")
    void 분해_리스트는_방어적으로_복사된다() {
        var mutable = new java.util.ArrayList<NightlyPrice>();
        mutable.add(new NightlyPrice(LocalDate.parse("2026-09-01"), new Money(132_000, KRW), new Money(12_000, KRW)));

        StayPrice price = new StayPrice(new Money(132_000, KRW), Optional.empty(), Optional.of(mutable));
        mutable.clear();

        assertThat(price.nightlyBreakdown()).get().asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                .hasSize(1);
    }
}
