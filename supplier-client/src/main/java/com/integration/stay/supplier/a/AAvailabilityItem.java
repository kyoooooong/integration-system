package com.integration.stay.supplier.a;

import java.util.List;

/** Supplier A 의 재고·요금 조회 item. */
public record AAvailabilityItem(
        String hotelCode,
        String hotelName,
        String roomTypeCode,
        String roomTypeName,
        Integer maxOccupancy,
        Boolean breakfastIncluded,
        String currency,
        List<ADailyRate> dailyRates) {}
