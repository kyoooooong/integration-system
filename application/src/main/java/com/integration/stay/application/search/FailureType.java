package com.integration.stay.application.search;

/**
 * 내부 실패 분류. API 응답에 그대로 노출하지 않는다.
 *
 * <p>400/401 을 더 세분하지 않는다. 클라이언트 행동을 바꾸지 않으므로 구조화 로그에
 * 실제 상태 코드를 남기는 편이 낫다.
 */
public enum FailureType {
    /** 공급사가 제시간에 응답하지 않았다. */
    TIMEOUT,
    UNAVAILABLE,
    RATE_LIMITED,
    /** 응답은 왔는데 계약에 맞지 않는다. */
    INVALID_RESPONSE,
    /** 그 공급사를 호출할 매핑 자체가 없다. */
    CATALOG_UNAVAILABLE,
    /**
     * <b>우리 자원이 포화됐다.</b> 커넥션 풀 고갈, bulkhead 포화 등.
     *
     * <p>공급사 장애가 아니다. 이것을 TIMEOUT 으로 집계하면 우리가 스스로 유발한 장애를
     * 공급사 탓으로 돌리게 되고, 그러면 대응이 "공급사에 문의" 로 잘못 흘러간다.
     */
    LOCAL_SATURATION,
    /** 우리 코드의 버그다. */
    INTERNAL_ERROR;

    /**
     * 공급사 성공률 지표에서 제외해야 하는가.
     *
     * <p>공급사 성공률에 우리 문제가 섞이면 그 지표는 아무 의미가 없다.
     * 포화와 버그는 원인이 다르지만 <b>둘 다 우리 것</b>이므로 여기서는 함께 묶인다.
     */
    public boolean isOurFault() {
        return this == INTERNAL_ERROR || this == LOCAL_SATURATION;
    }

    /**
     * 같은 요청을 다시 보내도 같은 결과인가.
     *
     * <p>{@link #isOurFault()} 와 분리한 이유는 <b>HTTP 상태 코드의 의미가 다르기</b> 때문이다.
     * 500 은 "재시도하지 마라" 라는 뜻이므로 일시적 용량 문제를 500 으로 보고하면 안 된다.
     * 포화는 잠시 뒤 성공할 수 있으므로 재시도 가능한 503 이다.
     *
     * <pre>
     * INTERNAL_ERROR    코드가 틀렸다.   다시 보내도 같다.        500
     * LOCAL_SATURATION  자원이 부족했다. 잠시 뒤엔 될 수 있다.    503
     * </pre>
     */
    public boolean isDeterministicBug() {
        return this == INTERNAL_ERROR;
    }
}
