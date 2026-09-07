package com.integration.stay.mock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 공급사 흉내용 별도 프로세스 (:9090).
 *
 * <p><b>본 애플리케이션과 같은 서블릿 컨테이너에 두지 않는다.</b> 무응답 모드의
 * {@code Thread.sleep} 이 우리 워커 스레드를 점유하면 "연동 타임아웃이 동작하지 않음" 과
 * "우리 자원이 고갈됨" 을 구분할 수 없게 되고, 검증 절차가 자기 자신을 검증하는 꼴이 된다.
 *
 * <p>제품 코드가 아니라 검증 도구다. 코드 품질과 데이터 다양성에 시간을 쓰지 않는다.
 * 상태 있는 시나리오(N번째 호출만 실패, malformed JSON 등)는 WireMock 스텁에만 넣는다.
 */
@SpringBootApplication
public class MockSupplierApplication {

    public static void main(String[] args) {
        SpringApplication.run(MockSupplierApplication.class, args);
    }
}
