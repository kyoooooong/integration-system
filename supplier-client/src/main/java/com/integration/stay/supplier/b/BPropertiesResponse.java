package com.integration.stay.supplier.b;

import java.util.List;

/** Supplier B 숙소 목록. 목록 API 도 봉투를 쓴다. */
public record BPropertiesResponse(String resultCode, String resultMessage, BPropertiesData data) {

    public record BPropertiesData(List<BProperty> items) {}

    public record BProperty(String propertyId, String propertyName, List<BRoom> rooms) {}

    public record BRoom(String roomId, String roomName, Integer maxOccupancy) {}
}
