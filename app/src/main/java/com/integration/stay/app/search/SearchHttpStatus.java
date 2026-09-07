package com.integration.stay.app.search;

import com.integration.stay.application.search.FailureType;
import com.integration.stay.application.search.SearchResult;
import org.springframework.http.HttpStatus;

/**
 * 검색 결과를 HTTP 상태 코드로 옮긴다.
 *
 * <p>순서가 곧 정책이다. <b>쓸 만한 공급사가 하나라도 있으면 200 이다.</b> 이 판정을
 * offers.isEmpty() 로 하면 전 숙소가 만실인 성수기에 503 이 나간다.
 *
 * <p>502/504 는 동질 실패일 때만 쓴다. 혼합 실패에서 어느 하나를 우선할 근거가 없다.
 *
 * <p>502 의 근거는 재시도 가능성이 아니라 error ownership 이다.
 * 500 은 우리 내부가 정상 응답을 만들지 못한 것, 502 는 upstream 이 invalid response 를
 * 준 것, 503 은 의존성들이 지금 사용 불가한 것, 504 는 제시간에 응답을 못 받은 것이다.
 */
public final class SearchHttpStatus {

    private SearchHttpStatus() {}

    public static HttpStatus of(SearchResult result) {
        if (result.hasUsableSupplier()) {
            return HttpStatus.OK;
        }
        // 우리 버그가 다른 실패에 가려지면 조사되지 않는다.
        // 다만 "우리 잘못" 전부가 500 은 아니다. 500 은 "재시도하지 마라" 라는 뜻이므로
        // 일시적 용량 문제(LOCAL_SATURATION)를 500 으로 보고하면 클라이언트가 잘못 판단한다.
        if (result.hasDeterministicBug()) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return result.homogeneousFailure()
                .map(SearchHttpStatus::homogeneous)
                .orElse(HttpStatus.SERVICE_UNAVAILABLE);
    }

    private static HttpStatus homogeneous(FailureType type) {
        return switch (type) {
            case TIMEOUT -> HttpStatus.GATEWAY_TIMEOUT;
            case INVALID_RESPONSE -> HttpStatus.BAD_GATEWAY;
            // 포화는 재시도 가능한 일시적 용량 문제다.
            case UNAVAILABLE, RATE_LIMITED, CATALOG_UNAVAILABLE, LOCAL_SATURATION ->
                    HttpStatus.SERVICE_UNAVAILABLE;
            // hasDeterministicBug 가 먼저 걸러내므로 여기 도달하지 않는다.
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
