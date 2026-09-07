package com.integration.stay.app.search;

import com.integration.stay.app.search.dto.response.SearchResponse;
import com.integration.stay.application.search.SearchResult;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.domain.offer.StayOffer;
import com.integration.stay.domain.pricing.NightlyPrice;

/** 도메인 타입은 직렬화 경계를 넘지 않는다. Optional 은 여기서 null 로 바뀐다. */
public final class SearchResponseAssembler {

    private SearchResponseAssembler() {}

    public static SearchResponse assemble(SearchResult result) {
        return new SearchResponse(
                result.partial(),
                result.outcomes().stream().map(SearchResponseAssembler::toSupplierView).toList(),
                // 재고 0 필터링은 여기서 한다. items 의 정의("요청 조건에서 예약 가능한 상품")는
                // API 계약이지 도메인 규칙이 아니다.
                result.bookableOffers().stream().map(SearchResponseAssembler::toItemView).toList());
    }

    private static SearchResponse.SupplierStatusView toSupplierView(SupplierOutcome outcome) {
        String reason = outcome.failure()
                .map(PublicFailureReason::from)
                .map(Enum::name)
                .orElse(null);
        return new SearchResponse.SupplierStatusView(
                outcome.supplierId().value(), outcome.status().name(), reason);
    }

    private static SearchResponse.StayItemView toItemView(StayOffer offer) {
        return new SearchResponse.StayItemView(
                offer.propertyId().toString(),
                offer.propertyName(),
                offer.roomTypeId().toString(),
                offer.roomTypeName(),
                offer.maxOccupancy(),
                offer.availability().bookableRooms(),
                offer.source().value(),
                toPriceView(offer),
                new SearchResponse.ConditionsView(offer.conditions().breakfastIncluded()));
    }

    private static SearchResponse.PriceView toPriceView(StayOffer offer) {
        var price = offer.price();
        return new SearchResponse.PriceView(
                price.total().currency().getCurrencyCode(),
                price.total().amount(),
                price.taxAmount().map(m -> m.amount()).orElse(null),
                price.nightlyBreakdown()
                        .map(list -> list.stream().map(SearchResponseAssembler::toNightlyView).toList())
                        .orElse(null));
    }

    private static SearchResponse.NightlyPriceView toNightlyView(NightlyPrice nightly) {
        return new SearchResponse.NightlyPriceView(
                nightly.date().toString(), nightly.grossAmount().amount(), nightly.taxAmount().amount());
    }
}
