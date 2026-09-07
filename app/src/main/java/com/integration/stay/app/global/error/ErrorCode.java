package com.integration.stay.app.global.error;

import org.springframework.http.HttpStatus;

/**
 * 클라이언트에게 노출하는 오류 코드.
 *
 * <p>목록이 짧다. 코드가 늘어나야 할 이유는 <b>클라이언트가 다르게 행동해야 할 때</b>뿐이다.
 * 내부 실패 원인을 세분해 코드로 내보내면 계약만 넓어지고 클라이언트가 할 일은 달라지지 않는다.
 *
 * <p>검색 결과 자체의 실패(공급사 장애·타임아웃)는 여기 없다. 그것은 오류 응답이 아니라
 * <b>정상 응답 본문의 {@code suppliers} 필드</b>로 나간다 — 상태 코드가 503 이어도
 * 어느 공급사가 왜 실패했는지가 함께 전달되어야 클라이언트가 판단할 수 있다.
 */
public enum ErrorCode {
    /** 체크아웃이 체크인보다 뒤가 아니다. */
    INVALID_DATE_RANGE(HttpStatus.BAD_REQUEST, "checkOut must be after checkIn"),
    /** 그 밖의 요청 파라미터 오류. 어떤 필드가 왜 틀렸는지는 {@code errors} 에 담는다. */
    INVALID_PARAMETER(HttpStatus.BAD_REQUEST, "invalid request parameter"),
    /** 존재하지 않는 경로. */
    NOT_FOUND(HttpStatus.NOT_FOUND, "no handler for this path"),
    /** 지원하지 않는 HTTP 메서드. */
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "http method not supported for this path"),
    /**
     * 의존성에 지금 닿을 수 없다.
     *
     * <p>DB 커넥션 풀 고갈이나 DB 자체의 장애가 여기 해당한다.
     * <b>500 이 아니라 503 이다.</b> 500 은 "재시도하지 마라" 라는 뜻인데, 이것은
     * 잠시 뒤 성공할 수 있는 일시적 용량 문제다. 공급사 실패를 LOCAL_SATURATION 과
     * INTERNAL_ERROR 로 나눈 것과 같은 기준이다.
     */
    DEPENDENCY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "a dependency is temporarily unavailable"),
    /**
     * 예상하지 못한 내부 오류. <b>우리 불변식 위반도 여기로 온다.</b>
     *
     * <p>메시지를 고정한다. 예외 메시지를 그대로 내려보내면 내부 구조·SQL·경로가 새고,
     * 클라이언트는 그 문자열로 할 수 있는 일이 없다. 상세는 로그에 남긴다.
     */
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "unexpected internal error");

    private final HttpStatus status;
    private final String defaultMessage;

    ErrorCode(HttpStatus status, String defaultMessage) {
        this.status = status;
        this.defaultMessage = defaultMessage;
    }

    public HttpStatus status() {
        return status;
    }

    public String defaultMessage() {
        return defaultMessage;
    }
}
