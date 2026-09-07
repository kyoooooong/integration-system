package com.integration.stay.app.global.config;

import com.integration.stay.application.catalog.CatalogPort;
import com.integration.stay.application.search.SearchTelemetry;
import com.integration.stay.application.search.SupplierSearchPort;
import com.integration.stay.domain.SupplierId;
import com.integration.stay.supplier.SupplierClientProperties;
import com.integration.stay.supplier.SupplierIds;
import com.integration.stay.supplier.a.ACatalogAdapter;
import com.integration.stay.supplier.a.ASearchAdapter;
import com.integration.stay.supplier.b.BCatalogAdapter;
import com.integration.stay.supplier.b.BSearchAdapter;
import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

/**
 * Composition Root. 어댑터를 명시적으로 조립한다.
 *
 * <p>공급사 C 를 추가할 때 바뀌는 곳은 여기와 supplier-client 아래의 DTO·어댑터·Normalizer,
 * 그리고 {@code SupplierIds} 상수 한 줄이다. domain 전체와 검색 오케스트레이션, 카탈로그
 * 동기화 로직, API 계약, 기존 어댑터는 바뀌지 않는다.
 *
 * <p>완전한 OCP("어떤 파일도 수정하지 않는다")를 주장하지 않는다. 공급사 어댑터는 컴파일과
 * 재배포가 필요한 <b>정적 확장</b>이지 런타임 플러그인 시스템이 아니다. 지켜야 할 것은
 * 오케스트레이션과 도메인 규칙이 공급사 추가에 영향받지 않는 것이고, ArchUnit 이 강제한다.
 */
@Configuration
@EnableConfigurationProperties(SupplierProperties.class)
class SupplierClientConfiguration {

    @Bean
    SupplierClientProperties supplierClientProperties(SupplierProperties properties) {
        // 값 사이의 불변식은 이 생성자가 검사한다. 설정 오타로 포화가 엉뚱한 계층에서
        // 드러나기 시작하면 지표를 아무리 봐도 원인을 알 수 없으므로, 기동을 실패시킨다.
        return new SupplierClientProperties(
                properties.connectTimeout(),
                properties.responseTimeout(),
                properties.batchConcurrency(),
                properties.bulkheadMaxConcurrentCalls(),
                properties.maxConnections(),
                properties.pendingAcquireTimeout());
    }

    /**
     * <b>공급사별로 커넥션 풀을 분리한다.</b>
     *
     * <p>bulkhead 를 공급사별로 나눈 논리가 전송 계층에도 그대로 적용된다 — 하나의 풀을
     * 공유하면 A 가 느려져 커넥션을 붙잡고 있을 때 B 의 몫이 사라진다. 그러면 애플리케이션
     * 계층에서 애써 나눈 격리가 자원 계층에서 무너진다.
     *
     * <p>풀을 나누면 {@code bulkheadMaxConcurrentCalls < maxConnections} 불변식이
     * <b>공급사 단위로 실제 성립한다.</b> 공유 풀이었다면 이 비교는 공급사 수를 모르는 채로
     * 하는 무의미한 비교가 된다.
     *
     * <p><b>기본값을 그대로 쓰지 않는 이유</b>
     * <ul>
     *   <li>기본 풀 크기는 {@code max(availableProcessors, 8) × 2} 라 <b>호스트 CPU 수에
     *       따라 달라진다.</b> "동시 몇 건까지 견디는가" 가 배포 환경마다 달라지면 근거가 될 수 없다
     *   <li>기본 획득 대기는 45초로 우리 응답 타임아웃보다 훨씬 길다. 그대로 두면 풀이
     *       고갈됐을 때 획득 대기 중에 응답 타임아웃이 먼저 터지고, 그 실패가
     *       <b>공급사 타임아웃으로 기록된다.</b> 원인은 우리 쪽인데 지표는 공급사를 가리킨다
     * </ul>
     *
     * <p>성능 튜닝이 아니라 <b>실패 귀속의 정확성과 결정성</b>을 위한 설정이다.
     *
     * <p>같은 공급사의 카탈로그 조회도 이 클라이언트를 함께 쓴다. 스케줄러에서 순차로
     * 한 번에 하나씩만 호출하므로 검색 경로의 몫을 의미 있게 잠식하지 않는다.
     */
    private static WebClient webClientFor(SupplierId supplierId, SupplierProperties properties) {
        ConnectionProvider connectionProvider = ConnectionProvider.builder("supplier-" + supplierId.value())
                .maxConnections(properties.maxConnections())
                .pendingAcquireTimeout(properties.pendingAcquireTimeout())
                .build();
        HttpClient httpClient = HttpClient.create(connectionProvider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.connectTimeout().toMillis());
        return WebClient.builder()
                .baseUrl(properties.baseUrl())
                .defaultHeader("X-Api-Key", properties.apiKey())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    @Bean
    WebClient supplierAWebClient(SupplierProperties properties) {
        return webClientFor(SupplierIds.A, properties);
    }

    @Bean
    WebClient supplierBWebClient(SupplierProperties properties) {
        return webClientFor(SupplierIds.B, properties);
    }

    @Bean
    SupplierSearchPort aSearchPort(
            WebClient supplierAWebClient, SupplierClientProperties clientProperties, SearchTelemetry telemetry) {
        return new ASearchAdapter(supplierAWebClient, clientProperties, telemetry);
    }

    @Bean
    SupplierSearchPort bSearchPort(
            WebClient supplierBWebClient, SupplierClientProperties clientProperties, SearchTelemetry telemetry) {
        return new BSearchAdapter(supplierBWebClient, clientProperties, telemetry);
    }

    @Bean
    CatalogPort aCatalogPort(WebClient supplierAWebClient, SupplierClientProperties clientProperties) {
        return new ACatalogAdapter(supplierAWebClient, clientProperties);
    }

    @Bean
    CatalogPort bCatalogPort(WebClient supplierBWebClient, SupplierClientProperties clientProperties) {
        return new BCatalogAdapter(supplierBWebClient, clientProperties);
    }
}
