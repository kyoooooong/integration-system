package com.integration.stay.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Composition Root.
 *
 * <p>어댑터 모듈을 {@code implementation} 으로 잡는다. {@code runtimeOnly} 로 막으면
 * {@code @Bean} 명시 조립이 불가능해지고 컴포넌트 스캔에만 의존하게 된다. 컨트롤러가
 * 어댑터를 import 하는 것은 ArchUnit 이 막는다 — 패키지 단위 예외가 가능하므로 빌드
 * configuration 보다 정밀하다.
 */
@SpringBootApplication(scanBasePackages = {"com.integration.stay.app", "com.integration.stay.storage"})
@EnableScheduling
public class StayIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(StayIntegrationApplication.class, args);
    }
}
