package com.integration.stay.domain.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.domain.StayPeriod;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 연박 재고 판정. 기준 예시 데이터(3,1,5 / 2,0,4)를 그대로 쓴다. */
class InventoryRuleTest {

    private static final StayPeriod THREE_NIGHTS =
            new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04"));

    @Test
    @DisplayName("연박 재고는 최솟값이다")
    void 연박_재고는_최솟값이다() {
        // 같은 객실 타입으로 3박을 연속 점유하려면 모든 숙박일에 재고가 있어야 한다.
        // 합계 9, 평균 3, 첫날 3 은 전부 틀린다.
        var rows = List.of(
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.of("2026-09-02", 1),
                TestInventoryRow.of("2026-09-03", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result)
                .isEqualTo(new InventoryEvaluation.Valid(new Availability(1)));
    }

    @Test
    @DisplayName("하루라도 0이면 예약 불가다")
    void 하루라도_0이면_예약_불가다() {
        var rows = List.of(
                TestInventoryRow.of("2026-09-01", 2),
                TestInventoryRow.of("2026-09-02", 0),
                TestInventoryRow.of("2026-09-03", 4));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result).isInstanceOf(InventoryEvaluation.Valid.class);
        var valid = (InventoryEvaluation.Valid) result;
        assertThat(valid.availability().bookableRooms()).isZero();
        assertThat(valid.availability().bookable()).isFalse();
    }

    @Test
    @DisplayName("날짜 누락은 0이 아니라 거부다")
    void 날짜_누락은_0이_아니라_거부다() {
        // 공급사가 아무 말도 안 한 것과 "없다" 고 말한 것은 다르다.
        // 0 으로 접으면 계약 위반이 정상 품절로 위장되어 지표에서 사라진다.
        var missingMiddleNight = List.of(
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.of("2026-09-03", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, missingMiddleNight);

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.DATE_COVERAGE_MISMATCH);
    }

    @Test
    @DisplayName("기간 밖 날짜는 거부한다")
    void 기간_밖_날짜는_거부한다() {
        // 행 수는 3개로 맞지만 마지막이 체크아웃일이다. 연속성 검사가 잡는다.
        var rows = List.of(
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.of("2026-09-02", 1),
                TestInventoryRow.of("2026-09-04", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.DATE_COVERAGE_MISMATCH);
    }

    @Test
    @DisplayName("중복 날짜는 거부한다")
    void 중복_날짜는_거부한다() {
        // 행 수는 맞고 전부 기간 안이지만 9/03 이 없다. 같은 연속성 검사 하나가 잡는다.
        var rows = List.of(
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.of("2026-09-02", 1),
                TestInventoryRow.of("2026-09-02", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.DATE_COVERAGE_MISMATCH);
    }

    @Test
    @DisplayName("음수 재고는 거부이지 품절이 아니다")
    void 음수_재고는_거부이지_품절이_아니다() {
        // clamp 하면 계약 위반 데이터가 정상 품절로 위장된다. 조치가 다르다.
        var rows = List.of(
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.of("2026-09-02", -1),
                TestInventoryRow.of("2026-09-03", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.INVALID_INVENTORY);
    }

    @Test
    @DisplayName("remainingRooms 누락은 MISSING_FIELD 로 거부한다")
    void remainingRooms_누락은_MISSING_FIELD로_거부한다() {
        var rows = List.of(
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.of("2026-09-02", null),
                TestInventoryRow.of("2026-09-03", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.MISSING_FIELD);
    }

    @Test
    @DisplayName("null row 는 MISSING_FIELD 로 거부한다")
    void null_row는_MISSING_FIELD로_거부한다() {
        // JSON 배열 [null] 도 실제 malformed input 이다. 정렬하다 NPE 로 죽지 않아야 한다.
        var rows = Arrays.asList(
                TestInventoryRow.of("2026-09-01", 3), null, TestInventoryRow.of("2026-09-03", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.MISSING_FIELD);
    }

    @Test
    @DisplayName("빈 재고는 거부한다")
    void 빈_재고는_거부한다() {
        var result = InventoryRule.evaluate(THREE_NIGHTS, List.of());

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.DATE_COVERAGE_MISMATCH);
    }

    @Test
    @DisplayName("공급사 날짜 순서가 뒤바뀌어도 판정은 같다")
    void 공급사_날짜_순서가_뒤바뀌어도_판정은_같다() {
        // 응답 배열 순서에 의존하면 출력이 비결정적이 된다.
        var shuffled = List.of(
                TestInventoryRow.of("2026-09-03", 5),
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.of("2026-09-02", 1));

        var result = InventoryRule.evaluate(THREE_NIGHTS, shuffled);

        assertThat(result).isEqualTo(new InventoryEvaluation.Valid(new Availability(1)));
    }

    @Test
    @DisplayName("date 가 null 인 행은 MISSING_FIELD 로 거부한다")
    void date가_null인_행은_MISSING_FIELD로_거부한다() {
        // nullsFirst 정렬이므로 null 이 맨 앞에 오고 연속성 검사보다 먼저 걸린다.
        var rows = List.of(
                TestInventoryRow.of("2026-09-01", 3),
                TestInventoryRow.withoutDate(1),
                TestInventoryRow.of("2026-09-03", 5));

        var result = InventoryRule.evaluate(THREE_NIGHTS, rows);

        assertThat(result)
                .isInstanceOf(InventoryEvaluation.Invalid.class)
                .extracting(r -> ((InventoryEvaluation.Invalid) r).reason())
                .isEqualTo(InventoryRejectReason.MISSING_FIELD);
    }
}
