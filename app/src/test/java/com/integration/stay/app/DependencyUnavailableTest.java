package com.integration.stay.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.application.catalog.CatalogQuery;
import com.integration.stay.application.catalog.CatalogReadUnavailableException;
import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.domain.SupplierId;
import java.util.Collection;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.TestPropertySource;
import org.springframework.web.client.RestClient;

/**
 * DB 에 닿지 못할 때 클라이언트가 무엇을 받는가.
 *
 * <p>검색의 카탈로그 스냅샷 조회는 {@code Mono} 조립 <b>전에</b> 서블릿 스레드에서 동기
 * 실행된다. 따라서 이 실패는 리액티브 파이프라인의 부분 실패 처리를 거치지 않고
 * 곧장 예외 핸들러로 온다 — 다른 경로다.
 *
 * <p>기대값은 <b>503</b>이다. 500은 "재시도하지 마라"라는 뜻인데
 * 커넥션 풀 고갈이나 DB 재시작은 잠시 뒤 성공할 수 있는 일시적 문제다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"catalog.sync.initial-delay=1h", "spring.flyway.enabled=false"})
class DependencyUnavailableTest {

    @TestConfiguration
    static class BrokenCatalogConfiguration {

        @Bean
        @Primary
        CatalogQuery brokenCatalogQuery() {
            return new CatalogQuery() {
                @Override
                public CatalogSnapshot snapshot(Collection<SupplierId> registered) {
                    throw new CatalogReadUnavailableException("cannot reach catalog store", new IllegalStateException());
                }
            };
        }
    }

    @LocalServerPort
    private int port;

    @Test
    @DisplayName("카탈로그를 읽을 수 없으면 500 이 아니라 503 이다")
    void 카탈로그를_읽을_수_없으면_500이_아니라_503이다() {
        ResponseEntity<String> response = RestClient.create("http://localhost:" + port)
                .get()
                .uri("/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0")
                .retrieve()
                .onStatus(status -> true, (req, res) -> {})
                .toEntity(String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).contains("DEPENDENCY_UNAVAILABLE");
        // 내부 상세를 흘리지 않는다. 클라이언트가 그 문자열로 할 수 있는 일이 없다.
        assertThat(response.getBody()).doesNotContain("IllegalStateException").doesNotContain("jdbc");
    }
}
