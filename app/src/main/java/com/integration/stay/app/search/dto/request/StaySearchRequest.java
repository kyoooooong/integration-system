package com.integration.stay.app.search.dto.request;

import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.domain.StayPeriod;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;

/**
 * 검색 요청 파라미터.
 *
 * <p>도메인 생성자 검증과 이 검증을 둘 다 둔다. 목적이 다르다 —
 * <b>생성자는 불변식을 지키고, 여기는 400 을 예쁘게 만든다.</b>
 *
 * <p>현재 계약에 최대 숙박일이 정의되어 있지 않아 30박·90박 같은 임의 제한은 두지 않는다.
 * 기간 계산은 날짜 컬렉션을 만들지 않지만, Supplier 응답 크기와 일별 재고 검증 비용은
 * 숙박일 수에 따라 늘 수 있다. 실제 최대 조회 기간이나 서비스 정책이 생기면 입력 단계에서 제한한다.
 *
 * <p>예시 값을 명시하는 이유는 springdoc 이 붙이는 기본 예시가 계약을 위반하기 때문이다.
 * 명시하지 않으면 정수에 {@code 1073741824}, 두 날짜에 같은 값을 넣은 문서가 나오는데,
 * 그대로 실행하면 우리가 400 으로 거부하는 요청이다. <b>문서의 예시가 실행되지 않으면
 * 그 문서는 읽는 사람을 한 번 속인다.</b>
 */
public record StaySearchRequest(
        @NotNull(message = "required")
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                @Schema(description = "체크인 날짜 (숙박 첫날)", example = "2026-09-01", requiredMode = Schema.RequiredMode.REQUIRED)
                LocalDate checkIn,
        @NotNull(message = "required")
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                @Schema(
                        description = "체크아웃 날짜. 숙박일에 포함되지 않으므로 checkIn 보다 뒤여야 한다",
                        example = "2026-09-04",
                        requiredMode = Schema.RequiredMode.REQUIRED)
                LocalDate checkOut,
        @Min(value = 1, message = "must be at least 1")
                @Schema(description = "성인 인원. 1 이상", example = "2", minimum = "1")
                int adults,
        @Min(value = 0, message = "must be at least 0")
                @Schema(description = "아동 인원. 0 이상", example = "0", minimum = "0")
                int children) {

    public SearchCommand toCommand() {
        return new SearchCommand(new StayPeriod(checkIn, checkOut), adults, children);
    }
}
