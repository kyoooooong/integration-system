package com.integration.stay.app.global.error;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

/**
 * 오류 응답 본문. 모든 오류가 같은 모양이어야 클라이언트가 분기할 수 있다.
 *
 * @param errors 필드별 오류. 어떤 파라미터가 왜 틀렸는지를 <b>구조로</b> 전달한다.
 *     메시지를 이어 붙인 문자열은 사람은 읽을 수 있어도 클라이언트가 파싱할 수 없고,
 *     "어느 입력 칸에 오류를 표시할지" 를 정할 수 없다. 해당 없으면 {@code null}
 */
public record ErrorResponse(
        @Schema(description = "기계가 분기할 값", example = "INVALID_PARAMETER") String code,
        @Schema(description = "사람이 읽을 설명", example = "invalid request parameter") String message,
        @Schema(
                        description = "필드별 오류. 해당 없으면 null",
                        example = "{\"checkOut\": \"must be after checkIn\"}")
                Map<String, String> errors) {

    public ErrorResponse {
        errors = errors == null ? null : Map.copyOf(errors);
    }

    static ErrorResponse of(ErrorCode code) {
        return new ErrorResponse(code.name(), code.defaultMessage(), null);
    }

    /**
     * 클라이언트가 고칠 수 있는 오류에 한해 상세를 덧붙인다.
     *
     * <p>어떤 파라미터가 왜 틀렸는지는 알려줘야 고칠 수 있다. 반대로 내부 오류의 상세는
     * 알려줘도 클라이언트가 할 수 있는 일이 없고 내부 구조만 샌다.
     */
    static ErrorResponse of(ErrorCode code, String detail) {
        return new ErrorResponse(code.name(), detail, null);
    }

    static ErrorResponse of(ErrorCode code, Map<String, String> errors) {
        return new ErrorResponse(code.name(), code.defaultMessage(), errors);
    }
}
