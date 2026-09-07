package com.integration.stay.storage.catalog;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * 테스트 전체가 공유하는 PostgreSQL 컨테이너.
 *
 * <p>JUnit 확장(@Testcontainers/@Container) 대신 정적 초기화로 띄운다. BOM 이 JUnit
 * Jupiter 를 6.0.3 으로 올렸고 testcontainers-junit-jupiter 는 별도 호환 축이라,
 * 검증 대상이 아닌 곳에서 깨질 이유를 만들지 않는다. 컨테이너는 JVM 종료 시 정리된다.
 *
 * <p>H2 가 아닌 이유: 멱등성의 핵심인 ON CONFLICT DO UPDATE 에 correctness 가 걸려
 * 있는데, H2 로 테스트하면 검증해야 할 제약 동작을 다른 DB 동작으로 테스트하게 된다.
 */
public abstract class PostgresContainerSupport {

    // patch 까지 pin 한다. 검증한 것과 다른 DB 로 테스트가 도는 일이 없어야 한다.
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:17.9-alpine"));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
