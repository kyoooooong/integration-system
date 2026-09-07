package com.integration.stay.app.global.config;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 공급사 연동 설정.
 *
 * <p>값의 의미와 상호 관계는 {@code SupplierClientProperties} 에 있다. 여기서는
 * <b>설정이 실제로 주입됐는지</b>만 본다.
 *
 * <h2>왜 검증이 필요한가</h2>
 *
 * "환경변수가 없으면 기동이 실패한다" 를 전제로 prod 프로파일에 기본값을 두지 않았는데,
 * <b>확인해 보니 사실이 아니었다.</b>
 *
 * <p>{@code @Value} 는 해석할 수 없는 플레이스홀더를 만나면 예외를 던지지만,
 * {@code @ConfigurationProperties} 바인더는 기본적으로 그것을 무시하고
 * <b>{@code "${SUPPLIER_API_KEY}"} 라는 문자열 자체를 값으로 넣는다.</b>
 * 그 값은 비어 있지 않으므로 {@code @NotBlank} 도 통과한다.
 *
 * <p>결과는 조용한 오작동이다. 앱은 정상 기동하고 health 는 UP 을 보고하는데,
 * 공급사에는 잘못된 키가 나가 전부 인증 실패가 된다. 그 상태는 배포 직후가 아니라
 * 한참 뒤에 "왜 아무 상품도 안 나오지" 로 발견된다.
 *
 * <p>그래서 미해석 플레이스홀더를 직접 거부한다.
 * <b>잘못된 설정은 런타임에 이상하게 동작하는 것보다 기동에서 시끄럽게 실패하는 편이 낫다.</b>
 *
 * @param baseUrl 공급사 주소. Mock 은 별도 프로세스여야 한다 — 같은 서블릿 컨테이너에 두면
 *     무응답 모드의 sleep 이 우리 워커 스레드를 점유해서 "연동 타임아웃 미동작" 과
 *     "우리 자원 고갈" 을 구분할 수 없게 된다
 * @param apiKey 공급사 인증 키. 코드에 두지 않고 환경변수로 주입한다
 */
@Validated
@ConfigurationProperties(prefix = "supplier")
public record SupplierProperties(
        @NotBlank String baseUrl,
        @NotBlank String apiKey,
        @NotNull Duration connectTimeout,
        @NotNull Duration responseTimeout,
        @Positive int batchConcurrency,
        @Positive int bulkheadMaxConcurrentCalls,
        @Positive int maxConnections,
        @NotNull Duration pendingAcquireTimeout) {

    public SupplierProperties {
        requireResolved("supplier.base-url", baseUrl);
        requireResolved("supplier.api-key", apiKey);
    }

    /** {@code ${...}} 가 그대로 남아 있다면 그 환경변수가 주입되지 않은 것이다. */
    private static void requireResolved(String name, String value) {
        if (value != null && value.startsWith("${") && value.endsWith("}")) {
            throw new IllegalStateException(
                    "%s 가 해석되지 않았다: %s — 해당 환경변수를 주입해야 한다. "
                                    .formatted(name, value)
                            + "기본값 없이 조용히 기동하면 잘못된 값으로 외부를 호출하면서도 health 는 UP 을 보고한다");
        }
    }
}
