package com.integration.stay.app.search.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 통합 검색 응답.
 *
 * <p>기준: <b>클라이언트의 행동을 바꾸는 정보만 응답에 넣고, 원인을 설명하는 정보는
 * 텔레메트리로 보낸다.</b> 배치 수, 정규화 거부 건수, 만실 제외 건수는 클라이언트가
 * 무엇을 다르게 하지 않으므로 지표로만 남긴다.
 *
 * <p>정렬하지 않는다. 정렬 기준이 요구에 없으므로 임의의 업무 의미를 부여하지 않았다.
 * 결정성이 필요한 것은 테스트이지 제품 API 가 아니다.
 *
 * <p>예시 값을 붙인 이유는 springdoc 이 기본으로 {@code int} 에 {@code 1073741824},
 * {@code long} 에 {@code 9007199254740991} 을 넣기 때문이다. 그 문서를 본 사람은
 * 금액의 단위도 재고의 크기도 짐작할 수 없다.
 */
public record SearchResponse(boolean partial, List<SupplierStatusView> suppliers, List<StayItemView> items) {

    public record SupplierStatusView(
            @Schema(description = "공급사 식별자", example = "A") String supplier,
            @Schema(
                            description = "SUCCESS | PARTIAL | FAILED | NO_TARGETS | CATALOG_UNAVAILABLE",
                            example = "PARTIAL")
                    String status,
            @Schema(description = "실패했을 때의 공개 사유. 성공이면 null", example = "TIMEOUT") String reason) {}

    public record StayItemView(
            @Schema(description = "우리가 발급한 내부 숙소 식별자. 동일한 공급사 상품이 재동기화되어도 유지된다",
                            example = "d11a17fd-60be-4814-b63f-0ce08ce87548")
                    String propertyId,
            @Schema(example = "Riverside Hotel Seoul") String propertyName,
            @Schema(example = "603cb3f2-8ecd-4202-83c3-2f91532fb658") String roomTypeId,
            @Schema(example = "Deluxe Twin Room") String roomTypeName,
            @Schema(description = "객실 최대 수용 인원", example = "2") int maxOccupancy,
            @Schema(description = "전 숙박일에 예약 가능한 객실 수(일별 재고의 최솟값). 0 은 응답에 포함하지 않는다",
                            example = "3")
                    int availableRooms,
            @Schema(example = "A") String supplier,
            PriceView price,
            ConditionsView conditions) {}

    /**
     * {@code taxAmount} 와 {@code nightlyBreakdown} 은 모를 때 {@code null} 로 나간다.
     * {@code @JsonInclude(NON_NULL)} 로 필드를 빼지 않는다 — "모른다" 가 "필드 없음" 으로
     * 흐려지면 클라이언트가 0으로 오해할 여지가 생긴다.
     */
    public record PriceView(
            @Schema(description = "ISO 4217", example = "KRW") String currency,
            @Schema(description = "전 숙박일 세금 포함 총액. 최소 화폐 단위의 정수", example = "429000") long totalAmount,
            @Schema(description = "총 세액. 공급사가 주지 않으면 null 이며 0 과 구분된다", example = "39000")
                    Long taxAmount,
            @Schema(description = "일별 분해. 공급사가 일별 요금을 주지 않으면 null 이다") List<NightlyPriceView> nightlyBreakdown) {}

    public record NightlyPriceView(
            @Schema(example = "2026-09-01") String date,
            @Schema(description = "그날의 세금 포함 요금", example = "143000") long grossAmount,
            @Schema(description = "그날의 세액", example = "13000") long taxAmount) {}

    public record ConditionsView(@Schema(example = "true") boolean breakfastIncluded) {}
}
