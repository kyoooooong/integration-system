package com.integration.stay.supplier.a;

import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.domain.StayPeriod;
import com.integration.stay.domain.pricing.Money;
import com.integration.stay.domain.pricing.NightlyPrice;
import com.integration.stay.supplier.CatalogFixture;
import com.integration.stay.supplier.SupplierIds;
import com.integration.stay.supplier.normalize.Diagnostic;
import com.integration.stay.supplier.normalize.OfferNormalizationResult;
import com.integration.stay.supplier.normalize.RejectReason;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 기준 예시 데이터를 그대로 쓴다. 값을 눈으로 대조할 수 있어야 한다. */
class ANormalizerTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final SearchCommand THREE_NIGHTS = new SearchCommand(
            new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);
    private static final Set<String> REQUESTED = Set.of("A-10023", "A-10044");

    private static ADailyRate rate(String date, int remaining, long nightly, long tax) {
        return new ADailyRate(LocalDate.parse(date), remaining, nightly, tax);
    }

    private static AAvailabilityItem riverside(List<ADailyRate> rates) {
        return new AAvailabilityItem(
                "A-10023", "Riverside Hotel Seoul", "DLX-TWN", "Deluxe Twin", 2, false, "KRW", rates);
    }

    @Test
    @DisplayName("A 는 일별 net 과 세액을 합산해 총액 gross 429,000 이 된다")
    void A_gross_total_계산() {
        var item = riverside(List.of(
                rate("2026-09-01", 3, 120_000, 12_000),
                rate("2026-09-02", 1, 150_000, 15_000),
                rate("2026-09-03", 5, 120_000, 12_000)));

        var result = ANormalizer.normalize(item, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result).isInstanceOf(OfferNormalizationResult.Success.class);
        var offer = ((OfferNormalizationResult.Success) result).offer();

        // 132,000 + 165,000 + 132,000
        assertThat(offer.price().total()).isEqualTo(new Money(429_000, KRW));
        // 12,000 + 15,000 + 12,000. A 는 세액을 실제로 알고 있으므로 보존한다.
        assertThat(offer.price().taxAmount()).contains(new Money(39_000, KRW));
        // 연박 재고 min(3,1,5)
        assertThat(offer.availability().bookableRooms()).isEqualTo(1);
        assertThat(offer.conditions().breakfastIncluded()).isFalse();
        // 내부 식별자는 카탈로그가 소유한다. 공급사 코드가 새어 나가지 않는다.
        assertThat(offer.propertyId()).isEqualTo(CatalogFixture.RIVERSIDE_A_PROPERTY);
        assertThat(offer.roomTypeId()).isEqualTo(CatalogFixture.RIVERSIDE_A_ROOM);
        assertThat(offer.source()).isEqualTo(SupplierIds.A);
    }

    @Test
    @DisplayName("A 의 일별 분해는 gross 로 보존된다")
    void A의_일별_분해는_gross로_보존된다() {
        var item = riverside(List.of(
                rate("2026-09-01", 3, 120_000, 12_000),
                rate("2026-09-02", 1, 150_000, 15_000),
                rate("2026-09-03", 5, 120_000, 12_000)));

        var result = ANormalizer.normalize(item, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());
        var offer = ((OfferNormalizationResult.Success) result).offer();

        assertThat(offer.price().nightlyBreakdown())
                .contains(List.of(
                        new NightlyPrice(LocalDate.parse("2026-09-01"), new Money(132_000, KRW), new Money(12_000, KRW)),
                        new NightlyPrice(LocalDate.parse("2026-09-02"), new Money(165_000, KRW), new Money(15_000, KRW)),
                        new NightlyPrice(
                                LocalDate.parse("2026-09-03"), new Money(132_000, KRW), new Money(12_000, KRW))));
    }

    @Test
    @DisplayName("공급사 날짜 순서가 뒤바뀌어도 분해는 오름차순이다")
    void 공급사_날짜_순서가_뒤바뀌어도_분해는_오름차순이다() {
        var shuffled = riverside(List.of(
                rate("2026-09-03", 5, 120_000, 12_000),
                rate("2026-09-01", 3, 120_000, 12_000),
                rate("2026-09-02", 1, 150_000, 15_000)));

        var result = ANormalizer.normalize(shuffled, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());
        var offer = ((OfferNormalizationResult.Success) result).offer();

        assertThat(offer.price().nightlyBreakdown())
                .get()
                .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.list(NightlyPrice.class))
                .extracting(NightlyPrice::date)
                .containsExactly(
                        LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-02"), LocalDate.parse("2026-09-03"));
        assertThat(offer.price().total()).isEqualTo(new Money(429_000, KRW));
    }

    @Test
    @DisplayName("하루라도 재고가 0이면 예약 가능 객실 수가 0이다")
    void 하루라도_재고가_0이면_예약_가능_객실_수가_0이다() {
        // Namsan Garden Stay 는 9/2 재고가 0이다. min 이 아니면 틀린 답이 나온다.
        var namsan = new AAvailabilityItem(
                "A-10044",
                "Namsan Garden Stay",
                "STD-DBL",
                "Standard Double",
                2,
                false,
                "KRW",
                List.of(
                        rate("2026-09-01", 2, 88_000, 8_800),
                        rate("2026-09-02", 0, 99_000, 9_900),
                        rate("2026-09-03", 4, 88_000, 8_800)));

        var result = ANormalizer.normalize(namsan, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());
        var offer = ((OfferNormalizationResult.Success) result).offer();

        assertThat(offer.availability().bookableRooms()).isZero();
        assertThat(offer.availability().bookable()).isFalse();
        // 재고가 0이어도 정규화 자체는 성공이다. 노출 여부는 API 계약의 문제다.
        assertThat(offer.price().total()).isEqualTo(new Money(302_500, KRW));
    }

    @Test
    @DisplayName("요청하지 않은 숙소는 매핑이 있어도 거부한다")
    void 요청하지_않은_숙소는_매핑이_있어도_거부한다() {
        // A-10044 는 카탈로그에 있지만 이 배치로 요청하지 않았다.
        var item = new AAvailabilityItem(
                "A-10044", "Namsan Garden Stay", "STD-DBL", "Standard Double", 2, false, "KRW",
                List.of(rate("2026-09-01", 2, 88_000, 8_800),
                        rate("2026-09-02", 3, 99_000, 9_900),
                        rate("2026-09-03", 4, 88_000, 8_800)));

        var result = ANormalizer.normalize(item, THREE_NIGHTS, Set.of("A-10023"), CatalogFixture.snapshot());

        assertThat(result)
                .isInstanceOf(OfferNormalizationResult.Rejected.class)
                .extracting(r -> ((OfferNormalizationResult.Rejected) r).reason())
                .isEqualTo(RejectReason.UNEXPECTED_PROPERTY);
    }

    @Test
    @DisplayName("identity 누락은 매핑 실패보다 먼저 판정된다")
    void identity_누락은_매핑_실패보다_먼저_판정된다() {
        // hotelCode 가 없는데 UNMAPPED_PROPERTY 로 기록되면 공급사 응답 문제가
        // 우리 카탈로그가 낡은 문제로 오진된다.
        var item = new AAvailabilityItem(
                null, "Riverside Hotel Seoul", "DLX-TWN", "Deluxe Twin", 2, false, "KRW",
                List.of(rate("2026-09-01", 3, 120_000, 12_000)));

        var result = ANormalizer.normalize(item, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result)
                .isInstanceOf(OfferNormalizationResult.Rejected.class)
                .extracting(r -> ((OfferNormalizationResult.Rejected) r).reason())
                .isEqualTo(RejectReason.MISSING_REQUIRED_FIELD);
    }

    @Test
    @DisplayName("live 이름이 카탈로그와 달라도 거부가 아니라 진단이다")
    void live_이름이_카탈로그와_달라도_거부가_아니라_진단이다() {
        var renamed = new AAvailabilityItem(
                "A-10023", "Riverside Hotel Seoul (Renovated)", "DLX-TWN", "Deluxe Twin", 2, false, "KRW",
                List.of(rate("2026-09-01", 3, 120_000, 12_000),
                        rate("2026-09-02", 1, 150_000, 15_000),
                        rate("2026-09-03", 5, 120_000, 12_000)));

        var result = ANormalizer.normalize(renamed, THREE_NIGHTS, REQUESTED, CatalogFixture.snapshot());

        assertThat(result).isInstanceOf(OfferNormalizationResult.Success.class);
        var success = (OfferNormalizationResult.Success) result;
        // 표시용 이름의 소스는 카탈로그다. live 값으로 덮지 않는다.
        assertThat(success.offer().propertyName()).isEqualTo("Riverside Hotel Seoul");
        assertThat(success.diagnostics())
                .extracting(Diagnostic::field, Diagnostic::actual)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("propertyName", "Riverside Hotel Seoul (Renovated)"));
    }
}
