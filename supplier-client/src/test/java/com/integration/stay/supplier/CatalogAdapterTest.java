package com.integration.stay.supplier;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.integration.stay.application.catalog.CatalogProperty;
import com.integration.stay.application.catalog.CatalogRoomType;
import com.integration.stay.supplier.a.ACatalogAdapter;
import com.integration.stay.supplier.b.BCatalogAdapter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * 숙소 목록 조회.
 *
 * <p>검색 경로에 비해 조용한 경로지만 <b>같은 함정이 있다</b> — B 는 목록 API 에서도
 * 장애 시 HTTP 200 을 준다. 여기서 본문 코드를 안 보면 실패한 응답을 "숙소 0개" 로
 * 해석하고, 그 빈 스냅샷이 <b>유효한 전체 스냅샷으로 적용되어 모든 매핑을 비활성화한다.</b>
 * 그러면 다음 검색부터 그 공급사가 통째로 사라진다.
 *
 * <p>빈 스냅샷을 유효하게 받기로 한 결정이, 목록 API 의 실패 판정을 <b>더</b> 중요하게 만든다.
 */
class CatalogAdapterTest {

    private static final SupplierClientProperties PROPERTIES = new SupplierClientProperties(
            Duration.ofSeconds(1), Duration.ofMillis(500), 4, 16, 24, Duration.ofMillis(200));

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

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    private WebClient client() {
        return WebClient.builder().baseUrl(server.baseUrl()).build();
    }

    private ACatalogAdapter adapterA() {
        return new ACatalogAdapter(client(), PROPERTIES);
    }

    private BCatalogAdapter adapterB() {
        return new BCatalogAdapter(client(), PROPERTIES);
    }

    private void stubA(ResponseDefinitionBuilder response) {
        server.stubFor(get(urlPathEqualTo("/a/v1/hotels")).willReturn(response));
    }

    private void stubB(ResponseDefinitionBuilder response) {
        server.stubFor(get(urlPathEqualTo("/b/api/properties")).willReturn(response));
    }

