package com.integration.stay.supplier.b;

/**
 * Supplier B 의 응답 봉투. <b>장애에도 HTTP 200 이다.</b>
 *
 * <p>{@code resultCode} 를 확인하지 않으면 장애를 정상 응답으로 처리하게 된다.
 * 성공 코드는 "0000" 이고, 실패 시 {@code data} 는 null 이다.
 */
public record BSearchResponse(String resultCode, String resultMessage, BSearchData data) {

    public static final String SUCCESS_CODE = "0000";

    public boolean succeeded() {
        return SUCCESS_CODE.equals(resultCode);
    }
}
