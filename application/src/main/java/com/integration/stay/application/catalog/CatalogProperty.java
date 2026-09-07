package com.integration.stay.application.catalog;

import java.util.List;

/** 공급사 숙소 목록 API 가 알려주는 숙소 하나. */
public record CatalogProperty(String code, String name, List<CatalogRoomType> roomTypes) {

    public CatalogProperty {
        roomTypes = List.copyOf(roomTypes);
    }
}
