package com.integration.stay.storage.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * Flyway 가 애플리케이션 자동 설정 경로로 실제 PostgreSQL 에 스키마를 적용하는지 확인한다.
 * 이것이 통과해야 이후의 제약 조건 테스트들이 의미를 갖는다.
 */
@SpringBootTest
class SchemaMigrationTest extends PostgresContainerSupport {

    @Autowired
    private JdbcClient jdbcClient;

    @Test
    @DisplayName("Flyway 가 V1 을 적용한다")
    void Flyway가_V1을_적용한다() {
        List<String> applied = jdbcClient
                .sql("SELECT script FROM flyway_schema_history WHERE success ORDER BY installed_rank")
                .query(String.class)
                .list();

        assertThat(applied).contains("V1__catalog.sql");
    }

    @Test
    @DisplayName("세 테이블이 생성된다")
    void 세_테이블이_생성된다() {
        List<String> tables = jdbcClient
                .sql("SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'")
                .query(String.class)
                .list();

        assertThat(tables)
                .contains("supplier_property", "supplier_room_type", "supplier_catalog_state");
    }

    @Test
    @DisplayName("객실 타입 유일성 범위가 숙소 안으로 제한된다")
    void 객실_타입_유일성_범위가_숙소_안으로_제한된다() {
        // 인덱스 정의의 첫 컬럼이 property_id 인 것이 "객실 코드는 해당 숙소 안에서만
        // 유일하다" 는 계약의 표현이다. 이 순서가 뒤집히면 서로 다른 숙소의 같은
        // STD-DBL 이 충돌한다.
        String definition = jdbcClient
                .sql("SELECT indexdef FROM pg_indexes WHERE indexname = 'uk_supplier_room_type'")
                .query(String.class)
                .single();

        assertThat(definition).contains("(property_id, supplier_room_type_code)");
    }

    @Test
    @DisplayName("검색 경로용 부분 인덱스가 활성 행만 덮는다")
    void 검색_경로용_부분_인덱스가_활성_행만_덮는다() {
        List<String> partial = jdbcClient
                .sql("SELECT indexdef FROM pg_indexes WHERE indexname IN "
                        + "('ix_supplier_property_active', 'ix_supplier_room_type_active')")
                .query(String.class)
                .list();

        assertThat(partial).hasSize(2).allMatch(def -> def.contains("WHERE active"));
    }
}
