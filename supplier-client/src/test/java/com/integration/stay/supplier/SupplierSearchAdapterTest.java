package com.integration.stay.supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.application.search.FailureType;
import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.application.search.SupplierSearchStatus;
import com.integration.stay.domain.StayPeriod;
import com.integration.stay.supplier.a.ASearchAdapter;
import com.integration.stay.supplier.b.BSearchAdapter;
import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

class SupplierSearchAdapterTest {

    private static final SearchCommand THREE_NIGHTS = new SearchCommand(
            new StayPeriod(LocalDate.parse("2026-09-01"), LocalDate.parse("2026-09-04")), 2, 0);
    private static final SupplierClientProperties FAST = new SupplierClientProperties(
            Duration.ofMillis(500), Duration.ofMillis(500), 4, 16, 32, Duration.ofMillis(200));

    private final RecordingTelemetry telemetry = new RecordingTelemetry();

    private WireMockServer server;
    private ASearchAdapter adapterA;
    private BSearchAdapter adapterB;

    @BeforeEach
    void startStub() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        WebClient client = WebClient.builder().baseUrl(server.baseUrl()).build();
        adapterA = new ASearchAdapter(client, FAST, telemetry);
        adapterB = new BSearchAdapter(client, FAST, telemetry);
    }

    @AfterEach
    void stopStub() {
        server.stop();
    }

    private static String aBody() {
        return """
               { "items": [ {
                 "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
                 "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin",
                 "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                 "dailyRates": [
                   { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
                   { "date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
                   { "date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 } ] } ] }
               """;
    }

    private void stubA(String body) {
        server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private void stubB(String body) {
        server.stubFor(get(urlPathEqualTo("/b/api/search"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private SupplierOutcome runA(CatalogSnapshot snapshot) {
        return adapterA.searchAll(THREE_NIGHTS, snapshot).block();
    }

    private SupplierOutcome runB(CatalogSnapshot snapshot) {
        return adapterB.searchAll(THREE_NIGHTS, snapshot).block();
    }

    @Test
    @DisplayName("B 의 HTTP 200 실패 코드를 실패로 처리한다")
    void B의_HTTP200_실패코드를_실패로_처리한다() {
        // 본문 코드를 안 보면 장애를 정상 응답으로 처리하게 된다.
        stubB("""
              { "resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null }
              """);

        SupplierOutcome outcome = runB(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.FAILED);
        assertThat(outcome.failure()).contains(FailureType.UNAVAILABLE);
        assertThat(outcome.usable()).isFalse();
    }

    @Test
    @DisplayName("B 의 성공 코드인데 data 가 null 이면 계약 위반이다")
    void B의_성공코드인데_data가_null이면_계약위반이다() {
        stubB("""
              { "resultCode": "0000", "resultMessage": "SUCCESS", "data": null }
              """);

        SupplierOutcome outcome = runB(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.FAILED);
        assertThat(outcome.failure()).contains(FailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("B 의 rate limit 코드는 A 의 429 와 같은 의미로 분류된다")
    void B의_rate_limit_코드는_A의_429와_같은_의미로_분류된다() {
        // "실패 판정 통일" 이란 이 대응이 성립한다는 뜻이다.
        stubB("""
              { "resultCode": "E429", "resultMessage": "RATE_LIMIT", "data": null }
              """);
        server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(aResponse().withStatus(429)));

        assertThat(runB(CatalogFixture.snapshot()).failure()).contains(FailureType.RATE_LIMITED);
        assertThat(runA(CatalogFixture.snapshot()).failure()).contains(FailureType.RATE_LIMITED);
    }

    @Test
    @DisplayName("malformed json 은 batch 실패이지 item 거부가 아니다")
    void malformed_json은_batch_실패이지_item_거부가_아니다() {
        // 역직렬화 이전·중의 실패는 item 단위로 격리할 수 없다.
        // 문서의 격리 단위 표가 코드와 일치하는지 못박는다.
        stubA("{ \"items\": [ { \"hotelCode\": ");

        SupplierOutcome outcome = runA(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.FAILED);
        assertThat(outcome.offers()).isEmpty();
    }

    @Test
    @DisplayName("빈 응답 본문은 batch 실패다")
    void 빈_응답_본문은_batch_실패다() {
        // empty 는 에러가 아니라서 onErrorResume 이 잡지 못한다.
        // switchIfEmpty 가 없으면 이 배치가 성공도 실패도 아닌 채로 조용히 사라진다.
        server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")));

        SupplierOutcome outcome = runA(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.FAILED);
        assertThat(outcome.failure()).contains(FailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("무응답은 TIMEOUT 으로 분류된다")
    void 무응답은_TIMEOUT으로_분류된다() {
        server.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .willReturn(aResponse()
                        // 500ms 응답 타임아웃을 넘기면 충분하다. 길게 잡으면 다음 테스트의
                        // WireMock 스레드에 지연 응답이 남아 실패 분류 검증을 흔들 수 있다.
                        .withFixedDelay(800)
                        .withHeader("Content-Type", "application/json")
                        .withBody(aBody())));

        SupplierOutcome outcome = runA(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.FAILED);
        assertThat(outcome.failure()).contains(FailureType.TIMEOUT);
    }

    @Test
    @DisplayName("공급사 5xx 는 UNAVAILABLE, 4xx 는 우리 잘못으로 분류된다")
    void 공급사_5xx는_UNAVAILABLE_4xx는_우리_잘못으로_분류된다() {
        // 4xx 는 공급사가 정상 동작하면서 "네 요청이 잘못됐다" 고 답한 것이다.
        // 공급사 가용성으로 집계하면 공급사 성공률 지표가 의미를 잃는다.
        server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(aResponse().withStatus(503)));
        assertThat(runA(CatalogFixture.snapshot()).failure()).contains(FailureType.UNAVAILABLE);

        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(aResponse().withStatus(400)));
        SupplierOutcome outcome = runA(CatalogFixture.snapshot());
        assertThat(outcome.failure()).contains(FailureType.INTERNAL_ERROR);
        assertThat(outcome.failure().orElseThrow().isOurFault()).isTrue();
    }

    @Test
    @DisplayName("요청한 숙소가 응답에 없는 것은 실패가 아니다")
    void 요청한_숙소가_응답에_없는_것은_실패가_아니다() {
        // 계약은 response ⊆ requested 이지 == 이 아니다. 재고 없음이나 인원 조건 미달로
        // 응답에서 빠지는 것은 정상이다. == 로 검증하면 정상 상황을 계속 거부한다.
        stubA(aBody()); // A-10044 는 요청되지만 응답에 없다

        SupplierOutcome outcome = runA(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.SUCCESS);
        assertThat(outcome.offers()).hasSize(1);
    }

    @Test
    @DisplayName("공급사가 빈 items 를 주면 정상 빈 결과다")
    void 공급사가_빈_items를_주면_정상_빈_결과다() {
        stubA("{ \"items\": [] }");

        SupplierOutcome outcome = runA(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.SUCCESS);
        assertThat(outcome.usable()).isTrue();
        assertThat(outcome.offers()).isEmpty();
    }

    @Test
    @DisplayName("전 item 이 거부되면 배치가 실패한다")
    void 전_item이_거부되면_배치가_실패한다() {
        // 응답은 왔는데 하나도 쓸 수 없다. 정상 빈 결과와 구분되어야 한다.
        stubA(aBody().replace("\"currency\": \"KRW\"", "\"currency\": \"XYZ\""));

        SupplierOutcome outcome = runA(CatalogFixture.snapshot());

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.FAILED);
        assertThat(outcome.failure()).contains(FailureType.INVALID_RESPONSE);
    }

    @Test
    @DisplayName("전부 매핑 실패면 공급사 장애가 아니라 카탈로그 문제로 분류된다")
    void 전부_매핑_실패면_공급사_장애가_아니라_카탈로그_문제로_분류된다() {
        stubA(aBody().replace("\"roomTypeCode\": \"DLX-TWN\"", "\"roomTypeCode\": \"BRAND-NEW\""));

        SupplierOutcome outcome = runA(CatalogFixture.snapshot());

        assertThat(outcome.failure()).contains(FailureType.CATALOG_UNAVAILABLE);
    }

    @Test
    @DisplayName("동기화 이력이 없으면 호출하지 않고 CATALOG_UNAVAILABLE 이다")
    void 동기화_이력이_없으면_호출하지_않고_CATALOG_UNAVAILABLE이다() {
        // 이 상태가 없으면 "고객은 B 상품을 못 받았는데 응답은 완전한 결과" 가 된다.
        CatalogSnapshot onlyA = CatalogSnapshot.builder()
                .roomType(SupplierIds.A, "A-10023", CatalogFixture.RIVERSIDE_A_PROPERTY, "Riverside",
                        "DLX-TWN", CatalogFixture.RIVERSIDE_A_ROOM, "Deluxe Twin")
                .build();

        SupplierOutcome outcome = runB(onlyA);

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.CATALOG_UNAVAILABLE);
        assertThat(outcome.degraded()).isTrue();
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("거부 사유가 지표로 발행된다")
    void 거부_사유가_지표로_발행된다() {
        // 사유를 9개로 나눈 이유가 "지표 레이블로 쓰이기 때문" 이라고 문서에 썼다.
        // 실제로 발행하지 않으면 그 문장은 거짓이다.
        stubA(aBody().replace("\"currency\": \"KRW\"", "\"currency\": \"XYZ\""));

        runA(CatalogFixture.snapshot());

        assertThat(telemetry.rejections)
                .extracting(RecordingTelemetry.Rejection::reason, RecordingTelemetry.Rejection::count)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("UNKNOWN_CURRENCY", 1));
    }

    @Test
    @DisplayName("거부가 없으면 지표도 발행하지 않는다")
    void 거부가_없으면_지표도_발행하지_않는다() {
        // 반대 방향 확인. 없으면 "항상 발행한다" 로도 위 테스트가 통과한다.
        stubA(aBody());

        runA(CatalogFixture.snapshot());

        assertThat(telemetry.rejections).isEmpty();
    }

    @Test
    @DisplayName("활성 숙소가 0개면 NO_TARGETS 이고 부분 실패가 아니다")
    void 활성_숙소가_0개면_NO_TARGETS이고_부분_실패가_아니다() {
        CatalogSnapshot syncedButEmpty =
                CatalogSnapshot.builder().synced(SupplierIds.B).build();

        SupplierOutcome outcome = runB(syncedButEmpty);

        assertThat(outcome.status()).isEqualTo(SupplierSearchStatus.NO_TARGETS);
        assertThat(outcome.usable()).isTrue();
        assertThat(outcome.degraded()).isFalse();
        assertThat(server.getAllServeEvents()).isEmpty();
    }
}
