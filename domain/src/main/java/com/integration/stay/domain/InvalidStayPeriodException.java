package com.integration.stay.domain;

import java.time.LocalDate;

public class InvalidStayPeriodException extends RuntimeException {

    public InvalidStayPeriodException(LocalDate checkIn, LocalDate checkOut) {
        super("checkOut must be after checkIn: checkIn=%s, checkOut=%s".formatted(checkIn, checkOut));
    }
}
