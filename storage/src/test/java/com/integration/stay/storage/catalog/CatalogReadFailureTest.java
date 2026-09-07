package com.integration.stay.storage.catalog;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integration.stay.application.catalog.CatalogReadUnavailableException;
import com.integration.stay.domain.SupplierId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * 저장소 기술에 묶인 예외가 상위 계층으로 새지 않는지 본다.
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. 닿을 수 없는 DataSource 를 직접 만들어
 * 번역만 확인한다 — 컨테이너를 띄워 놓고 끄는 것보다 결정적이고 빠르다.
 */
class CatalogReadFailureTest {

    @Test
    @DisplayName("DB 에 닿지 못하면 애플리케이션 개념의 예외로 번역된다")
    void DB에_닿지_못하면_애플리케이션_개념의_예외로_번역된다() {
        // 아무도 듣고 있지 않은 포트. 커넥션 획득이 실패한다.
        DriverManagerDataSource unreachable = new DriverManagerDataSource();
        unreachable.setDriverClassName("org.postgresql.Driver");
        unreachable.setUrl("jdbc:postgresql://localhost:1/nowhere?connectTimeout=1&socketTimeout=1");
        unreachable.setUsername("none");
        unreachable.setPassword("none");

        var query = new JdbcCatalogQuery(JdbcClient.create(unreachable));

        assertThatThrownBy(() -> query.snapshot(List.of(new SupplierId("A"))))
                // JDBC 예외 타입이 그대로 올라가면 컨트롤러가 저장소 기술을 알게 된다.
                .isInstanceOf(CatalogReadUnavailableException.class)
                .hasMessageContaining("cannot reach catalog store")
                .hasCauseInstanceOf(org.springframework.dao.DataAccessResourceFailureException.class);
    }
}
