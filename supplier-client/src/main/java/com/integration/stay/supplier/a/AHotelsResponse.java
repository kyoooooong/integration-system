package com.integration.stay.supplier.a;

import java.util.List;

/** Supplier A 숙소 목록. 요금·재고·조식은 여기 없다. */
public record AHotelsResponse(List<AHotel> items) {

    public record AHotel(String hotelCode, String hotelName, List<ARoomType> roomTypes) {}

    public record ARoomType(String roomTypeCode, String roomTypeName, Integer maxOccupancy) {}
}