    @Test
    @DisplayName("A 의 숙소 목록을 파싱한다")
    void A의_숙소_목록을_파싱한다() {
        stubA(json("""
                { "items": [
                  { "hotelCode": "A-10023", "hotelName": "Riverside Hotel Seoul",
                    "roomTypes": [ { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin",
                                     "maxOccupancy": 2 } ] },
                  { "hotelCode": "A-10044", "hotelName": "Namsan Garden Stay",
                    "roomTypes": [ { "roomTypeCode": "STD-DBL", "roomTypeName": "Standard Double",
                                     "maxOccupancy": 2 } ] } ] }
                """));

        List<CatalogProperty> catalog = adapterA().fetchCatalog();

        assertThat(catalog).extracting(CatalogProperty::code).containsExactly("A-10023", "A-10044");
        assertThat(catalog.getFirst().roomTypes())
                .extracting(CatalogRoomType::code, CatalogRoomType::name, CatalogRoomType::maxOccupancy)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("DLX-TWN", "Deluxe Twin", 2));
    }

    @Test
    @DisplayName("B 의 봉투를 벗겨 숙소 목록을 파싱한다")
    void B의_봉투를_벗겨_숙소_목록을_파싱한다() {
        stubB(json("""
                { "resultCode": "0000", "resultMessage": "SUCCESS", "data": { "items": [
                  { "propertyId": "B77120", "propertyName": "Riverside Hotel Seoul",
                    "rooms": [ { "roomId": "R-401", "roomName": "Deluxe Twin Room",
                                 "maxOccupancy": 2 } ] } ] } }
                """));

        List<CatalogProperty> catalog = adapterB().fetchCatalog();

        assertThat(catalog).hasSize(1);
        assertThat(catalog.getFirst().code()).isEqualTo("B77120");
        assertThat(catalog.getFirst().roomTypes().getFirst().code()).isEqualTo("R-401");
    }

    @Test
    @DisplayName("B 는 목록 API 에서도 HTTP 200 으로 실패한다")
    void B는_목록_API에서도_HTTP200으로_실패한다() {
        // 본문 코드를 안 보면 이 응답이 "숙소 0개" 가 되고, 빈 스냅샷이 유효하게 적용되어
        // B 의 모든 매핑이 비활성화된다. 다음 검색부터 B 가 통째로 사라진다.
        stubB(json("""
                { "resultCode": "E503", "resultMessage": "TEMPORARILY_UNAVAILABLE", "data": null }
                """));

        assertThatThrownBy(() -> adapterB().fetchCatalog())
                .isInstanceOf(CatalogFetchException.class)
                .hasMessageContaining("E503");
    }

    @Test
    @DisplayName("B 의 성공 코드인데 data 가 null 이면 계약 위반이다")
    void B의_성공_코드인데_data가_null이면_계약_위반이다() {
        stubB(json("""
                { "resultCode": "0000", "resultMessage": "SUCCESS", "data": null }
                """));

        assertThatThrownBy(() -> adapterB().fetchCatalog())
                .isInstanceOf(CatalogFetchException.class)
                .hasMessageContaining("data is null");
    }

    @Test
    @DisplayName("A 의 HTTP 오류는 스냅샷 전체 실패다")
    void A의_HTTP_오류는_스냅샷_전체_실패다() {
        stubA(aResponse().withStatus(503));

        assertThatThrownBy(() -> adapterA().fetchCatalog()).isInstanceOf(CatalogFetchException.class);
    }

    @Test
    @DisplayName("items 가 null 이면 빈 목록으로 착각하지 않는다")
    void items가_null이면_빈_목록으로_착각하지_않는다() {
        // "items 없음" 과 "items 가 빈 배열" 은 다르다. 후자만 유효한 0개 스냅샷이다.
        stubA(json("{ }"));

        assertThatThrownBy(() -> adapterA().fetchCatalog())
                .isInstanceOf(CatalogFetchException.class)
                .hasMessageContaining("items is null");
    }

    @Test
    @DisplayName("빈 배열은 유효한 0개 스냅샷이다")
    void 빈_배열은_유효한_0개_스냅샷이다() {
        // 계약에 "0개는 올 수 없다" 가 없다.
        stubA(json("{ \"items\": [] }"));

        assertThat(adapterA().fetchCatalog()).isEmpty();
    }

    @Test
    @DisplayName("maxOccupancy 누락은 0으로 접지 않고 스냅샷 전체를 거부한다")
    void maxOccupancy_누락은_0으로_접지_않고_스냅샷_전체를_거부한다() {
        // 0 으로 접으면 "필드가 없었다" 가 "0명 수용" 으로 기록되어 진단이 흐려진다.
        // 그리고 이 항목만 빼고 진행하면 그 숙소가 "공급사에서 사라진 것" 으로 오해되어
        // 비활성화된다. 실패 하나가 멀쩡한 숙소를 검색에서 사라지게 만든다.
        stubA(json("""
                { "items": [ { "hotelCode": "A-10023", "hotelName": "Riverside",
                    "roomTypes": [ { "roomTypeCode": "DLX-TWN", "roomTypeName": "Deluxe Twin" } ] } ] }
                """));

        assertThatThrownBy(() -> adapterA().fetchCatalog())
                .isInstanceOf(CatalogFetchException.class)
                .hasMessageContaining("maxOccupancy");
    }

    @Test
    @DisplayName("객실 타입이 없는 숙소도 파싱된다")
    void 객실_타입이_없는_숙소도_파싱된다() {
        // 반대 방향 확인. roomTypes 가 없다고 거부하면 정상 상황을 막게 된다.
        stubA(json("{ \"items\": [ { \"hotelCode\": \"A-1\", \"hotelName\": \"H\" } ] }"));

        assertThatCode(() -> assertThat(adapterA().fetchCatalog().getFirst().roomTypes())
                        .isEmpty())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("응답이 오지 않으면 타임아웃으로 실패한다")
    void 응답이_오지_않으면_타임아웃으로_실패한다() {
        // 목록 조회가 무한 대기하면 스케줄러 스레드가 영원히 묶인다.
        stubA(json("{ \"items\": [] }").withFixedDelay(3_000));

        assertThatThrownBy(() -> adapterA().fetchCatalog()).isInstanceOf(CatalogFetchException.class);
    }
}
