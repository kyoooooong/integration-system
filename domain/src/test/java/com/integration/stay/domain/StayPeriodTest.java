package com.integration.stay.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class StayPeriodTest {

    @Test
    @DisplayName("체크아웃일은 숙박일에 포함되지 않는다")
    void 체크아웃일은_숙박일에_포함되지_않는다() {
        StayPeriod period = new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04"));

        assertThat(period.nightCount()).isEqualTo(3);
        assertThat(period.containsNight(LocalDate.parse("2026-09-03"))).isTrue();
        assertThat(period.containsNight(LocalDate.parse("2026-09-04"))).isFalse();
    }

    @Test
    @DisplayName("체크아웃이 체크인보다 뒤가 아니면 거부한다")
    void 체크아웃이_체크인보다_뒤가_아니면_거부한다() {
        LocalDate day = LocalDate.parse("2026-09-01");

        assertThatThrownBy(() -> new StayPeriod(day, day)).isInstanceOf(InvalidStayPeriodException.class);
        assertThatThrownBy(() -> new StayPeriod(day, day.minusDays(1)))
                .isInstanceOf(InvalidStayPeriodException.class);
    }

    @Test
    @DisplayName("긴 기간도 상수 시간에 계산한다")
    void 긴_기간도_상수_시간에_계산한다() {
        // 날짜 컬렉션을 만들었다면 요청 문자열 하나로 힙을 태울 수 있다.
        // 이 테스트가 통과한다는 것은 그 컬렉션이 없다는 뜻이다.
        StayPeriod millennium = new StayPeriod(LocalDate.parse("2026-01-01"), LocalDate.parse("3026-01-01"));

        assertThat(millennium.nightCount()).isEqualTo(365_242L);
    }
}
