package com.integration.stay.application.catalog;

/** 공급사 숙소 목록 API 가 알려주는 객실 타입 하나. 요금·재고는 여기 없다. */
public record CatalogRoomType(String code, String name, int maxOccupancy) {}
