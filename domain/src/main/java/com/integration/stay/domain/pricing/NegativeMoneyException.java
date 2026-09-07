package com.integration.stay.domain.pricing;

import java.util.Currency;

public class NegativeMoneyException extends RuntimeException {

    public NegativeMoneyException(long amount, Currency currency) {
        super("amount must be >= 0: amount=%d, currency=%s".formatted(amount, currency));
    }
}
