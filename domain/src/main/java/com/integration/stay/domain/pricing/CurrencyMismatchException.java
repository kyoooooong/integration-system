package com.integration.stay.domain.pricing;

import java.util.Currency;

/** 환율 변환은 하지 않는다. 이종 통화 연산은 예외다. */
public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(Currency left, Currency right) {
        super("currency mismatch: %s vs %s".formatted(left, right));
    }
}
