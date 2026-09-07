package com.integration.stay.storage.catalog;

import com.integration.stay.application.catalog.CatalogProperty;
import com.integration.stay.application.catalog.CatalogRoomType;
import com.integration.stay.domain.SupplierId;
import java.util.List;

final class CatalogFixtures {

    static final SupplierId A = new SupplierId("A");
    static final SupplierId B = new SupplierId("B");

    private CatalogFixtures() {}

    static CatalogProperty property(String code, String name, CatalogRoomType... roomTypes) {
        return new CatalogProperty(code, name, List.of(roomTypes));
    }

    static CatalogRoomType roomType(String code, String name, int maxOccupancy) {
        return new CatalogRoomType(code, name, maxOccupancy);
    }
}
