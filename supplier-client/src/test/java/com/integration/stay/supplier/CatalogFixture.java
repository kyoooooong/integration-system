package com.integration.stay.supplier;

import com.integration.stay.application.catalog.CatalogSnapshot;
import java.util.UUID;

/** 공급사 스펙의 예시 데이터에 대응하는 카탈로그 스냅샷. */
public final class CatalogFixture {

    public static final UUID RIVERSIDE_A_PROPERTY = UUID.fromString("11111111-1111-1111-1111-111111111111");
    public static final UUID RIVERSIDE_A_ROOM = UUID.fromString("11111111-1111-1111-1111-1111111111a1");
    public static final UUID NAMSAN_PROPERTY = UUID.fromString("22222222-2222-2222-2222-222222222222");
    public static final UUID NAMSAN_ROOM = UUID.fromString("22222222-2222-2222-2222-2222222222a1");
    // 같은 호텔이지만 공급사가 다르면 내부 식별자도 다르다. 중복 병합은 하지 않는다.
    public static final UUID RIVERSIDE_B_PROPERTY = UUID.fromString("33333333-3333-3333-3333-333333333333");
    public static final UUID RIVERSIDE_B_ROOM = UUID.fromString("33333333-3333-3333-3333-3333333333a1");

    private CatalogFixture() {}

    public static CatalogSnapshot snapshot() {
        return CatalogSnapshot.builder()
                .roomType(
                        SupplierIds.A,
                        "A-10023",
                        RIVERSIDE_A_PROPERTY,
                        "Riverside Hotel Seoul",
                        "DLX-TWN",
                        RIVERSIDE_A_ROOM,
                        "Deluxe Twin")
                .roomType(
                        SupplierIds.A,
                        "A-10044",
                        NAMSAN_PROPERTY,
                        "Namsan Garden Stay",
                        "STD-DBL",
                        NAMSAN_ROOM,
                        "Standard Double")
                .roomType(
                        SupplierIds.B,
                        "B77120",
                        RIVERSIDE_B_PROPERTY,
                        "Riverside Hotel Seoul",
                        "R-401",
                        RIVERSIDE_B_ROOM,
                        "Deluxe Twin Room")
                .build();
    }
}
