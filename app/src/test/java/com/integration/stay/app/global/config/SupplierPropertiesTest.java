package com.integration.stay.app.global.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 설정 누락이 조용한 오작동이 되지 않는지 본다.
 *
 * <p>이 테스트가 존재하는 이유는 <b>처음 가정이 틀렸기 때문</b>이다.
 * "prod 프로파일에 기본값을 두지 않으면 환경변수가 없을 때 기동이 실패한다" 고 생각했는데,
 * 실제로 컨테이너로 띄워 보니 정상 기동했다.
 *
 * <p>{@code @Value} 는 미해석 플레이스홀더에 예외를 던지지만
 * {@code @ConfigurationProperties} 바인더는 무시하고 {@code "${SUPPLIER_API_KEY}"} 라는
 * 문자열을 그대로 값으로 넣는다. 비어 있지 않으니 {@code @NotBlank} 도 통과한다.
 * 그러면 앱은 health UP 을 보고하면서 잘못된 키로 공급사를 호출한다.
 */
class SupplierPropertiesTest {

    private static SupplierProperties with(String baseUrl, String apiKey) {
        return new SupplierProperties(
                baseUrl, apiKey, Duration.ofSeconds(1), Duration.ofSeconds(2), 4, 16, 24, Duration.ofMillis(500));
    }

    @Test
    @DisplayName("미해석 플레이스홀더는 기동을 실패시킨다")
    void 미해석_플레이스홀더는_기동을_실패시킨다() {
        assertThatThrownBy(() -> with("http://localhost:9090", "${SUPPLIER_API_KEY}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supplier.api-key");

        assertThatThrownBy(() -> with("${SUPPLIER_BASE_URL}", "key"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("supplier.base-url");
    }

    @Test
    @DisplayName("정상 값은 통과한다")
    void 정상_값은_통과한다() {
        // 반대 방향 확인. 없으면 "모든 값을 거부한다" 로도 위 테스트가 통과한다.
        assertThatCode(() -> with("http://localhost:9090", "real-key")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("플레이스홀더처럼 보이지만 아닌 값은 통과한다")
    void 플레이스홀더처럼_보이지만_아닌_값은_통과한다() {
        // 여는 괄호만 있거나 닫는 괄호만 있는 것은 미해석 플레이스홀더가 아니다.
        assertThatCode(() -> with("http://localhost:9090", "${partial")).doesNotThrowAnyException();
        assertThatCode(() -> with("http://localhost:9090", "key}")).doesNotThrowAnyException();
    }
}
