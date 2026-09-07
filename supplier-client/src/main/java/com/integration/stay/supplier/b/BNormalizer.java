package com.integration.stay.supplier.b;

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
import com.integration.stay.domain.pricing.StayPrice;
import com.integration.stay.supplier.SupplierIds;
import com.integration.stay.supplier.normalize.Diagnostic;
import com.integration.stay.supplier.normalize.ExternalOfferKey;
import com.integration.stay.supplier.normalize.Normalizations;
import com.integration.stay.supplier.normalize.OfferNormalizationResult;
import com.integration.stay.supplier.normalize.RejectReason;
import java.util.Currency;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Supplier B 응답을 표준 모델로 변환한다.
 *
 * <p>B 는 기간 전체 총액만 세금 포함(gross)으로 준다. 우리 표준이 이미 gross 총액이므로
 * 금액은 그대로 쓴다. <b>없는 정보를 만들어내지 않는다.</b>
 *
 * <ul>
 *   <li>{@code taxAmount} 는 {@code Optional.empty()} 다. 0원이 아니라 <b>모른다</b>는 뜻이다
 *   <li>{@code nightlyBreakdown} 은 {@code Optional.empty()} 다. 총액을 박수로 나누지 않는다.
 *       452,000 / 3 = 150,666 은 어느 날에도 실재하지 않는 금액이며, 검색 금액과 예약 금액이
 *       달라지는 사고로 이어진다
 * </ul>
 *
 * <p>잃는 것을 감추지 않는다. B 상품은 일별 요금 비교와 부분 취소 금액 산정이 불가능하고
 * 세액을 분리할 수 없다. 그것을 empty 로 <b>명시</b>한다.
 */
public final class BNormalizer {

    private BNormalizer() {}

    public static OfferNormalizationResult normalize(
            BSearchItem item, SearchCommand command, Set<String> requestedIds, CatalogSnapshot snapshot) {

        // ① identity
        if (Normalizations.isBlank(item.propertyId())) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "propertyId");
        }
        if (Normalizations.isBlank(item.roomId())) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "roomId");
        }

        // ② 요청 배치 소속
        if (!requestedIds.contains(item.propertyId())) {
            return reject(RejectReason.UNEXPECTED_PROPERTY, item, "propertyId=" + item.propertyId());
        }

        // ③ 매핑 해석
        CatalogResolution resolution = snapshot.resolve(SupplierIds.B, item.propertyId(), item.roomId());
        if (!(resolution instanceof CatalogResolution.Resolved resolved)) {
            RejectReason reason = resolution instanceof CatalogResolution.UnknownProperty
                    ? RejectReason.UNMAPPED_PROPERTY
                    : RejectReason.UNMAPPED_ROOM_TYPE;
            return reject(reason, item, "no catalog mapping");
        }
        CatalogRoomTypeRef ref = resolved.ref();

        // ④ 동적 필수 필드
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
        if (item.totalPrice() == null) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "totalPrice");
        }
        if (item.inventory() == null) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "inventory");
        }

        // B 전용. taxIncluded 는 계약상 항상 true 다.
        if (item.taxIncluded() == null) {
            return reject(RejectReason.MISSING_REQUIRED_FIELD, item, "taxIncluded");
        }
        if (Boolean.FALSE.equals(item.taxIncluded())) {
            // 세금을 추측해 더하지 않는다. 이 값을 gross 로 취급하면 실제 고객 결제 금액보다
            // 작게 노출된다. 데이터가 잘못된 게 아니라 우리 gross 표준 가정이 깨진 것이므로
            // 지표에 뜨면 공급사 문의가 아니라 코드 수정이 필요하다.
            return reject(RejectReason.CONTRACT_ASSUMPTION_BROKEN, item, "taxIncluded=false");
        }

        // ⑤ 날짜 행
        InventoryEvaluation evaluation = InventoryRule.evaluate(command.period(), item.inventory());
        if (evaluation instanceof InventoryEvaluation.Invalid invalid) {
            return reject(Normalizations.toRejectReason(invalid.reason()), item, invalid.detail());
        }
        Availability availability = ((InventoryEvaluation.Valid) evaluation).availability();

        // ⑥ 금액
        if (item.totalPrice() < 0) {
            return reject(RejectReason.INVALID_AMOUNT, item, "totalPrice=" + item.totalPrice());
        }
        StayPrice price = StayPrice.totalOnly(new Money(item.totalPrice(), currency.get()));

        // ⑦⑧
        List<Diagnostic> diagnostics = Normalizations.driftDiagnostics(ref, item.propertyName(), item.roomName());

        StayOffer offer = new StayOffer(
                ref.propertyId(),
                ref.propertyName(),
                ref.roomTypeId(),
                ref.roomTypeName(),
                item.maxOccupancy(),
                SupplierIds.B,
                availability,
                price,
                new OfferConditions(item.breakfastIncluded()));

        return new OfferNormalizationResult.Success(offer, diagnostics);
    }

    private static OfferNormalizationResult reject(RejectReason reason, BSearchItem item, String detail) {
        return new OfferNormalizationResult.Rejected(
                reason, new ExternalOfferKey(SupplierIds.B, item.propertyId(), item.roomId()), detail);
    }
}
