package com.integration.stay.domain.inventory;

import java.time.LocalDate;

/** 테스트용 DailyInventory 구현. 공급사 DTO 자리에 들어간다. */
record TestInventoryRow(LocalDate date, Integer remainingRooms) implements DailyInventory {

    static TestInventoryRow of(String date, Integer remainingRooms) {
        return new TestInventoryRow(LocalDate.parse(date), remainingRooms);
    }

    static TestInventoryRow withoutDate(Integer remainingRooms) {
        return new TestInventoryRow(null, remainingRooms);
    }
}
