package com.integration.stay.supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient 가 Boot 4 의 Jackson 3 코덱으로 공급사 응답을 읽어내는지 확인한다.
 *
 * <p>{@link JacksonContractTest} 와 목적이 다르다. 저기는 우리가 만든 ObjectMapper 의
 * 의미론이고, 여기는 <b>실제 운영 경로인 WebClient 코덱</b>이다. 둘은 다른 mapper 를
 * 쓸 수 있으므로 한쪽만 통과했다고 다른 쪽이 보장되지 않는다.
 *
 * <p>WireMock 은 JUnit 확장 대신 프로그래매틱으로 띄운다. BOM 이 JUnit Jupiter 를
 * 6.0.3 으로 올렸는데 WireMock 3.13.1 의 JUnit5 확장은 5.x 를 전제로 하므로,
 * 확장을 쓰면 검증 대상이 아닌 곳에서 깨진다.
 */
class WebClientCodecTest {

    private WireMockServer server;
    private WebClient webClient;

    /** 공급사 B 의 응답 봉투. resultCode 로 실패를 알리고 실패 시 data 가 null 이다. */
    record BEnvelope(String resultCode, String resultMessage, BData data) {}

    record BData(List<BItem> items) {}

    record BItem(
            String propertyId,
            String roomId,
            Integer maxOccupancy,
            Boolean breakfastIncluded,
            String currency,
            Long totalPrice,
            Boolean taxIncluded,
            List<BRow> inventory) {}

    record BRow(LocalDate date, Integer remainingRooms) {}

    @BeforeEach
    void startStub() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        webClient = WebClient.builder().baseUrl(server.baseUrl()).build();
    }

    @AfterEach
    void stopStub() {
        server.stop();
    }

    @Test
    @DisplayName("WebClient 코덱이 공급사 응답을 boxed DTO 로 읽는다")
    void WebClient_코덱이_공급사_응답을_boxed_DTO로_읽는다() {
        server.stubFor(get(urlPathEqualTo("/b/api/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                { "resultCode": "0000", "resultMessage": "SUCCESS",
                                  "data": { "items": [ {
                                    "propertyId": "B77120", "roomId": "R-401",
                                    "maxOccupancy": 2, "breakfastIncluded": true,
                                    "currency": "KRW", "totalPrice": 452000, "taxIncluded": true,
                                    "inventory": [
                                      { "date": "2026-09-01", "remainingRooms": 3 },
                                      { "date": "2026-09-02", "remainingRooms": 1 },
                                      { "date": "2026-09-03", "remainingRooms": 5 } ] } ] } }
                                """)));

        BEnvelope body = webClient.get().uri("/b/api/search").retrieve().bodyToMono(BEnvelope.class).block();

        assertThat(body).isNotNull();
        assertThat(body.resultCode()).isEqualTo("0000");
        assertThat(body.data().items()).hasSize(1);

        BItem item = body.data().items().getFirst();
        assertThat(item.totalPrice()).isEqualTo(452_000L);
        assertThat(item.taxIncluded()).isTrue();
        assertThat(item.inventory())
                .extracting(BRow::date, BRow::remainingRooms)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 1), 3),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 2), 1),
                        org.assertj.core.groups.Tuple.tuple(LocalDate.of(2026, 9, 3), 5));
    }

    @Test
    @DisplayName("장애 응답의 data 가 null 이어도 봉투는 읽힌다")
    void 장애_응답의_data가_null이어도_봉투는_읽힌다() {
        // B 는 장애에도 HTTP 200 이다. 본문을 읽을 수 있어야 실패 판정을 할 수 있다.
        server.stubFor(get(urlPathEqualTo("/b/api/search"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                                """
                                { "resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null }
                                """)));

        BEnvelope body = webClient.get().uri("/b/api/search").retrieve().bodyToMono(BEnvelope.class).block();

        assertThat(body).isNotNull();
        assertThat(body.resultCode()).isEqualTo("E503");
        assertThat(body.data()).isNull();
    }

    @Test
    @DisplayName("코덱 상한을 넘는 응답은 거부된다")
    void 코덱_상한을_넘는_응답은_거부된다() {
        // 긴 기간 요청에서 응답이 커질 때 마지막 방어선은 코덱 상한이다.
        // 최대 조회 기간 정책이 없는 현재 조건에서 상한이 실제로 발동하는지를 확인해 둔다.
        String oversized = "{ \"resultCode\": \"0000\", \"resultMessage\": \"" + "x".repeat(4096) + "\" }";
        server.stubFor(get(urlPathEqualTo("/b/api/search"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(oversized)));

        WebClient bounded = WebClient.builder()
                .baseUrl(server.baseUrl())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(1024))
                .build();

        assertThatThrownBy(() ->
                        bounded.get().uri("/b/api/search").retrieve().bodyToMono(BEnvelope.class).block())
                .hasRootCauseInstanceOf(DataBufferLimitException.class);
    }
}
