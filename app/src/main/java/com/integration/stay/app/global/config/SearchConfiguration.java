package com.integration.stay.app.global.config;

import com.integration.stay.application.catalog.CatalogPort;
import com.integration.stay.application.catalog.CatalogQuery;
import com.integration.stay.application.catalog.CatalogSnapshotStore;
import com.integration.stay.application.catalog.CatalogSyncService;
import com.integration.stay.application.search.SearchStaysService;
import com.integration.stay.application.search.SearchTelemetry;
import com.integration.stay.application.search.SupplierSearchPort;
import java.util.List;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * application 계층의 조립.
 *
 * <p>{@code SearchStaysService} 에 {@code @Service} 를 붙이지 않는다. application 모듈에
 * Spring 의존이 없기 때문이고, 그 대가로 이 파일이 필요하다. 어댑터 쪽에서는 반대로
 * 애노테이션을 유지한다 — 어댑터는 정의상 프레임워크와 붙는 계층이고, 거기서 애노테이션을
 * 빼면 순수성을 얻는 게 아니라 {@code List<SupplierSearchPort>} 자동 수집이라는 설계
 * 목표를 버리는 것이다.
 */
@Configuration
class SearchConfiguration {

    @Bean
    SearchStaysService searchStaysService(
            CatalogQuery catalogQuery, List<SupplierSearchPort> ports, SearchTelemetry telemetry) {
        return new SearchStaysService(catalogQuery, ports, telemetry);
    }

    @Bean
    CatalogSyncService catalogSyncService(List<CatalogPort> ports, CatalogSnapshotStore store) {
        return new CatalogSyncService(ports, store);
    }
}
