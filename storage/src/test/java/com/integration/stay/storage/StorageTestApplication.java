package com.integration.stay.storage;

import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;

/**
 * storage 모듈 테스트용 부트 설정.
 *
 * <p>DataSource 와 Flyway 를 직접 조립하지 않고 자동 설정을 태우는 이유는,
 * <b>애플리케이션 실행과 테스트가 같은 스키마 초기화 경로를 쓰게 하기 위해서</b>다.
 * 테스트에서만 Flyway.configure().migrate() 를 부르면, 실제 기동에서 마이그레이션이
 * 안 돌아도 테스트는 통과한다.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
class StorageTestApplication {}
