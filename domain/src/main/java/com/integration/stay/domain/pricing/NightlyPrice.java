package com.integration.stay.domain.pricing;

import java.time.LocalDate;

/** 일별 요금 분해. {@code grossAmount} 는 세금 포함 금액이다. */
public record NightlyPrice(LocalDate date, Money grossAmount, Money taxAmount) {}
