package com.integration.stay.supplier.b;

import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.domain.StayPeriod;
import com.integration.stay.domain.pricing.Money;
import com.integration.stay.supplier.CatalogFixture;
import com.integration.stay.supplier.SupplierIds;
import com.integration.stay.supplier.normalize.OfferNormalizationResult;
import com.integration.stay.supplier.normalize.RejectReason;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BNormalizerTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final SearchCommand THREE_NIGHTS = new SearchCommand(
            new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);
    private static final Set<String> REQUESTED = Set.of("B77120");

    private static BInventoryRow row(String date, Integer remaining) {
        return new BInventoryRow(LocalDate.parse(date), remaining);
    }

    private static final List<BInventoryRow> INVENTORY =
            List.of(row("2026-09-01", 3), row("2026-09-02", 1), row("2026-09-03", 5));

    private static BSearchItem riverside(Long totalPrice, Boolean taxIncluded) {
        return new BSearchItem(
                "B77120",
                "Riverside Hotel Seoul",
                "R-401",
                "Deluxe Twin Room",
                2,
                true,
                "KRW",
                totalPrice,
                taxIncluded,
                INVENTORY);
    }

    @Test
    @DisplayName("B 의 총액은 그대로 452,000 이다")
    void B_total_그대로() {
        var result = BNormalizer.normalize(riverside(452_000L, true), THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result).isInstanceOf(OfferNormalizationResult.Success.class);
        var offer = ((OfferNormalizationResult.Success) result).offer();

        assertThat(offer.price().total()).isEqualTo(new Money(452_000, KRW));
        assertThat(offer.availability().bookableRooms()).isEqualTo(1);
        // 같은 호텔·같은 객실 타입이지만 조식 조건이 A 와 다르다. 그래서 병합하지 않는다.
        assertThat(offer.conditions().breakfastIncluded()).isTrue();
        assertThat(offer.source()).isEqualTo(SupplierIds.B);
        // 공급사가 다르면 내부 식별자도 다르다.
        assertThat(offer.propertyId())
                .isEqualTo(CatalogFixture.RIVERSIDE_B_PROPERTY)
                .isNotEqualTo(CatalogFixture.RIVERSIDE_A_PROPERTY);
    }

    @Test
    @DisplayName("B 의 세액과 일별 분해는 0이 아니라 empty 다")
    void B_tax_와_breakdown_은_empty() {
        var result = BNormalizer.normalize(riverside(452_000L, true), THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());
        var offer = ((OfferNormalizationResult.Success) result).offer();

        // "세금이 얼마인지 모른다" 와 "세금이 0원이다" 는 다르다.
        assertThat(offer.price().taxAmount()).isEmpty();
        assertThat(offer.price().taxAmount()).isNotEqualTo(java.util.Optional.of(Money.zero(KRW)));
        // 452,000 / 3 = 150,666 은 어느 날에도 실재하지 않는다. 만들어내지 않는다.
        assertThat(offer.price().nightlyBreakdown()).isEmpty();
    }

    @Test
    @DisplayName("taxIncluded 가 false 면 gross 가정이 깨진 것이므로 거부한다")
    void taxIncluded가_false면_거부한다() {
        // 세금을 추측해 더하지 않는다. 그 값을 gross 로 취급하면 실제 결제 금액보다 작게 노출된다.
        var result = BNormalizer.normalize(riverside(452_000L, false), THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result)
                .isInstanceOf(OfferNormalizationResult.Rejected.class)
                .extracting(r -> ((OfferNormalizationResult.Rejected) r).reason())
                .isEqualTo(RejectReason.CONTRACT_ASSUMPTION_BROKEN);
    }

    @Test
    @DisplayName("taxIncluded 필드 부재는 명시적 false 와 다르게 거부한다")
    void taxIncluded_필드_부재는_명시적_false와_다르게_거부한다() {
        // boxed 가 아니었다면 이 둘이 같은 값이 되어 구분 자체가 불가능하다.
        var result = BNormalizer.normalize(riverside(452_000L, null), THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result)
                .isInstanceOf(OfferNormalizationResult.Rejected.class)
                .extracting(r -> ((OfferNormalizationResult.Rejected) r).reason())
                .isEqualTo(RejectReason.MISSING_REQUIRED_FIELD);
    }

    @Test
    @DisplayName("날짜가 누락되면 0이 아니라 거부한다")
    void 날짜가_누락되면_0이_아니라_거부한다() {
        var missing = new BSearchItem(
                "B77120", "Riverside Hotel Seoul", "R-401", "Deluxe Twin Room", 2, true, "KRW", 452_000L, true,
                List.of(row("2026-09-01", 3), row("2026-09-03", 5)));

        var result = BNormalizer.normalize(missing, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result)
                .isInstanceOf(OfferNormalizationResult.Rejected.class)
                .extracting(r -> ((OfferNormalizationResult.Rejected) r).reason())
                .isEqualTo(RejectReason.DATE_COVERAGE_MISMATCH);
    }

    @Test
    @DisplayName("알 수 없는 통화는 거부한다")
    void 알_수_없는_통화는_거부한다() {
        var item = new BSearchItem(
                "B77120", "Riverside Hotel Seoul", "R-401", "Deluxe Twin Room", 2, true, "XYZ", 452_000L, true,
                INVENTORY);

        var result = BNormalizer.normalize(item, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result)
                .isInstanceOf(OfferNormalizationResult.Rejected.class)
                .extracting(r -> ((OfferNormalizationResult.Rejected) r).reason())
                .isEqualTo(RejectReason.UNKNOWN_CURRENCY);
    }

    @Test
    @DisplayName("카탈로그에 없는 객실 타입은 UNMAPPED_ROOM_TYPE 이다")
    void 카탈로그에_없는_객실_타입은_UNMAPPED_ROOM_TYPE이다() {
        // 숙소는 아는데 객실 타입을 모르는 것과 숙소 자체를 모르는 것은 조치가 다르다.
        var item = new BSearchItem(
                "B77120", "Riverside Hotel Seoul", "R-999", "New Suite", 2, true, "KRW", 452_000L, true, INVENTORY);

        var result = BNormalizer.normalize(item, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result)
                .isInstanceOf(OfferNormalizationResult.Rejected.class)
                .extracting(r -> ((OfferNormalizationResult.Rejected) r).reason())
                .isEqualTo(RejectReason.UNMAPPED_ROOM_TYPE);
    }
}
