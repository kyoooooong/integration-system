package com.integration.stay.domain;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/**
 * 숙박 기간. 체크아웃일은 숙박일에 포함되지 않는다 (9/1~9/4 = 3박).
 *
 * <p><b>날짜 컬렉션을 만들지 않는 것이 이 타입의 핵심이다.</b> nights().toList() 형태였다면
 * {@code ?checkIn=2026-01-01&checkOut=3026-01-01} 요청 하나로 우리가 LocalDate 36만 개를
 * 할당하게 된다.
 *
 * <p>현재 계약에 최대 숙박일이 정의되어 있지 않아 30박·90박 같은 임의 제한은 두지 않는다.
 * {@link #nightCount()} 는 O(1)이지만, Supplier 응답 크기와 일별 재고 검증 비용은 숙박일 수에
 * 따라 늘 수 있다. 실제 최대 조회 기간이나 서비스 정책이 생기면 입력 단계에서 제한한다.
 */
public record StayPeriod(LocalDate checkIn, LocalDate checkOut) {

    public StayPeriod {
        Objects.requireNonNull(checkIn, "checkIn");
        Objects.requireNonNull(checkOut, "checkOut");
        if (!checkOut.isAfter(checkIn)) {
            throw new InvalidStayPeriodException(checkIn, checkOut);
        }
    }

    /** O(1). 날짜 컬렉션을 만들지 않는다. */
    public long nightCount() {
        return ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    public boolean containsNight(LocalDate date) {
        return !date.isBefore(checkIn) && date.isBefore(checkOut);
    }
}
