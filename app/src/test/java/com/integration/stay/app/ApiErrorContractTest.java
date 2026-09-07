package com.integration.stay.app;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * 오류 응답 계약.
 *
 * <p>이 테스트가 존재하는 이유는 <b>잡지 않은 예외가 조용히 500 이 되기 때문</b>이다.
 * catch-all 이 있으면 어떤 실수도 응답은 나가므로, 상태 코드가 틀린 것을 아무도 모른다.
 * 실제로 지원하지 않는 메서드와 없는 경로가 둘 다 500 으로 나가고 있었다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"catalog.sync.initial-delay=1h", "spring.flyway.enabled=false"})
class ApiErrorContractTest {

    private static final String SEARCH = "/api/v1/stays/search";
    private static final String VALID_QUERY = "?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0";

    @LocalServerPort
    private int port;

    private ResponseEntity<String> call(HttpMethod method, String uri) {
        return RestClient.create("http://localhost:" + port)
                .method(method)
                .uri(uri)
                .retrieve()
                .onStatus(status -> true, (req, res) -> {})
                .toEntity(String.class);
    }

    @Test
    @DisplayName("지원하지 않는 메서드는 500 이 아니라 405 다")
    void 지원하지_않는_메서드는_500이_아니라_405다() {
        // 클라이언트가 메서드를 잘못 썼는데 "우리 서버가 고장났다" 고 답하면
        // 클라이언트는 재시도하고 우리는 5xx 알람을 받는다. 둘 다 헛수고다.
        ResponseEntity<String> response = call(HttpMethod.POST, SEARCH + VALID_QUERY);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).contains("METHOD_NOT_ALLOWED");
    }

    @Test
    @DisplayName("없는 경로는 500 이 아니라 404 다")
    void 없는_경로는_500이_아니라_404다() {
        ResponseEntity<String> response = call(HttpMethod.GET, "/api/v1/does-not-exist");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("검증 실패는 필드별로 무엇이 왜 틀렸는지 알려준다")
    void 검증_실패는_필드별로_무엇이_왜_틀렸는지_알려준다() {
        // 메시지를 이어 붙인 문자열은 클라이언트가 파싱할 수 없다.
        ResponseEntity<String> response =
                call(HttpMethod.GET, SEARCH + "?checkIn=2026-09-01&checkOut=2026-09-04&adults=0&children=-1");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody())
                .contains("INVALID_PARAMETER")
                .contains("\"errors\"")
                .contains("adults")
                .contains("children");
    }

    @Test
    @DisplayName("타입이 틀리면 기대 형식만 알려주고 내부 타입명을 흘리지 않는다")
    void 타입이_틀리면_기대_형식만_알려주고_내부_타입명을_흘리지_않는다() {
        ResponseEntity<String> response =
                call(HttpMethod.GET, SEARCH + "?checkIn=notadate&checkOut=2026-09-04&adults=2&children=0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("checkIn").contains("LocalDate");
        // Spring 기본 변환 실패 메시지에는 내부 구조가 섞여 있다.
        assertThat(response.getBody()).doesNotContain("java.lang.String").doesNotContain("ConversionFailed");
    }

    @Test
    @DisplayName("필수 파라미터 누락은 어느 것이 빠졌는지 알려준다")
    void 필수_파라미터_누락은_어느_것이_빠졌는지_알려준다() {
        ResponseEntity<String> response =
                call(HttpMethod.GET, SEARCH + "?checkIn=2026-09-01&adults=2&children=0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("checkOut");
    }

    @Test
    @DisplayName("검증 메시지는 클라이언트 로케일에 따라 달라지지 않는다")
    void 검증_메시지는_클라이언트_로케일에_따라_달라지지_않는다() {
        // 기본 메시지를 그대로 쓰면 Accept-Language 에 따라 언어가 바뀐다.
        // 응답 본문이 요청 헤더에 따라 달라지면 그것은 계약이 아니다.
        String uri = SEARCH + "?checkIn=2026-09-01&checkOut=2026-09-04&adults=0&children=0";
        String korean = withLocale("ko-KR", uri);
        String english = withLocale("en-US", uri);
        String japanese = withLocale("ja-JP", uri);

        assertThat(korean).isEqualTo(english).isEqualTo(japanese);
        assertThat(korean).contains("must be at least 1");
    }

    private String withLocale(String language, String uri) {
        return RestClient.create("http://localhost:" + port)
                .get()
                .uri(uri)
                .header("Accept-Language", language)
                .retrieve()
                .onStatus(status -> true, (req, res) -> {})
                .toEntity(String.class)
                .getBody();
    }

    @Test
    @DisplayName("잘못된 날짜 범위는 도메인 검증이 잡고 400 이다")
    void 잘못된_날짜_범위는_도메인_검증이_잡고_400이다() {
        ResponseEntity<String> response =
                call(HttpMethod.GET, SEARCH + "?checkIn=2026-09-04&checkOut=2026-09-01&adults=2&children=0");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("INVALID_DATE_RANGE");
    }
}
