package com.integration.stay.domain.pricing;

public class MoneyOverflowException extends RuntimeException {

    public MoneyOverflowException(Money left, Money right) {
        super("money overflow: %s + %s".formatted(left, right));
    }
}
