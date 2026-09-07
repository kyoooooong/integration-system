package com.integration.stay.app.global.config;

import com.integration.stay.application.catalog.CatalogSyncService;
import com.integration.stay.application.catalog.SupplierSyncOutcome;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 카탈로그 동기화 트리거.
 *
 * <p>기동 시 1회 + 주기 실행이다. 검색마다 호출하면 고객 지연이 나빠지고 외부 호출량이
 * 폭증한다. 기동 시에만 하면 신규 숙소가 재기동 전까지 영원히 반영되지 않는다.
 * 정적 데이터가 느리게라도 실제로 변한다는 lifecycle 을, 검색 지연을 늘리지 않으면서
 * 가장 싼 비용으로 반영하는 선택이다.
 *
 * <p><b>{@code fixedRate} 가 아니라 {@code fixedDelay} 다.</b> 목적이 "정각 실행" 이
 * 아니라 "이전 갱신이 끝난 뒤 일정 시간 뒤 다시 갱신" 이다. 이전 완료 시점부터 재므로
 * 실행이 겹치지 않는다.
 *
 * <p><b>{@code @Async} 를 붙이지 않는다.</b> 붙이면 이 메서드가 즉시 반환하고 실제
 * 동기화는 뒤에서 계속 돌아 비중첩 보장이 깨진다.
 *
 * <p>단일 인스턴스 + 동기 실행 + fixedDelay, 이 셋이 같이 있어야 advisory lock 없이
 * 안전하다. 셋 중 하나가 바뀌면 락이 필요해진다.
 */
@Component
class CatalogSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(CatalogSyncScheduler.class);

    private final CatalogSyncService catalogSyncService;
    private final MeterRegistry meterRegistry;

    /**
     * 공급사별 게이지가 읽는 값. <b>이 맵이 없으면 게이지가 동작하지 않는다.</b>
     *
     * <p>{@code meterRegistry.gauge(name, tags, number)} 를 동기화마다 부르면 두 가지가
     * 동시에 잘못된다. 첫째, Micrometer 는 같은 이름·태그의 두 번째 등록을 무시하므로
     * 값이 첫 동기화 시점에 고정된다. 둘째, 그 오버로드는 전달한 {@code Number} 를
     * <b>약한 참조</b>로 잡으므로 박싱된 정수가 수거되면 게이지가 NaN 을 보고한다.
     *
     * <p>둘 다 예외가 나지 않는다. 로그에 경고 한 줄이 남을 뿐이고, 대시보드에는
     * 그럴듯한 숫자가 계속 보인다. "카탈로그 급감을 이 게이지로 본다" 는 우리 문서가
     * 그대로 거짓이 된다. 그래서 값을 여기서 강하게 붙들고 등록은 한 번만 한다.
     */
    private final Map<String, AtomicInteger> snapshotSizes = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> lastSuccessEpochMillis = new ConcurrentHashMap<>();

    CatalogSyncScheduler(CatalogSyncService catalogSyncService, MeterRegistry meterRegistry) {
        this.catalogSyncService = catalogSyncService;
        this.meterRegistry = meterRegistry;
    }

    private void recordSnapshotSize(String supplier, int propertiesApplied) {
        snapshotSizes
                .computeIfAbsent(supplier, key -> {
                    AtomicInteger holder = new AtomicInteger();
                    Gauge.builder("catalog_snapshot_size", holder, AtomicInteger::get)
                            .tag("supplier", key)
                            .description("마지막 동기화에서 적용된 활성 숙소 수")
                            .register(meterRegistry);
                    return holder;
                })
                .set(propertiesApplied);
    }

    private void recordLastSuccess(String supplier) {
        lastSuccessEpochMillis
                .computeIfAbsent(supplier, key -> {
                    AtomicLong holder = new AtomicLong();
                    Gauge.builder("catalog_last_success_age_seconds", holder, this::lastSuccessAgeSeconds)
                            .tag("supplier", key)
                            .description("마지막 카탈로그 동기화 성공 이후 지난 시간")
                            .register(meterRegistry);
                    return holder;
                })
                .set(System.currentTimeMillis());
    }

    private double lastSuccessAgeSeconds(AtomicLong holder) {
        long lastSuccess = holder.get();
        if (lastSuccess == 0L) {
            return Double.NaN;
        }
        return Math.max(0D, (System.currentTimeMillis() - lastSuccess) / 1000D);
    }

    // initialDelay 0 으로 기동 시 1회와 주기 실행을 하나의 트리거로 합친다.
    // 테스트에서만 이 값을 늘려 스케줄러와 명시 호출의 경합을 없앤다.
    @Scheduled(
            initialDelayString = "${catalog.sync.initial-delay:0}",
            fixedDelayString = "${catalog.sync.interval}")
    void syncCatalogs() {
        for (SupplierSyncOutcome outcome : catalogSyncService.syncAll()) {
            switch (outcome) {
                case SupplierSyncOutcome.Succeeded succeeded -> {
                    log.info(
                            "catalog sync ok supplier={} properties={} roomTypes={} "
                                    + "deactivatedProperties={} deactivatedRoomTypes={}",
                            succeeded.supplierId().value(),
                            succeeded.result().propertiesApplied(),
                            succeeded.result().roomTypesApplied(),
                            succeeded.result().propertiesDeactivated(),
                            succeeded.result().roomTypesDeactivated());
                    // 개수 급감은 DB 파생 컬럼이 아니라 이 게이지로 본다.
                    // 파생 상태를 저장하면 다음 동기화가 덮어써서 "일어났다" 는 사실이 사라진다.
                    recordSnapshotSize(
                            succeeded.supplierId().value(),
                            succeeded.result().propertiesApplied());
                    recordLastSuccess(succeeded.supplierId().value());
                }
                // 검색만 공급사 격리를 하고 카탈로그에서 A 가 B 를 죽이면 일관성이 없다.
                case SupplierSyncOutcome.Failed failed -> {
                    log.error("catalog sync failed supplier={}", failed.supplierId().value(), failed.cause());
                    meterRegistry
                            .counter("catalog_sync_failure_total", "supplier", failed.supplierId().value())
                            .increment();
                }
            }
        }
    }
}
