package com.integration.stay.application.search;

import com.integration.stay.domain.DomainInvariantViolation;
import com.integration.stay.domain.StayPeriod;

/**
 * 고객 검색 조건. 날짜와 인원뿐이다.
 *
 * <p>지역·키워드 필터는 없다. 공급사가 지역 정보를 주지 않으므로 조회 대상은 보유 숙소 전체다.
 */
public record SearchCommand(StayPeriod period, int adults, int children) {

    /**
     * 클라이언트 입력 검증은 컨트롤러의 {@code @Valid} 가 담당한다.
     *
     * <p>여기 검증은 <b>목적이 다르다</b> — 이 값이 잘못된 채로 도달했다면 그 검증이
     * 뚫린 것이므로 400 이 아니라 우리 버그다. 그래서 {@link DomainInvariantViolation} 이다.
     * 생성자는 불변식을 지키고, 컨트롤러 검증은 400 을 예쁘게 만든다.
     */
    public SearchCommand {
        if (adults < 1) {
            throw new DomainInvariantViolation("adults must be >= 1: " + adults);
        }
        if (children < 0) {
            throw new DomainInvariantViolation("children must be >= 0: " + children);
        }
    }
}
