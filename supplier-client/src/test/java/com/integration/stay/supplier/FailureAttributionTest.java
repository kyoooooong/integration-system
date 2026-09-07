package com.integration.stay.supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integration.stay.application.search.FailureType;
import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.domain.StayPeriod;
import com.integration.stay.supplier.a.ASearchAdapter;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;

/**
 * 실패의 <b>소유권</b>이 올바르게 귀속되는지 본다.
 *
 * <p>이 구분이 무너지면 지표가 거짓말을 한다. 우리가 스스로 유발한 포화를 공급사 장애로
 * 집계하면 대응이 "공급사에 문의" 로 흘러가고, 실제 원인인 우리 동시성 설정은 손대지 않는다.
 *
 * <p>특히 위험한 지점 하나 —
 * {@code PoolAcquireTimeoutException} 은 {@link java.util.concurrent.TimeoutException} 을
 * <b>상속한다.</b> 상속만 보고 분류하면 풀 고갈이 자동으로 공급사 타임아웃이 된다.
 */
class FailureAttributionTest {

    private static final SearchCommand THREE_NIGHTS = new SearchCommand(
            new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);

    private final RecordingTelemetry telemetry = new RecordingTelemetry();

    private HttpServer slowServer;

    @BeforeEach
    void startSlowServer() throws IOException {
        slowServer = HttpServer.create(new InetSocketAddress(0), 0);
        slowServer.setExecutor(Executors.newFixedThreadPool(16));
        slowServer.createContext("/a/v1/availability", exchange -> {
            try {
                Thread.sleep(800);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = "{ \"items\": [] }".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        slowServer.start();
    }

    @AfterEach
    void stopSlowServer() {
        slowServer.stop(0);
    }

    private ASearchAdapter adapter(SupplierClientProperties properties) {
        return new ASearchAdapter(
                WebClient.builder().baseUrl("http://localhost:" + slowServer.getAddress().getPort()).build(),
                properties,
                telemetry);
    }

    private static com.integration.stay.application.catalog.CatalogSnapshot snapshotOf(int count) {
        var builder = com.integration.stay.application.catalog.CatalogSnapshot.builder();
        for (int i = 0; i < count; i++) {
            builder.roomType(
                    SupplierIds.A, "A-%05d".formatted(i), UUID.randomUUID(), "H", "STD", UUID.randomUUID(), "S");
        }
        return builder.build();
    }

    @Test
    @DisplayName("동시 검색이 겹치면 bulkhead 포화가 우리 포화로 분류된다")
    void 동시_검색이_겹치면_bulkhead_포화가_우리_포화로_분류된다() {
        // batchConcurrency 는 "요청 하나" 의 상한이라 한 요청 안에서는 절대 bulkhead 를
        // 넘지 못한다. flatMap 이 이미 그만큼만 구독하기 때문이다.
        // 상류가 위험해지는 것은 동시 검색이 겹칠 때다 — 동시 4건이면 4배가 나간다.
        // Bulkhead 가 CircuitBreaker 보다 우선인 이유가 정확히 이것이다.
        var properties = new SupplierClientProperties(
                Duration.ofSeconds(2), Duration.ofSeconds(5), 4, 4, 8, Duration.ofSeconds(1));
        // 어댑터 하나를 공유한다. bulkhead 는 요청 사이에 공유되어야 의미가 있다.
        var adapter = adapter(properties);
        var snapshot = snapshotOf(200); // 배치 4개

        List<SupplierOutcome> outcomes = Flux.range(0, 4)
                .flatMap(i -> adapter.searchAll(THREE_NIGHTS, snapshot), 4)
                .collectList()
                .block();

        assertThat(outcomes).hasSize(4);
        List<FailureType> failures = outcomes.stream()
                .map(SupplierOutcome::failure)
                .flatMap(Optional::stream)
                .toList();

        assertThat(failures)
                .as("동시 검색이 상한을 넘었으므로 일부는 포화로 실패해야 한다")
                .isNotEmpty()
                .containsOnly(FailureType.LOCAL_SATURATION);
        assertThat(failures.getFirst().isOurFault()).isTrue();
        // 포화는 재시도 가능한 일시적 문제다. 버그가 아니다.
        assertThat(failures.getFirst().isDeterministicBug()).isFalse();
    }

    @Test
    @DisplayName("공급사가 느린 것은 우리 포화가 아니라 TIMEOUT 이다")
    void 공급사가_느린_것은_우리_포화가_아니라_TIMEOUT이다() {
        // 반대 방향 확인. 이게 없으면 위 테스트는 "전부 LOCAL_SATURATION 으로 분류한다" 로도 통과한다.
        var roomy = new SupplierClientProperties(
                Duration.ofSeconds(2), Duration.ofMillis(200), 4, 64, 128, Duration.ofMillis(100));

        SupplierOutcome outcome = adapter(roomy).searchAll(THREE_NIGHTS, snapshotOf(50)).block();

        assertThat(outcome).isNotNull();
        assertThat(outcome.failure()).contains(FailureType.TIMEOUT);
        assertThat(outcome.failure().orElseThrow().isOurFault()).isFalse();
    }

    @Test
    @DisplayName("숫자 사이의 순서 불변식이 깨지면 기동이 실패한다")
    void 숫자_사이의_순서_불변식이_깨지면_기동이_실패한다() {
        // 설정 오타로 포화가 엉뚱한 계층에서 드러나기 시작하면 지표를 봐도 원인을 알 수 없다.
        // 런타임에 이상하게 동작하느니 기동을 실패시킨다.
        assertThatThrownBy(() -> new SupplierClientProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 8, 4, 32, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("batchConcurrency");

        assertThatThrownBy(() -> new SupplierClientProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 4, 16, 16, Duration.ofMillis(500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxConnections");

        assertThatThrownBy(() -> new SupplierClientProperties(
                        Duration.ofSeconds(1), Duration.ofSeconds(2), 4, 16, 32, Duration.ofSeconds(2)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pendingAcquireTimeout");
    }
}
