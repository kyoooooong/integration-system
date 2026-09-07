package com.integration.stay.supplier.a;

import com.integration.stay.application.catalog.CatalogResolution;
import com.integration.stay.application.catalog.CatalogRoomTypeRef;
import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.domain.inventory.Availability;
import com.integration.stay.domain.inventory.InventoryEvaluation;
import com.integration.stay.domain.inventory.InventoryRule;
import com.integration.stay.domain.offer.OfferConditions;
import com.integration.stay.domain.offer.StayOffer;
import com.integration.stay.domain.pricing.Money;
import com.integration.stay.domain.pricing.MoneyOverflowException;
import com.integration.stay.domain.pricing.NightlyPrice;
import com.integration.stay.domain.pricing.StayPrice;
import com.integration.stay.supplier.SupplierIds;
import com.integration.stay.supplier.normalize.Diagnostic;
import com.integration.stay.supplier.normalize.ExternalOfferKey;
import com.integration.stay.supplier.normalize.Normalizations;
import com.integration.stay.supplier.normalize.OfferNormalizationResult;
import com.integration.stay.supplier.normalize.RejectReason;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Supplier A 응답을 표준 모델로 변환한다. <b>순수 함수다.</b> 지표를 올리지 않고 진단을
 * 결과값으로 돌려준다.
 *
 * <p>A 는 날짜별 1박 단가를 세금 별도(net)로 준다. 우리 표준은 총액 gross 이므로
 * 각 날짜의 {@code (nightlyRate + taxAmount)} 를 합산한다. 이 방향은 계약이 보장하는
 * 정확한 값이라 <b>무손실</b>이다. 세액과 일별 분해도 그대로 보존한다.
 */
public final class ANormalizer {

    private ANormalizer() {}

