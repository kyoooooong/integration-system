package com.integration.stay.application.catalog;

/**
 * 카탈로그를 지금 읽을 수 없다.
 *
 * <p>검색은 어떤 숙소를 물어볼지 알아야 시작할 수 있으므로 이것은 <b>하드 프리컨디션</b>이다.
 * 공급사 실패처럼 값으로 표현해 부분 응답으로 넘길 수 없다 — 물어볼 대상 자체가 없다.
 *
 * <p><b>인프라 예외를 그대로 올려보내지 않고 여기서 한 번 번역하는 이유:</b>
 * {@code DataAccessResourceFailureException} 같은 Spring JDBC 타입이 app 계층까지
 * 올라가면, 컨트롤러가 저장소 기술을 알게 되어 포트 경계가 의미를 잃는다.
 * 어떤 저장소를 쓰든 "지금 읽을 수 없다" 는 같은 사실이다.
 *
 * <p>이것은 <b>일시적</b> 실패다. 커넥션 풀 고갈이나 DB 재시작이 원인이고 잠시 뒤 성공할 수
 * 있다. 그래서 500(재시도하지 마라)이 아니라 503으로 매핑된다.
 */
public class CatalogReadUnavailableException extends RuntimeException {

    public CatalogReadUnavailableException(String detail, Throwable cause) {
        super("catalog read unavailable: " + detail, cause);
    }
}
