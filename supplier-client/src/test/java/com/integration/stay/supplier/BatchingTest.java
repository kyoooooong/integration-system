package com.integration.stay.supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.application.search.SupplierSearchStatus;
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
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/** 50개 배칭과 수천 숙소 규모에서의 동작. */
class BatchingTest {

    private static final SearchCommand THREE_NIGHTS = new SearchCommand(
            new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);

    private WireMockServer server;

    @BeforeEach
    void startStub() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stopStub() {
        server.stop();
    }

    /** 숙소 n 개짜리 카탈로그. 코드는 A-00000 형태로 정렬 가능하게 만든다. */
    private static CatalogSnapshot snapshotOf(int count) {
        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        for (int i = 0; i < count; i++) {
            String code = "A-%05d".formatted(i);
            builder.roomType(
                    SupplierIds.A, code, UUID.randomUUID(), "Hotel " + code, "STD", UUID.randomUUID(), "Standard");
        }
        return builder.build();
    }

    private static String bodyFor(String hotelCode) {
        return """
               { "items": [ {
                 "hotelCode": "%s", "hotelName": "Hotel %s",
                 "roomTypeCode": "STD", "roomTypeName": "Standard",
                 "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                 "dailyRates": [
                   { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 100000, "taxAmount": 10000 },
                   { "date": "2026-09-02", "remainingRooms": 2, "nightlyRate": 100000, "taxAmount": 10000 },
                   { "date": "2026-09-03", "remainingRooms": 4, "nightlyRate": 100000, "taxAmount": 10000 } ] } ] }
               """
                .formatted(hotelCode, hotelCode);
    }

    private final RecordingTelemetry telemetry = new RecordingTelemetry();

    private ASearchAdapter adapter(String baseUrl, int concurrency) {
        return new ASearchAdapter(
                WebClient.builder().baseUrl(baseUrl).build(),
                new SupplierClientProperties(
                        Duration.ofSeconds(2), Duration.ofSeconds(5), concurrency, 64, 128, Duration.ofSeconds(1)),
                telemetry);
    }

    @Test
    @DisplayName("50개 상한으로 분할한다 — 50/51/100/101")
    void 배치는_50개_상한으로_분할된다() {
        // 공급사 프로토콜 상한이 50이고 초과하면 오류다. 경계에서 정확해야 한다.
        assertThat(AbstractSupplierSearchAdapter.partition(codes(50), 50)).hasSize(1);
        assertThat(AbstractSupplierSearchAdapter.partition(codes(51), 50)).hasSize(2);
        assertThat(AbstractSupplierSearchAdapter.partition(codes(100), 50)).hasSize(2);
        assertThat(AbstractSupplierSearchAdapter.partition(codes(101), 50)).hasSize(3);
        assertThat(AbstractSupplierSearchAdapter.partition(codes(101), 50))
                .allSatisfy(batch -> assertThat(batch).hasSizeLessThanOrEqualTo(50));
    }

    private static List<String> codes(int n) {
        return java.util.stream.IntStream.range(0, n).mapToObj("A-%05d"::formatted).toList();
    }

    @Test
    @DisplayName("숙소 1000개면 정확히 20번 호출한다")
    void 숙소_1000개면_정확히_20번_호출한다() {
        server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{ \"items\": [] }")));

        SupplierOutcome outcome = adapter(server.baseUrl(), 4).searchAll(THREE_NIGHTS, snapshotOf(1000)).block();

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.SUCCESS);
        server.verify(20, getRequestedFor(urlPathEqualTo("/a/v1/availability")));
    }

    @Test
    @DisplayName("배치 하나가 실패해도 나머지 배치 결과는 유지된다")
    void 배치_하나가_실패해도_나머지_배치_결과는_유지된다() {
        // onErrorResume 이 flatMap 바깥에 있으면 첫 배치 실패가 나머지를 취소해
        // 성공한 배치들이 통째로 사라진다. 그 회귀를 잡는다.
        server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .withQueryParam("hotelCodes", containing("A-00059"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(bodyFor("A-00059"))));
        server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .withQueryParam("hotelCodes", containing("A-00000"))
                .willReturn(aResponse().withStatus(503)));

        // 60개 -> 배치 2개 (50 + 10)
        SupplierOutcome outcome = adapter(server.baseUrl(), 4).searchAll(THREE_NIGHTS, snapshotOf(60)).block();

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.PARTIAL);
        assertThat(outcome.offers()).hasSize(1);
        assertThat(outcome.usable()).isTrue();
        assertThat(outcome.degraded()).isTrue();
    }

    @Test
    @DisplayName("동시 호출 수가 설정한 상한을 넘지 않는다")
    void 동시_호출_수가_설정한_상한을_넘지_않는다() throws IOException, InterruptedException {
        // 경과 시간으로 검증하지 않는다. 상한이 깨져 있어도 총 소요는 비슷하게 나온다.
        // 서버에서 동시에 들어와 있는 요청 수를 직접 센다.
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger maxInFlight = new AtomicInteger();

        HttpServer probe = HttpServer.create(new InetSocketAddress(0), 0);
        probe.setExecutor(Executors.newFixedThreadPool(32));
        probe.createContext("/a/v1/availability", exchange -> {
            int current = inFlight.incrementAndGet();
            maxInFlight.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(60);
                byte[] body = "{ \"items\": [] }".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                inFlight.decrementAndGet();
            }
        });
        probe.start();

        try {
            // 500개 -> 배치 10개. 상한 3이면 동시에 3개를 넘을 수 없다.
            SupplierOutcome outcome = adapter("http://localhost:" + probe.getAddress().getPort(), 3)
                    .searchAll(THREE_NIGHTS, snapshotOf(500))
                    .block();

            assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.SUCCESS);
            assertThat(maxInFlight.get()).isLessThanOrEqualTo(3);
            // 상한이 실제로 걸렸는지도 확인한다. 1로 떨어졌다면 병렬이 아예 안 된 것이고
            // 그러면 이 테스트는 상한을 검증한 게 아니다.
            assertThat(maxInFlight.get()).isGreaterThan(1);
        } finally {
            probe.stop(0);
        }
    }
}
