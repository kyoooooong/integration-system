package com.integration.stay.app.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.domain.SupplierId;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MicrometerSearchTelemetryTest {

    @Test
    @DisplayName("공급사 호출 시간은 supplier와 outcome 라벨로 기록된다")
    void 공급사_호출_시간은_supplier와_outcome_라벨로_기록된다() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MicrometerSearchTelemetry telemetry = new MicrometerSearchTelemetry(registry);

        telemetry.supplierCallCompleted(new SupplierId("A"), "TIMEOUT", Duration.ofMillis(250));

        Timer timer = registry.find("supplier_call_duration_seconds")
                .tag("supplier", "A")
                .tag("outcome", "TIMEOUT")
                .timer();
        assertThat(timer).as("supplier_call_duration_seconds timer").isNotNull();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(250);
    }
}
