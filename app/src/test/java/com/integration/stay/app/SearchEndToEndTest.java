package com.integration.stay.app;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.integration.stay.application.catalog.CatalogSyncService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 핵심 흐름 하나가 끊김 없이 동작하는지 본다.
 *
 * <p>카탈로그 동기화 -> 매핑 저장 -> 병렬 조회 -> 정규화 -> 병합 -> 응답까지 전부 실제
 * 컴포넌트다. 공급사 자리에만 WireMock 이 들어간다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    // 스케줄러 자동 실행과 테스트의 명시 호출이 겹치지 않게 한다.
    "catalog.sync.initial-delay=1h",
    "supplier.response-timeout=1s",
    "supplier.connect-timeout=1s"
})
class SearchEndToEndTest {

    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17.9-alpine"));
    private static final WireMockServer SUPPLIERS =
            new WireMockServer(WireMockConfiguration.options().dynamicPort());

    static {
        POSTGRES.start();
        SUPPLIERS.start();
    }

    @AfterAll
    static void stopStub() {
        SUPPLIERS.stop();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("supplier.base-url", SUPPLIERS::baseUrl);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private CatalogSyncService catalogSyncService;

    @BeforeEach
    void resetStubs() {
        SUPPLIERS.resetAll();
        stubCatalogs();
    }

    private RestClient client() {
        return RestClient.create("http://localhost:" + port);
    }

    private ResponseEntity<String> search() {
        return client().get()
                .uri("/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0")
                .retrieve()
                .onStatus(status -> true, (req, res) -> {})
                .toEntity(String.class);
    }

    private void stubCatalogs() {
        SUPPLIERS.stubFor(get(urlPathEqualTo("/a/v1/hotels"))
                .willReturn(json("""
                        { "items": [
                          { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
                            "roomTypes": [ { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin",
                                             "maxOccupancy": 2 } ] },
                          { "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay",
                            "roomTypes": [ { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double",
                                             "maxOccupancy": 2 } ] } ] }
                        """)));
        SUPPLIERS.stubFor(get(urlPathEqualTo("/b/api/properties"))
                .willReturn(json("""
                        { "resultCode": "0000", "resultMessage": "SUCCESS", "data": { "items": [
                          { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
                            "rooms": [ { "roomId": "R-401", "roomName": "Deluxe Twin Room",
                                         "maxOccupancy": 2 } ] } ] } }
                        """)));
    }

    private void stubNormalAvailability() {
        SUPPLIERS.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(json("""
                { "items": [
                  { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
                    "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin",
                    "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                    "dailyRates": [
                      { "date": "2026-09-01", "remainingRooms": 3, "nightlyRate": 120000, "taxAmount": 12000 },
                      { "date": "2026-09-02", "remainingRooms": 1, "nightlyRate": 150000, "taxAmount": 15000 },
                      { "date": "2026-09-03", "remainingRooms": 5, "nightlyRate": 120000, "taxAmount": 12000 } ] },
                  { "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay",
                    "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double",
                    "maxOccupancy": 2, "breakfastIncluded": false, "currency": "KRW",
                    "dailyRates": [
                      { "date": "2026-09-01", "remainingRooms": 2, "nightlyRate": 88000, "taxAmount": 8800 },
                      { "date": "2026-09-02", "remainingRooms": 0, "nightlyRate": 99000, "taxAmount": 9900 },
                      { "date": "2026-09-03", "remainingRooms": 4, "nightlyRate": 88000, "taxAmount": 8800 } ] } ] }
                """)));
        SUPPLIERS.stubFor(get(urlPathEqualTo("/b/api/search")).willReturn(json("""
                { "resultCode": "0000", "resultMessage": "SUCCESS", "data": { "items": [
                  { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
                    "roomId": "R-401", "roomName": "Deluxe Twin Room",
                    "maxOccupancy": 2, "breakfastIncluded": true, "currency": "KRW",
                    "totalPrice": 452000, "taxIncluded": true,
                    "inventory": [
                      { "date": "2026-09-01", "remainingRooms": 3 },
                      { "date": "2026-09-02", "remainingRooms": 1 },
                      { "date": "2026-09-03", "remainingRooms": 5 } ] } ] } }
                """)));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    @Test
    @DisplayName("정상 검색: A 와 B 가 각자의 내부 식별자로 나오고 만실 상품은 빠진다")
    void 정상_검색() {
        catalogSyncService.syncAll();
        stubNormalAvailability();

        ResponseEntity<String> response = search();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        String body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body).contains("\"partial\":false");
        // A 429,000 (조식 미포함) 과 B 452,000 (조식 포함) 이 병합되지 않고 둘 다 나온다.
        assertThat(body).contains("\"totalAmount\":429000").contains("\"totalAmount\":452000");
        // A 는 세액을 알고 B 는 모른다.
        assertThat(body).contains("\"taxAmount\":39000").contains("\"taxAmount\":null");
        // Namsan Garden Stay 는 9/2 재고가 0이라 items 에 없다.
        assertThat(body).doesNotContain("Namsan");
    }

