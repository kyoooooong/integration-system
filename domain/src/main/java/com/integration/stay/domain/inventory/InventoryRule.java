package com.integration.stay.domain.inventory;

import static java.util.Comparator.comparing;
import static java.util.Comparator.naturalOrder;
import static java.util.Comparator.nullsFirst;

import com.integration.stay.domain.StayPeriod;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

/**
 * 연박 재고 판정.
 *
 * <p><b>연박 예약 가능 객실 수 = 일별 잔여 수의 최솟값이다.</b> 같은 객실 타입으로 N박을
 * 연속 점유하려면 모든 숙박일에 재고가 있어야 한다. 합계(3+1+5=9)나 평균(3)이나
 * 첫날 값(3)은 전부 틀린다 — 3,1,5 의 정답은 1이다.
 *
 * <p>세 상태를 절대 합치지 않는다.
 * <ul>
 *   <li>{@code 0} — 공급사가 "없다" 고 말했다. 정상이며 품절이다
 *   <li>누락 — 공급사가 아무 말도 안 했다. 거부한다
 *   <li>음수 — 계약상 불가능한 값이다. 거부한다
 * </ul>
 * 음수를 0 으로 clamp 하면 계약 위반 데이터가 정상 품절로 위장되고, 지표에서 원인을
 * 구분할 수 없게 된다.
 */
public final class InventoryRule {

    private InventoryRule() {}

    public static InventoryEvaluation evaluate(StayPeriod period, List<? extends DailyInventory> rows) {
        if (rows == null || rows.isEmpty()) {
            return invalid(InventoryRejectReason.DATE_COVERAGE_MISMATCH, "empty");
        }
        // JSON 배열 [null] 도 실제 malformed input 이다. 정렬 전에 거른다.
        if (rows.stream().anyMatch(Objects::isNull)) {
            return invalid(InventoryRejectReason.MISSING_FIELD, "null row");
        }
        // 정렬 전에 거부한다. 긴 기간 요청에서 아무것도 할당하지 않는다.
        if (rows.size() != period.nightCount()) {
            return invalid(
                    InventoryRejectReason.DATE_COVERAGE_MISMATCH,
                    "expected %d nights, got %d rows".formatted(period.nightCount(), rows.size()));
        }

        // 응답 배열 순서에 의존하면 출력이 비결정적이 된다.
        List<? extends DailyInventory> sorted = rows.stream()
                .sorted(comparing(DailyInventory::date, nullsFirst(naturalOrder())))
                .toList();

        LocalDate cursor = period.checkIn();
        int min = Integer.MAX_VALUE;
        for (DailyInventory row : sorted) {
            if (row.date() == null) {
                return invalid(InventoryRejectReason.MISSING_FIELD, "date");
            }
            if (row.remainingRooms() == null) {
                return invalid(InventoryRejectReason.MISSING_FIELD, "remainingRooms");
            }
            if (row.remainingRooms() < 0) {
                return invalid(InventoryRejectReason.INVALID_INVENTORY, "remainingRooms=" + row.remainingRooms());
            }
            // 연속성 하나로 누락·중복·기간 밖을 전부 잡는다.
            if (!row.date().equals(cursor)) {
                return invalid(
                        InventoryRejectReason.DATE_COVERAGE_MISMATCH,
                        "expected %s, got %s".formatted(cursor, row.date()));
            }
            min = Math.min(min, row.remainingRooms());
            cursor = cursor.plusDays(1);
        }
        return new InventoryEvaluation.Valid(new Availability(min));
    }

    private static InventoryEvaluation invalid(InventoryRejectReason reason, String detail) {
        return new InventoryEvaluation.Invalid(reason, detail);
    }
}
