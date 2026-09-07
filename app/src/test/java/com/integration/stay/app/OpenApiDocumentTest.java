package com.integration.stay.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * API 문서가 실제로 생성되는지, 그리고 그 내용이 계약과 맞는지 본다.
 *
 * <p>의존성이 붙어 컴파일되고 컨텍스트가 뜨는 것과, 문서가 실제로 나오는 것은 다르다.
 * Spring Boot 4 에 맞는 springdoc 3.x 를 쓰더라도 "의도" 는 증거가 아니므로
 * 엔드포인트를 직접 호출해 확인한다.
 *
 * <p>문자열 포함 검사만으로는 부족하다는 것을 Swagger UI 를 직접 열어 보고 알았다.
 * 네 파라미터가 문서에 <b>들어 있기는 했지만</b> 쿼리 파라미터가 아니라 이름 없는 객체
 * 하나로 묶여 있었고, 예시 값은 우리가 400 으로 거부하는 요청이었다.
 * 그래서 이 테스트는 "어디에 어떤 모양으로" 들어 있는지까지 본다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {
    "catalog.sync.initial-delay=1h",
    // 이 테스트는 DB 와 공급사를 쓰지 않는다. Flyway 만 끄면 DataSource 는 lazy 라 뜬다.
    "spring.flyway.enabled=false",
    "spring.sql.init.mode=never"
})
class OpenApiDocumentTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @LocalServerPort
    private int port;

    private JsonNode document() {
        String body = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/v3/api-docs")
                .retrieve()
                .body(String.class);
        assertThat(body).isNotNull();
        try {
            return MAPPER.readTree(body);
        } catch (Exception e) {
            throw new AssertionError("OpenAPI 문서가 JSON 이 아니다", e);
        }
    }

    private JsonNode searchOperation() {
        JsonNode search = document().path("paths").path("/api/v1/stays/search").path("get");
        assertThat(search.isMissingNode()).as("검색 엔드포인트가 문서에 없다").isFalse();
        return search;
    }

    @Test
    @DisplayName("네 검색 조건이 이름 있는 쿼리 파라미터로 문서화된다")
    void 네_검색_조건이_이름_있는_쿼리_파라미터로_문서화된다() {
        List<String> queryParams = new ArrayList<>();
        for (JsonNode p : searchOperation().path("parameters")) {
            if ("query".equals(p.path("in").asString())) {
                queryParams.add(p.path("name").asString());
            }
        }

        assertThat(queryParams).containsExactlyInAnyOrder("checkIn", "checkOut", "adults", "children");
    }

    @Test
    @DisplayName("문서의 예시 요청은 실제로 우리 계약을 통과하는 값이다")
    void 문서의_예시_요청은_실제로_우리_계약을_통과하는_값이다() {
        LocalDate checkIn = null;
        LocalDate checkOut = null;
        for (JsonNode p : searchOperation().path("parameters")) {
            JsonNode example = p.path("schema").path("example");
            switch (p.path("name").asString()) {
                case "checkIn" -> checkIn = LocalDate.parse(example.asString());
                case "checkOut" -> checkOut = LocalDate.parse(example.asString());
                case "adults" -> assertThat(example.asInt()).isGreaterThanOrEqualTo(1);
                case "children" -> assertThat(example.asInt()).isGreaterThanOrEqualTo(0);
                default -> throw new AssertionError("모르는 파라미터: " + p.path("name").asString());
            }
        }

        assertThat(checkIn).isNotNull();
        assertThat(checkOut).isNotNull();
        // 체크아웃은 숙박일에 포함되지 않으므로 체크인보다 뒤여야 한다. 문서가 이것을 어기면
        // 읽는 사람이 그대로 실행했을 때 400 을 받는다.
        assertThat(checkOut).isAfter(checkIn);
    }

    @Test
    @DisplayName("클라이언트가 분기해야 하는 상태 코드가 모두 문서에 있다")
    void 클라이언트가_분기해야_하는_상태_코드가_모두_문서에_있다() {
        JsonNode responses = searchOperation().path("responses");

        assertThat(responses.propertyNames()).contains("200", "400", "502", "503", "504");
    }

    @Test
    @DisplayName("응답 스키마가 record 에서 유도된다")
    void 응답_스키마가_record_에서_유도된다() {
        JsonNode schemas = document().path("components").path("schemas");

        assertThat(schemas.propertyNames()).contains("SearchResponse", "ErrorResponse");
        assertThat(schemas.path("SearchResponse").path("properties").propertyNames())
                .contains("partial", "suppliers", "items");
        assertThat(schemas.path("StayItemView").path("properties").propertyNames())
                .contains("propertyId", "roomTypeId", "availableRooms", "price");
    }

    @Test
    @DisplayName("5xx 본문 스키마가 실제로 나가는 타입과 같다")
    void 오xx_본문_스키마가_실제로_나가는_타입과_같다() {
        JsonNode responses = searchOperation().path("responses");

        // 공급사를 호출해 봤으면 공급사별 상태를 말할 수 있으므로 SearchResponse 다.
        // 이것을 ErrorResponse 라고 적어 두면 클라이언트는 재시도 대상을 고를 수 없다.
        // 실제로 한 번 잘못 적었고, 그때 컴파일도 테스트도 통과했다.
        assertThat(schemaRefOf(responses, "502")).isEqualTo("SearchResponse");
        assertThat(schemaRefOf(responses, "504")).isEqualTo("SearchResponse");
        // 호출조차 못 한 경우가 있으므로 503 만 두 모양이다.
        assertThat(oneOfRefsOf(responses, "503")).containsExactlyInAnyOrder("SearchResponse", "ErrorResponse");
        // 요청이 잘못됐으면 공급사 이야기를 할 게 없다.
        assertThat(schemaRefOf(responses, "400")).isEqualTo("ErrorResponse");
        // 검색 파이프라인 내부 실패는 공급사별 상태를 담아 SearchResponse 로 나갈 수 있고,
        // 전역 예외 처리의 예상 밖 오류는 ErrorResponse 로 나간다.
        assertThat(oneOfRefsOf(responses, "500")).containsExactlyInAnyOrder("SearchResponse", "ErrorResponse");
    }

    private static String schemaRefOf(JsonNode responses, String status) {
        return simpleName(schemaOf(responses, status).path("$ref").asString());
    }

    private static List<String> oneOfRefsOf(JsonNode responses, String status) {
        List<String> refs = new ArrayList<>();
        for (JsonNode one : schemaOf(responses, status).path("oneOf")) {
            refs.add(simpleName(one.path("$ref").asString()));
        }
        return refs;
    }

    private static JsonNode schemaOf(JsonNode responses, String status) {
        JsonNode content = responses.path(status).path("content");
        String mediaType = content.propertyNames().iterator().next();
        return content.path(mediaType).path("schema");
    }

    private static String simpleName(String ref) {
        return ref.substring(ref.lastIndexOf('/') + 1);
    }
}
