package com.integration.stay.supplier;

import java.time.Duration;

/**
 * 공급사 호출 설정.
 *
 * <p>여기 있는 값들은 개별 최적값을 주장하지 않는다. 공급사 SLO 도 rate limit 도 계약에 없다.
 * 주장하는 것은 <b>값들 사이의 순서 관계</b>이고, 그 순서가 실패를 어디서 드러낼지를 정한다.
 *
 * <pre>
 * batchConcurrency ≤ bulkheadMaxConcurrentCalls &lt; maxConnections     (DESIGN_INVARIANT)
 * pendingAcquireTimeout &lt; responseTimeout                            (DESIGN_INVARIANT)
 * </pre>
 *
 * <p><b>앞 줄이 필요한 이유:</b> {@code batchConcurrency} 는 <b>요청 하나</b>의 fan-out 상한이라
 * 동시 검색 N 건이면 상류로 나가는 호출은 N 배가 된다. 전송 계층 풀이 먼저 고갈되면 포화가
 * Reactor Netty 내부 예외로 드러나는데, 그 풀의 기본 크기는
 * {@code max(availableProcessors, 8) × 2} 라 <b>호스트 CPU 수에 따라 달라진다.</b>
 * 배포 환경마다 다른 한계는 근거로 삼을 수 없다. 그래서 애플리케이션 계층의 bulkhead 가
 * 먼저 걸리게 하고, 전송 계층 풀은 그보다 크게 잡아 <b>구속 조건이 되지 않게</b> 한다.
 * 포화는 우리가 설명할 수 있는 한계에서 드러나야 한다.
 *
 * <p><b>뒤 줄이 필요한 이유:</b> 풀 획득 대기의 기본값은 45초로 응답 타임아웃보다 훨씬 길다.
 * 그대로 두면 풀이 고갈됐을 때 획득을 기다리는 도중 우리 응답 타임아웃이 먼저 터지고,
 * 그 실패는 {@code TIMEOUT}(공급사 잘못)으로 기록된다. 실제 원인은 우리 자원 포화다.
 *
 * <p><b>공급사의 벌크 상한(50)은 여기 없다.</b> 그것은 CONTRACT 이고 어댑터 내부 상수다.
 * 설정으로 빼면 설정 실수 하나로 프로토콜을 위반하게 된다.
 *
 * @param connectTimeout 연결 수립 상한 (EVALUATION_DEFAULT)
 * @param responseTimeout 응답 수신 상한 (EVALUATION_DEFAULT)
 * @param batchConcurrency 한 검색 요청의 fan-out 상한 (EVALUATION_DEFAULT)
 * @param bulkheadMaxConcurrentCalls 공급사 하나에 대한 <b>전체</b> 동시 호출 상한.
 *     현재 부하 검증에서는 해당 상한이 실제 구속 조건으로 동작하는지 확인한다. 공급사별로 따로
 *     둔다 — A 의 포화가 B 의 몫을 잠식하면 격리 설계가 무너진다
 * @param maxConnections 전송 계층 커넥션 상한 (EVALUATION_DEFAULT). 호스트 CPU 에
 *     의존하지 않게 하려고 명시한다. 최적값 주장이 아니라 <b>결정성</b>이 목적이다
 * @param pendingAcquireTimeout 커넥션 획득 대기 상한 (EVALUATION_DEFAULT)
 */
public record SupplierClientProperties(
        Duration connectTimeout,
        Duration responseTimeout,
        int batchConcurrency,
        int bulkheadMaxConcurrentCalls,
        int maxConnections,
        Duration pendingAcquireTimeout) {

    public SupplierClientProperties {
        if (batchConcurrency < 1) {
            throw new IllegalArgumentException("batchConcurrency must be >= 1: " + batchConcurrency);
        }
        // 불변식을 생성자에서 검사한다. 설정 파일의 오타 하나로 포화가 엉뚱한 곳에서
        // 드러나기 시작하면, 그때는 지표를 아무리 봐도 원인을 알 수 없다.
        if (bulkheadMaxConcurrentCalls < batchConcurrency) {
            throw new IllegalArgumentException(
                    "bulkheadMaxConcurrentCalls(%d) must be >= batchConcurrency(%d): 한 요청조차 통과하지 못한다"
                            .formatted(bulkheadMaxConcurrentCalls, batchConcurrency));
        }
        if (maxConnections <= bulkheadMaxConcurrentCalls) {
            throw new IllegalArgumentException(
                    "maxConnections(%d) must be > bulkheadMaxConcurrentCalls(%d): 전송 계층이 먼저 구속되면 포화가 호스트 CPU 에 따라 달라진다"
                            .formatted(maxConnections, bulkheadMaxConcurrentCalls));
        }
        if (pendingAcquireTimeout.compareTo(responseTimeout) >= 0) {
            throw new IllegalArgumentException(
                    "pendingAcquireTimeout(%s) must be < responseTimeout(%s): 포화가 공급사 타임아웃으로 위장된다"
                            .formatted(pendingAcquireTimeout, responseTimeout));
        }
    }
}
