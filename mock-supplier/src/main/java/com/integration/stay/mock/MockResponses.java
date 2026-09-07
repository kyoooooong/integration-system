package com.integration.stay.mock;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

/**
 * 요청 파라미터에 맞춰 공급사 응답을 만든다.
 *
 * <p>지키는 계약은 셋이다.
 *
 * <ul>
 *   <li>요청한 숙박일을 <b>정확히</b> 덮는 행을 준다 (체크아웃일 제외)
 *   <li>요청 인원을 수용할 수 있는 객실 타입만 반환한다
 *   <li>요청한 숙소 코드에 대해서만 응답한다 (response ⊆ requested)
 * </ul>
 *
 * <p>JSON 을 문자열로 조립한다. Mock 은 제품 코드가 아니고 여기에 직렬화 라이브러리를
 * 끌어오면 본 애플리케이션의 Jackson 설정과 뒤섞여 무엇을 검증하는지 흐려진다.
 */
final class MockResponses {

    private MockResponses() {}

    static Set<String> requestedCodes(String commaSeparated) {
        return Arrays.stream(commaSeparated.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toSet());
    }

    private static int nights(LocalDate checkIn, LocalDate checkOut) {
        return (int) ChronoUnit.DAYS.between(checkIn, checkOut);
    }

    /** Supplier A — 날짜별 1박 단가, 세금 별도. */
    static String availabilityA(
            Set<String> codes, LocalDate checkIn, LocalDate checkOut, int adults, int children) {
        StringJoiner items = new StringJoiner(",\n    ");
        for (MockCatalog.Property property : MockCatalog.A) {
            if (!codes.contains(property.code())) {
                continue;
            }
            for (MockCatalog.RoomType roomType : property.roomTypes()) {
                if (!MockCatalog.accommodates(roomType, adults, children)) {
                    continue;
                }
                items.add(itemA(property, roomType, checkIn, nights(checkIn, checkOut)));
            }
        }
        return "{ \"items\": [\n    " + items + "\n  ] }";
    }

    private static String itemA(
            MockCatalog.Property property, MockCatalog.RoomType roomType, LocalDate checkIn, int nights) {
        StringJoiner rates = new StringJoiner(",\n        ");
        for (int i = 0; i < nights; i++) {
            LocalDate date = checkIn.plusDays(i);
            int net = MockCatalog.at(roomType.nightlyNet(), date);
            rates.add(
                    """
                    { "date": "%s", "remainingRooms": %d, "nightlyRate": %d, "taxAmount": %d }"""
                            .formatted(
                                    date,
                                    MockCatalog.at(roomType.remaining(), date),
                                    net,
                                    net * MockCatalog.TAX_PERCENT / 100));
        }
        return """
               { "hotelCode": "%s", "hotelName": "%s",
                 "roomTypeCode": "%s", "roomTypeName": "%s",
                 "maxOccupancy": %d, "breakfastIncluded": false, "currency": "KRW",
                 "dailyRates": [
                     %s
                 ] }"""
                .formatted(
                        property.code(),
                        property.name(),
                        roomType.code(),
                        roomType.name(),
                        roomType.maxOccupancy(),
                        rates);
    }

    /** Supplier B — 기간 총액, 세금 포함. 날짜별 요금은 주지 않는다. */
    static String searchB(Set<String> codes, LocalDate checkIn, LocalDate checkOut, int adults, int children) {
        int nights = nights(checkIn, checkOut);
        StringJoiner items = new StringJoiner(",\n      ");
        for (MockCatalog.Property property : MockCatalog.B) {
            if (!codes.contains(property.code())) {
                continue;
            }
            for (MockCatalog.RoomType roomType : property.roomTypes()) {
                if (!MockCatalog.accommodates(roomType, adults, children)) {
                    continue;
                }
                items.add(itemB(property, roomType, checkIn, nights));
            }
        }
        return """
               { "resultCode": "0000", "resultMessage": "SUCCESS",
                 "data": { "items": [
                   %s
                 ] } }"""
                .formatted(items);
    }

    private static String itemB(
            MockCatalog.Property property, MockCatalog.RoomType roomType, LocalDate checkIn, int nights) {
        long total = 0;
        StringJoiner inventory = new StringJoiner(",\n            ");
        for (int i = 0; i < nights; i++) {
            LocalDate date = checkIn.plusDays(i);
            total += MockCatalog.at(roomType.nightlyNet(), date);
            inventory.add("""
                    { "date": "%s", "remainingRooms": %d }"""
                    .formatted(date, MockCatalog.at(roomType.remaining(), date)));
        }
        return """
               { "propertyId": "%s", "propertyName": "%s",
                 "roomId": "%s", "roomName": "%s",
                 "maxOccupancy": %d, "breakfastIncluded": true, "currency": "KRW",
                 "totalPrice": %d, "taxIncluded": true,
                 "inventory": [
                     %s
                 ] }"""
                .formatted(
                        property.code(),
                        property.name(),
                        roomType.code(),
                        roomType.name(),
                        roomType.maxOccupancy(),
                        total,
                        inventory);
    }

    /** 숙소 목록. 조건 파라미터가 없고 요금·재고도 없다. */
    static String hotelsA() {
        return propertyList(MockCatalog.A, "hotelCode", "hotelName", "roomTypes", "roomTypeCode", "roomTypeName");
    }

    static String propertiesB() {
        return """
               { "resultCode": "0000", "resultMessage": "SUCCESS",
                 "data": %s }"""
                .formatted(propertyList(MockCatalog.B, "propertyId", "propertyName", "rooms", "roomId", "roomName"));
    }

    private static String propertyList(
            List<MockCatalog.Property> catalog,
            String codeKey,
            String nameKey,
            String roomsKey,
            String roomCodeKey,
            String roomNameKey) {
        StringJoiner items = new StringJoiner(",\n    ");
        for (MockCatalog.Property property : catalog) {
            StringJoiner rooms = new StringJoiner(",\n        ");
            for (MockCatalog.RoomType roomType : property.roomTypes()) {
                rooms.add("""
                        { "%s": "%s", "%s": "%s", "maxOccupancy": %d }"""
                        .formatted(
                                roomCodeKey,
                                roomType.code(),
                                roomNameKey,
                                roomType.name(),
                                roomType.maxOccupancy()));
            }
            items.add("""
                    { "%s": "%s", "%s": "%s", "%s": [
                        %s
                    ] }"""
                    .formatted(codeKey, property.code(), nameKey, property.name(), roomsKey, rooms));
        }
        return "{ \"items\": [\n    " + items + "\n  ] }";
    }
}