    @Test
    @DisplayName("내부 식별자는 동기화를 반복해도 같다")
    void 내부_식별자는_동기화를_반복해도_같다() {
        catalogSyncService.syncAll();
        stubNormalAvailability();
        String first = search().getBody();

        catalogSyncService.syncAll();
        String second = search().getBody();

        String firstId = extractFirstPropertyId(first);
        assertThat(second).contains(firstId);
    }

    private static String extractFirstPropertyId(String body) {
        int start = body.indexOf("\"propertyId\":\"") + "\"propertyId\":\"".length();
        return body.substring(start, body.indexOf('"', start));
    }

    @Test
    @DisplayName("A 가 무응답이어도 B 결과로 200 부분 응답을 준다")
    void A가_무응답이어도_B_결과로_200_부분_응답을_준다() {
        catalogSyncService.syncAll();
        stubNormalAvailability();
        SUPPLIERS.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .willReturn(json("{ \"items\": [] }").withFixedDelay(5_000)));

        ResponseEntity<String> response = search();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"partial\":true")
                .contains("\"supplier\":\"A\",\"status\":\"FAILED\",\"reason\":\"TIMEOUT\"")
                .contains("\"totalAmount\":452000");
    }

    @Test
    @DisplayName("B 는 HTTP 200 으로 실패하지만 우리는 FAILED 로 다룬다")
    void B는_HTTP200으로_실패하지만_우리는_FAILED로_다룬다() {
        catalogSyncService.syncAll();
        stubNormalAvailability();
        SUPPLIERS.stubFor(get(urlPathEqualTo("/b/api/search"))
                .willReturn(json("""
                        { "resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null }
                        """)));

        ResponseEntity<String> response = search();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody())
                .contains("\"supplier\":\"B\",\"status\":\"FAILED\",\"reason\":\"UNAVAILABLE\"")
                .contains("\"totalAmount\":429000");
    }

    @Test
    @DisplayName("둘 다 실패하면 빈 배열 200 이 아니라 상태 코드로 알린다")
    void 둘_다_실패하면_빈_배열_200이_아니라_상태_코드로_알린다() {
        catalogSyncService.syncAll();
        SUPPLIERS.stubFor(get(urlPathEqualTo("/a/v1/availability")).willReturn(aResponse().withStatus(503)));
        SUPPLIERS.stubFor(get(urlPathEqualTo("/b/api/search"))
                .willReturn(json("""
                        { "resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null }
                        """)));

        ResponseEntity<String> response = search();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("동질 타임아웃 실패는 504 다")
    void 동질_타임아웃_실패는_504다() {
        catalogSyncService.syncAll();
        SUPPLIERS.stubFor(get(urlPathEqualTo("/a/v1/availability"))
                .willReturn(json("{ \"items\": [] }").withFixedDelay(5_000)));
        SUPPLIERS.stubFor(get(urlPathEqualTo("/b/api/search"))
                .willReturn(json("{ \"resultCode\": \"0000\", \"data\": { \"items\": [] } }")
                        .withFixedDelay(5_000)));

        ResponseEntity<String> response = search();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("잘못된 날짜 범위는 400 이다")
    void 잘못된_날짜_범위는_400이다() {
        ResponseEntity<String> response = client().get()
                .uri("/api/v1/stays/search?checkIn=2026-09-04&checkOut=2026-09-01&adults=2&children=0")
                .retrieve()
                .onStatus(status -> true, (req, res) -> {})
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_DATE_RANGE");
    }
}
