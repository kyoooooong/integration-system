package com.integration.stay.app.global.config;

import com.integration.stay.application.search.SearchResult;
import com.integration.stay.application.search.SearchTelemetry;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.domain.SupplierId;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 지표와 로그를 실제로 발행하는 유일한 지점.
 *
 * <p>레이블 설계의 기준은 <b>"이 레이블로 나뉜 두 줄이 서로 다른 행동을 부르는가"</b> 다.
 * 부르지 않는다면 카디널리티만 늘리는 레이블이다.
 */
@Component
class MicrometerSearchTelemetry implements SearchTelemetry {

    private static final Logger log = LoggerFactory.getLogger(MicrometerSearchTelemetry.class);

    private final MeterRegistry meterRegistry;

    MicrometerSearchTelemetry(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @Override
    public void adapterPipelineFailed(SupplierId supplierId, Throwable cause) {
        // 가용성을 위해 catch 하되 관측을 숨기지 않는다. 이 로그가 없으면 그냥 버그를 삼키는 catch 다.
        log.error("supplier pipeline failure supplier={}", supplierId.value(), cause);
        meterRegistry
                .counter("supplier_adapter_error_total", "supplier", supplierId.value())
                .increment();
    }

    @Override
    public void offersRejected(SupplierId supplierId, String reason, int count) {
        // 거부마다 WARN 을 찍으면 로그 홍수가 된다. 배치당 집계 한 줄만 남기고 상세는 지표로.
        log.warn("offers rejected supplier={} reason={} count={}", supplierId.value(), reason, count);
        meterRegistry
                .counter("stay_offer_rejected_total", "supplier", supplierId.value(), "reason", reason)
                .increment(count);
    }

    @Override
    public void supplierCallCompleted(SupplierId supplierId, String outcome, Duration duration) {
        meterRegistry
                .timer("supplier_call_duration_seconds", "supplier", supplierId.value(), "outcome", outcome)
                .record(duration);
    }

    @Override
    public void searchCompleted(SearchResult result) {
        for (SupplierOutcome outcome : result.outcomes()) {
            meterRegistry
                    .counter(
                            "supplier_search_total",
                            "supplier",
                            outcome.supplierId().value(),
                            "status",
                            outcome.status().name(),
                            // 실패 사유가 없는 정상 응답도 같은 지표에 남아야 성공률을 계산할 수 있다.
                            "reason",
                            outcome.failure().map(Enum::name).orElse("NONE"))
                    .increment();
        }
        int soldOut = result.soldOutCount();
        if (soldOut > 0) {
            // "결과가 비었다" 의 원인을 가른다. 만실이라 빈 것과 조회가 실패해서 빈 것은
            // 응답에서 구분되지만(partial 플래그), 만실 비율 자체는 지표로만 보인다.
            meterRegistry.counter("stay_offer_filtered_total", "reason", "SOLD_OUT").increment(soldOut);
        }
    }
}
