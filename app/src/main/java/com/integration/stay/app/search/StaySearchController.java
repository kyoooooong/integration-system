package com.integration.stay.app.search;

import com.integration.stay.app.global.error.ErrorResponse;
import com.integration.stay.app.search.dto.request.StaySearchRequest;
import com.integration.stay.app.search.dto.response.SearchResponse;
import com.integration.stay.application.search.SearchStaysService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.ExampleObject;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import jakarta.validation.Valid;
import org.springdoc.core.annotations.ParameterObject;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * 통합 검색 API.
 *
 * <p>{@code Mono} 를 반환한다. 현재 긴 대기는 WebClient 가 처리하는 외부 I/O 이며,
 * 해당 구간은 이미 비동기로 처리된다. 전체 WebFlux 전환에 따른 운영·디버깅 모델 변경을
 * 현재 병목이 정당화하지 않는다고 판단했다.
 */
@RestController
@RequestMapping("/api/v1/stays")
class StaySearchController {

    private final SearchStaysService searchStaysService;

    StaySearchController(SearchStaysService searchStaysService) {
        this.searchStaysService = searchStaysService;
    }

    /**
     * 상태 코드를 문서에 적는 이유는 이것이 곧 클라이언트의 분기이기 때문이다.
     * 200 과 503 의 차이는 "결과가 없다" 와 "조회하지 못했다" 이고, 그 둘을 같게 다루면
     * 만실인 성수기에 클라이언트가 장애 처리를 한다.
     *
     * <p>{@code @ParameterObject} 를 붙이는 이유는 이것이 없으면 springdoc 이 네 파라미터를
     * 이름 없는 객체 하나로 묶어 문서화하기 때문이다. 그러면 문서만 보고는 무엇을 쿼리에
     * 실어야 하는지 알 수 없다.
     *
     * <p>5xx 의 본문이 {@code SearchResponse} 인 것은 실수가 아니다. 규칙은 하나다 —
     * <b>공급사별 상태를 말할 수 있으면 {@code SearchResponse}, 말할 수 없으면
     * {@code ErrorResponse}.</b> 공급사를 호출해 봤다면 어느 쪽이 왜 실패했는지 알고 있고,
     * 그 정보를 상태 코드가 5xx 라는 이유로 버리면 클라이언트는 재시도 대상을 고를 수 없다.
     */
    @Operation(
            summary = "여러 공급사의 숙박 상품을 통합 검색한다",
            description =
                    "쓸 만한 공급사가 하나라도 있으면 200 이다. 결과가 0건이어도 200 이며, "
                            + "이때 `partial` 과 `suppliers` 로 어느 공급사가 빠졌는지 알 수 있다. "
                            + "전부 실패했을 때만 5xx 로 갈린다.")
    @ApiResponses({
        @ApiResponse(
                responseCode = "200",
                description = "쓸 만한 공급사가 하나 이상. partial 로 부분 실패 여부를 판단한다"),
        @ApiResponse(
                responseCode = "400",
                description = "파라미터가 계약에 맞지 않음 (날짜 역전, 인원 범위, 형식)",
                content =
                        @Content(
                                schema = @Schema(implementation = ErrorResponse.class),
                                examples = {
                                    @ExampleObject(
                                            name = "날짜 역전",
                                            value =
                                                    """
                                                    {"code":"INVALID_DATE_RANGE",\
                                                    "message":"checkOut must be after checkIn: \
                                                    checkIn=2026-09-04, checkOut=2026-09-01",\
                                                    "errors":null}"""),
                                    @ExampleObject(
                                            name = "인원 범위",
                                            value =
                                                    """
                                                    {"code":"INVALID_PARAMETER",\
                                                    "message":"invalid request parameter",\
                                                    "errors":{"adults":"must be at least 1"}}""")
                                })),
        @ApiResponse(
                responseCode = "502",
                description = "모든 공급사가 계약을 위반한 응답을 줌. 어느 공급사가 왜 실패했는지는 suppliers 에 남는다",
                content = @Content(schema = @Schema(implementation = SearchResponse.class))),
        @ApiResponse(
                responseCode = "503",
                description =
                        "모든 공급사 조회가 실패했고 재시도 가능. 카탈로그 자체를 읽지 못해 호출조차 못 한 경우에는 "
                                + "공급사별 상태를 말할 수 없으므로 공통 오류 본문(DEPENDENCY_UNAVAILABLE)이 나간다",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                // type 을 명시하지 않으면 springdoc 이 "string" 을 붙여
                                                // oneOf 와 모순되는 스키마를 만든다.
                                                type = "object",
                                                oneOf = {SearchResponse.class, ErrorResponse.class},
                                                description = "공급사 조회를 시도했으면 SearchResponse, 시도조차 못 했으면 ErrorResponse"))),
        @ApiResponse(
                responseCode = "504",
                description = "모든 공급사가 제시간에 응답하지 않음",
                content = @Content(schema = @Schema(implementation = SearchResponse.class))),
        @ApiResponse(
                responseCode = "500",
                description = "검색 파이프라인 내부 실패 또는 예상하지 못한 애플리케이션 오류",
                content =
                        @Content(
                                schema =
                                        @Schema(
                                                type = "object",
                                                oneOf = {SearchResponse.class, ErrorResponse.class},
                                                description = "공급사 상태를 말할 수 있으면 SearchResponse, 그 외에는 ErrorResponse")))
    })
    @GetMapping("/search")
    Mono<ResponseEntity<SearchResponse>> search(
            @Valid @ParameterObject @ModelAttribute StaySearchRequest request) {
        return searchStaysService
                .search(request.toCommand())
                .map(result -> ResponseEntity.status(SearchHttpStatus.of(result))
                        .body(SearchResponseAssembler.assemble(result)));
    }
}
