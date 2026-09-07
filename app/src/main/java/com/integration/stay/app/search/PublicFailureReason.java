package com.integration.stay.app.search;

import com.integration.stay.application.search.FailureType;

/**
 * 외부에 노출하는 실패 사유. 내부 {@link FailureType} 7종을 4종으로 접는다.
 *
 * <p>내부 분류를 그대로 내보내지 않는 이유는 클라이언트의 행동을 바꾸지 않기 때문이고,
 * LOCAL_SATURATION 이나 INTERNAL_ERROR 처럼 우리 내부 사정을 그대로 알릴 이유도 없다.
 */
public enum PublicFailureReason {
    TIMEOUT,
    UNAVAILABLE,
    INVALID_RESPONSE,
    CATALOG_UNAVAILABLE;

    public static PublicFailureReason from(FailureType type) {
        return switch (type) {
            case TIMEOUT -> TIMEOUT;
            case INVALID_RESPONSE -> INVALID_RESPONSE;
            case CATALOG_UNAVAILABLE -> CATALOG_UNAVAILABLE;
            // 우리 자원 포화와 우리 버그는 클라이언트에게 "지금 쓸 수 없다" 와 같은 의미다.
            case UNAVAILABLE, RATE_LIMITED, LOCAL_SATURATION, INTERNAL_ERROR -> UNAVAILABLE;
        };
    }
}
