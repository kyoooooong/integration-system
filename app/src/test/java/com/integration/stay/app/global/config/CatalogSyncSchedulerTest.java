package com.integration.stay.app.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.integration.stay.application.catalog.CatalogSyncResult;
import com.integration.stay.application.catalog.CatalogSyncService;
import com.integration.stay.application.catalog.SupplierSyncOutcome;
import com.integration.stay.domain.SupplierId;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 카탈로그 개수 게이지가 동기화마다 실제로 갱신되는지 본다.
 *
 * <p>이 테스트는 <b>실행 중인 앱의 로그 한 줄에서 시작했다.</b>
 * {@code This Gauge has been already registered ... the registration will be ignored}.
 * 경고일 뿐이라 지나칠 수 있었지만, 무시된 것이 두 번째 등록이라면 값이 갱신되지 않는다는 뜻이다.
 *
 * <p>확인해 보니 두 가지가 동시에 잘못돼 있었다.
 *
 * <pre>
 * 1. meterRegistry.gauge(name, tags, number) 를 동기화마다 호출 → 두 번째부터 무시된다.
 *    값이 첫 동기화 시점에 고정된다.
 * 2. 그 오버로드는 전달한 Number 를 약한 참조로 잡는다 → 박싱된 정수가 수거되면 NaN.
 * </pre>
 *
 * <p>둘 다 예외가 나지 않는다. 대시보드에는 그럴듯한 숫자가 계속 보인다.
     * "카탈로그 급감은 이 게이지로 본다" 는 우리 문서의 설명이 그대로 거짓이 된다.
 *
 * <p>그래서 값을 강하게 붙들고 등록은 한 번만 하도록 고쳤고, 이 테스트로 고정했다.
 * 고치기 전 코드로 돌리면 두 번째 단언에서 실패한다.
 */
class CatalogSyncSchedulerTest {

    private static final SupplierId A = new SupplierId("A");

    private static SupplierSyncOutcome succeeded(int propertiesApplied) {
        return new SupplierSyncOutcome.Succeeded(A, new CatalogSyncResult(propertiesApplied, 0, 0, 0));
    }

    private static double gaugeValue(SimpleMeterRegistry registry) {
        Gauge gauge = registry.find("catalog_snapshot_size").tag("supplier", "A").gauge();
        assertThat(gauge).as("catalog_snapshot_size 게이지가 등록되지 않았다").isNotNull();
        return gauge.value();
    }

    @Test
    @DisplayName("카탈로그 개수 게이지는 동기화마다 갱신된다")
    void 카탈로그_개수_게이지는_동기화마다_갱신된다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CatalogSyncService service = mock(CatalogSyncService.class);
        CatalogSyncScheduler scheduler = new CatalogSyncScheduler(service, registry);

        when(service.syncAll()).thenReturn(List.of(succeeded(2000)));
        scheduler.syncCatalogs();
        assertThat(gaugeValue(registry)).isEqualTo(2000);

        // 급감이 지표에 보여야 한다. 이 단언이 이 테스트의 존재 이유다 —
        // 재등록 방식에서는 여기서 여전히 2000 이 나온다.
        when(service.syncAll()).thenReturn(List.of(succeeded(3)));
        scheduler.syncCatalogs();
        assertThat(gaugeValue(registry)).isEqualTo(3);
    }

    @Test
    @DisplayName("게이지는 공급사마다 따로 등록된다")
    void 게이지는_공급사마다_따로_등록된다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CatalogSyncService service = mock(CatalogSyncService.class);
        CatalogSyncScheduler scheduler = new CatalogSyncScheduler(service, registry);

        when(service.syncAll())
                .thenReturn(List.of(
                        succeeded(10),
                        new SupplierSyncOutcome.Succeeded(
                                new SupplierId("B"), new CatalogSyncResult(20, 0, 0, 0))));
        scheduler.syncCatalogs();

        assertThat(gaugeValue(registry)).isEqualTo(10);
        assertThat(registry.find("catalog_snapshot_size").tag("supplier", "B").gauge().value())
                .isEqualTo(20);
    }

    @Test
    @DisplayName("마지막 성공 이후 경과 시간 게이지가 등록된다")
    void 마지막_성공_이후_경과_시간_게이지가_등록된다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CatalogSyncService service = mock(CatalogSyncService.class);
        CatalogSyncScheduler scheduler = new CatalogSyncScheduler(service, registry);

        when(service.syncAll()).thenReturn(List.of(succeeded(2000)));
        scheduler.syncCatalogs();

        Gauge gauge = registry.find("catalog_last_success_age_seconds").tag("supplier", "A").gauge();
        assertThat(gauge).as("catalog_last_success_age_seconds 게이지가 등록되지 않았다").isNotNull();
        assertThat(gauge.value()).isGreaterThanOrEqualTo(0D);
    }

    @Test
    @DisplayName("동기화가 실패한 공급사는 게이지를 남기지 않는다")
    void 동기화가_실패한_공급사는_게이지를_남기지_않는다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CatalogSyncService service = mock(CatalogSyncService.class);
        CatalogSyncScheduler scheduler = new CatalogSyncScheduler(service, registry);

        when(service.syncAll())
                .thenReturn(List.of(new SupplierSyncOutcome.Failed(A, new IllegalStateException("boom"))));
        scheduler.syncCatalogs();

        // 실패를 0 으로 기록하면 "공급사가 숙소를 다 내렸다" 와 구분되지 않는다.
        assertThat(registry.find("catalog_snapshot_size").gauges()).isEmpty();
        assertThat(registry.find("catalog_sync_failure_total").counter().count()).isEqualTo(1);
    }
}