    public static OfferNormalizationResult normalize(
            AAvailabilityItem item, SearchCommand command, Set<String> requestedCodes, CatalogSnapshot snapshot) {

        // ① identity 필드. 매핑 해석보다 먼저 본다.
        //    resolve(null, ...) 를 먼저 하면 공급사 응답 문제가 UNMAPPED_PROPERTY 로 기록되어
        //    우리 카탈로그가 낡은 문제로 오진된다.
        if (Normalizations.isBlank(item.hotelCode())) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "hotelCode");
        }
        if (Normalizations.isBlank(item.roomTypeCode())) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "roomTypeCode");
        }

        // ② 요청 배치 소속. 계약은 response ⊆ requested 이지 == 이 아니다.
        //    요청했는데 응답에 없는 것은 정상(재고 없음/인원 미달)이므로 여기서 보지 않는다.
        if (!requestedCodes.contains(item.hotelCode())) {
            return reject(RejectReason.UNEXPECTED_PROPERTY, item, "hotelCode=" + item.hotelCode());
        }

        // ③ 매핑 해석
        CatalogResolution resolution = snapshot.resolve(SupplierIds.A, item.hotelCode(), item.roomTypeCode());
        CatalogRoomTypeRef ref =
                switch (resolution) {
                    case CatalogResolution.Resolved r -> r.ref();
                    case CatalogResolution.UnknownProperty ignored -> null;
                    case CatalogResolution.UnknownRoomType ignored -> null;
                };
        if (ref == null) {
            RejectReason reason = resolution instanceof CatalogResolution.UnknownProperty
                    ? RejectReason.UNMAPPED_PROPERTY
                    : RejectReason.UNMAPPED_ROOM_TYPE;
            return reject(reason, item, "no catalog mapping");
        }

        // ④ 동적 필수 필드. 판매 조건의 유일한 소스는 live 다. null 이면 카탈로그로 폴백하지 않고 거부한다.
        if (item.maxOccupancy() == null) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "maxOccupancy");
        }
        if (item.maxOccupancy() <= 0) {
            return reject(RejectReason.CONTRACT_ASSUMPTION_BROKEN, item, "maxOccupancy=" + item.maxOccupancy());
        }
        if (item.breakfastIncluded() == null) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "breakfastIncluded");
        }
        if (Normalizations.isBlank(item.currency())) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "currency");
        }
        Optional<Currency> currency = Normalizations.parseCurrency(item.currency());
        if (currency.isEmpty()) {
            return reject(RejectReason.UNKNOWN_CURRENCY, item, "currency=" + item.currency());
        }
        if (item.dailyRates() == null) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "dailyRates");
        }

        // ⑤ 날짜 행. 연속성·누락·중복·기간 밖·음수를 도메인 규칙이 한 번에 판정한다.
        InventoryEvaluation evaluation = InventoryRule.evaluate(command.period(), item.dailyRates());
        if (evaluation instanceof InventoryEvaluation.Invalid invalid) {
            return reject(Normalizations.toRejectReason(invalid.reason()), item, invalid.detail());
        }
        Availability availability = ((InventoryEvaluation.Valid) evaluation).availability();

        // ⑥ 금액. InventoryRule 이 이미 날짜 연속성을 검증했으므로 여기서 재검사하지 않는다.
        //    중복 검증은 한쪽만 고쳐지면 모순된다.
        //    정렬된 순서로 순회한다. 응답 배열 순서에 의존하면 분해 결과가 비결정적이 된다.
        List<ADailyRate> sorted = item.dailyRates().stream()
                .sorted(Comparator.comparing(ADailyRate::date))
                .toList();

        Money total = Money.zero(currency.get());
        Money tax = Money.zero(currency.get());
        List<NightlyPrice> breakdown = new ArrayList<>(sorted.size());
        for (ADailyRate rate : sorted) {
            if (rate.nightlyRate() == null) {
                return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "nightlyRate@" + rate.date());
            }
            if (rate.taxAmount() == null) {
                return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "taxAmount@" + rate.date());
            }
            if (rate.nightlyRate() < 0 || rate.taxAmount() < 0) {
                return reject(
                        RejectReason.INVALID_AMOUNT,
                        item,
                        "negative amount@%s nightlyRate=%d taxAmount=%d"
                                .formatted(rate.date(), rate.nightlyRate(), rate.taxAmount()));
            }
            try {
                Money nightlyNet = new Money(rate.nightlyRate(), currency.get());
                Money nightlyTax = new Money(rate.taxAmount(), currency.get());
                Money nightlyGross = nightlyNet.plus(nightlyTax);

                total = total.plus(nightlyGross);
                tax = tax.plus(nightlyTax);
                breakdown.add(new NightlyPrice(rate.date(), nightlyGross, nightlyTax));
            } catch (MoneyOverflowException e) {
                return reject(RejectReason.INVALID_AMOUNT, item, "overflow@" + rate.date());
            }
        }

        // A 는 일별 정보를 실제로 가지고 있다. 창작이 아니라 보존이므로 노출한다.
        StayPrice price = StayPrice.itemized(total, tax, breakdown);

        // ⑦⑧ 표시용 이름은 카탈로그가 소스다. live 와 다르면 거부가 아니라 진단으로 남긴다.
        List<Diagnostic> diagnostics = Normalizations.driftDiagnostics(ref, item.hotelName(), item.roomTypeName());

        StayOffer offer = new StayOffer(
                ref.propertyId(),
                ref.propertyName(),
                ref.roomTypeId(),
                ref.roomTypeName(),
                item.maxOccupancy(),
                SupplierIds.A,
                availability,
                price,
                new OfferConditions(item.breakfastIncluded()));

        return new OfferNormalizationResult.Success(offer, diagnostics);
    }

    private static OfferNormalizationResult reject(RejectReason reason, AAvailabilityItem item, String detail) {
        return new OfferNormalizationResult.Rejected(
                reason, new ExternalOfferKey(SupplierIds.A, item.hotelCode(), item.roomTypeCode()), detail);
    }
}
