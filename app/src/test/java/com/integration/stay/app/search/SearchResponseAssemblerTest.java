package com.integration.stay.app.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.app.search.dto.response.SearchResponse;
import com.integration.stay.application.search.FailureType;
import com.integration.stay.application.search.SearchResult;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.domain.SupplierId;
import com.integration.stay.domain.inventory.Availability;
import com.integration.stay.domain.offer.OfferConditions;
import com.integration.stay.domain.offer.StayOffer;
import com.integration.stay.domain.pricing.Money;
import com.integration.stay.domain.pricing.NightlyPrice;
import com.integration.stay.domain.pricing.StayPrice;
import java.time.LocalDate;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 도메인 타입이 직렬화 경계를 넘지 않는 지점.
 *
 * <p>여기서 {@code Optional} 이 {@code null} 로 바뀌고, 재고 0 이 걸러지고,
 * 내부 실패 사유가 공개 사유로 접힌다. 세 변환 모두 <b>정보를 잃는 방향</b>이므로
 * 무엇을 잃고 무엇을 남기는지가 검증 대상이다.
 */
class SearchResponseAssemblerTest {

    private static final Currency KRW = Currency.getInstance("KRW");
    private static final SupplierId A = new SupplierId("A");
    private static final SupplierId B = new SupplierId("B");

    private static StayOffer offer(SupplierId source, int rooms, StayPrice price, boolean breakfast) {
        return new StayOffer(
                UUID.randomUUID(),
                "Riverside Hotel Seoul",
                UUID.randomUUID(),
                "Deluxe Twin",
                2,
                source,
                new Availability(rooms),
                price,
                new OfferConditions(breakfast));
    }

    private static StayPrice itemized() {
        return StayPrice.itemized(
                new Money(429_000, KRW),
                new Money(39_000, KRW),
                List.of(new NightlyPrice(LocalDate.parse("2026-09-01"), new Money(132_000, KRW), new Money(12_000, KRW))));
    }

    @Test
    @DisplayName("모르는 세액은 null 로 내려가고 필드는 남는다")
    void 모르는_세액은_null로_내려가고_필드는_남는다() {
        // @JsonInclude(NON_NULL) 로 필드를 빼지 않는다.
        // "모른다" 가 "필드 없음" 으로 흐려지면 클라이언트가 0으로 오해할 여지가 생긴다.
        var result = new SearchResult(List.of(
                SupplierOutcome.success(B, List.of(offer(B, 1, StayPrice.totalOnly(new Money(452_000, KRW)), true)))));

        SearchResponse response = SearchResponseAssembler.assemble(result);

        SearchResponse.PriceView price = response.items().getFirst().price();
        assertThat(price.totalAmount()).isEqualTo(452_000);
        assertThat(price.taxAmount()).isNull();
        assertThat(price.nightlyBreakdown()).isNull();
    }

    @Test
    @DisplayName("아는 세액과 분해는 보존된다")
    void 아는_세액과_분해는_보존된다() {
        var result = new SearchResult(List.of(SupplierOutcome.success(A, List.of(offer(A, 1, itemized(), false)))));

        SearchResponse.PriceView price =
                SearchResponseAssembler.assemble(result).items().getFirst().price();

        assertThat(price.taxAmount()).isEqualTo(39_000);
        assertThat(price.nightlyBreakdown()).hasSize(1);
        assertThat(price.nightlyBreakdown().getFirst().grossAmount()).isEqualTo(132_000);
        assertThat(price.nightlyBreakdown().getFirst().date()).isEqualTo("2026-09-01");
    }

    @Test
    @DisplayName("재고 0 상품은 items 에서 빠지지만 공급사 상태는 남는다")
    void 재고_0_상품은_items에서_빠지지만_공급사_상태는_남는다() {
        // items 의 정의는 "요청 조건에서 예약 가능한 상품" 이다. API 계약이지 도메인 규칙이 아니다.
        var result = new SearchResult(List.of(SupplierOutcome.success(
                A,
                List.of(
                        offer(A, 0, itemized(), false),
                        offer(A, 2, itemized(), false)))));

        SearchResponse response = SearchResponseAssembler.assemble(result);

        assertThat(response.items()).hasSize(1);
        assertThat(response.items().getFirst().availableRooms()).isEqualTo(2);
        // 전부 만실이어도 공급사는 SUCCESS 다. 결과 없음과 조회 실패는 다르다.
        assertThat(response.suppliers()).hasSize(1);
        assertThat(response.suppliers().getFirst().status()).isEqualTo("SUCCESS");
        assertThat(response.partial()).isFalse();
    }

    @Test
    @DisplayName("내부 실패 사유는 공개 사유로 접힌다")
    void 내부_실패_사유는_공개_사유로_접힌다() {
        // 우리 자원 포화나 우리 버그를 그대로 알릴 이유가 없다. 클라이언트 행동이 같다.
        var result = new SearchResult(List.of(
                SupplierOutcome.failed(A, FailureType.LOCAL_SATURATION),
                SupplierOutcome.failed(B, FailureType.RATE_LIMITED)));

        SearchResponse response = SearchResponseAssembler.assemble(result);

        assertThat(response.suppliers())
                .extracting(SearchResponse.SupplierStatusView::supplier, SearchResponse.SupplierStatusView::reason)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("A", "UNAVAILABLE"),
                        org.assertj.core.groups.Tuple.tuple("B", "UNAVAILABLE"));
    }

    @Test
    @DisplayName("타임아웃과 계약 위반은 공개 사유에서도 구분된다")
    void 타임아웃과_계약_위반은_공개_사유에서도_구분된다() {
        // 반대 방향 확인. 전부 UNAVAILABLE 로 접으면 위 테스트도 통과한다.
        var result = new SearchResult(List.of(
                SupplierOutcome.failed(A, FailureType.TIMEOUT),
                SupplierOutcome.failed(B, FailureType.INVALID_RESPONSE)));

        assertThat(SearchResponseAssembler.assemble(result).suppliers())
                .extracting(SearchResponse.SupplierStatusView::reason)
                .containsExactlyInAnyOrder("TIMEOUT", "INVALID_RESPONSE");
    }

    @Test
    @DisplayName("성공한 공급사의 사유는 null 이다")
    void 성공한_공급사의_사유는_null이다() {
        var result = new SearchResult(List.of(SupplierOutcome.success(A, List.of())));

        assertThat(SearchResponseAssembler.assemble(result).suppliers().getFirst().reason())
                .isNull();
    }

    @Test
    @DisplayName("서로 다른 공급사의 같은 숙소는 각자의 식별자로 나온다")
    void 서로_다른_공급사의_같은_숙소는_각자의_식별자로_나온다() {
        // 중복 병합을 하지 않는다는 결정이 응답에서 실제로 보이는 지점이다.
        var a = offer(A, 1, itemized(), false);
        var b = offer(B, 1, StayPrice.totalOnly(new Money(452_000, KRW)), true);
        var result = new SearchResult(
                List.of(SupplierOutcome.success(A, List.of(a)), SupplierOutcome.success(B, List.of(b))));

        SearchResponse response = SearchResponseAssembler.assemble(result);

        assertThat(response.items()).hasSize(2);
        assertThat(response.items())
                .extracting(SearchResponse.StayItemView::propertyId)
                .doesNotHaveDuplicates();
        assertThat(response.items())
                .extracting(i -> i.conditions().breakfastIncluded())
                .containsExactlyInAnyOrder(true, false);
    }
}
