package com.integration.stay.supplier.b;

import com.integration.stay.domain.inventory.DailyInventory;
import java.time.LocalDate;

/** Supplier B 의 일별 재고 행. B 는 날짜별 요금을 제공하지 않으므로 재고만 있다. */
public record BInventoryRow(LocalDate date, Integer remainingRooms) implements DailyInventory {}
