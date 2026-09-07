package com.integration.stay.application.search;

import com.integration.stay.domain.SupplierId;
import java.time.Duration;

/**
 * 검색 경로의 관측 지점.
 *
 * <p>application 계층에 로거도 미터도 두지 않기 위해 포트로 뽑았다. 구현은 Composition
 * Root 에 하나 있다.
 *
 * <p>여기 있는 것들의 공통점은 <b>클라이언트의 행동을 바꾸지 않지만 우리의 행동은
 * 바꾼다</b>는 것이다. 그래서 응답이 아니라 지표로 간다.
 */
public interface SearchTelemetry {

    /**
     * 오케스트레이터가 가용성을 위해 catch 한 예외.
     *
     * <p>이 기록이 없으면 최외곽 {@code onErrorResume} 은 그냥 버그를 삼키는 catch 다.
     * 공급사 실패와 분리해서 센다 — 공급사 성공률에 우리 버그가 섞이면 그 지표는 의미가 없다.
     */
    void adapterPipelineFailed(SupplierId supplierId, Throwable cause);

    /**
     * 정규화 단계에서 거부된 item 수.
     *
     * <p>사유를 레이블로 남기는 것이 핵심이다. <b>사유마다 다른 사람의 다른 행동을
     * 유발하기 때문</b>이다 — DATE_COVERAGE_MISMATCH 는 공급사 문의,
     * UNMAPPED_ROOM_TYPE 은 카탈로그 갱신, CONTRACT_ASSUMPTION_BROKEN 은 코드 수정이다.
     * 하나로 뭉치면 "거부가 늘었다" 는 것만 알고 무엇을 해야 할지는 모른다.
     */
    void offersRejected(SupplierId supplierId, String reason, int count);

    /**
     * 공급사 원격 호출 시간.
     *
     * <p>성공률만 있으면 "실패가 늘었다" 는 알 수 있지만, "느려지고 있어서 곧 실패할 것
     * 같다" 는 보이지 않는다. Retry 나 CircuitBreaker 를 넣을지 판단하려면 성공/실패뿐 아니라
     * 지연도 같이 봐야 한다.
     */
    default void supplierCallCompleted(SupplierId supplierId, String outcome, Duration duration) {}

    /**
     * 검색 한 건의 최종 결과.
     *
     * <p>공급사별 상태와, 재고 0으로 응답에서 제외된 건수를 남긴다.
     * 제외 건수는 클라이언트에게 알릴 이유가 없지만(행동이 바뀌지 않는다),
     * 우리에게는 "왜 결과가 비었는가" 를 판별하는 신호다 —
     * 만실이라 빈 것과 조회가 실패해서 빈 것은 대응이 다르다.
     */
    void searchCompleted(SearchResult result);
}
