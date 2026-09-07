package com.integration.stay.storage.catalog;

import static com.integration.stay.storage.catalog.CatalogFixtures.A;
import static com.integration.stay.storage.catalog.CatalogFixtures.B;
import static com.integration.stay.storage.catalog.CatalogFixtures.property;
import static com.integration.stay.storage.catalog.CatalogFixtures.roomType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integration.stay.application.catalog.CatalogProperty;
import com.integration.stay.domain.SupplierId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 영속 매핑과 static/live lifecycle 에 대한 주장의 증거.
 *
 * <p>여기서 주장하는 것 — UNIQUE 제약, ON CONFLICT DO UPDATE, 트랜잭션 롤백,
 * 식별자 안정성, inactive -> active 복구 — 가 전부 실제 PostgreSQL 동작에 걸려 있다.
 * H2 로 테스트하면 검증해야 할 제약 동작을 다른 DB 동작으로 테스트하게 된다.
 */
@SpringBootTest
@Import(JdbcCatalogSnapshotStore.class)
class JdbcCatalogSnapshotStoreTest extends PostgresContainerSupport {

    @Autowired
    private JdbcCatalogSnapshotStore store;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clean() {
        jdbcClient.sql("TRUNCATE supplier_room_type, supplier_property, supplier_catalog_state").update();
    }

    private void apply(SupplierId supplierId, List<CatalogProperty> snapshot) {
        store.apply(supplierId, UUID.randomUUID(), snapshot);
    }

    private Optional<UUID> propertyId(SupplierId supplierId, String code) {
        return jdbcClient
                .sql("SELECT id FROM supplier_property WHERE supplier_id = :s AND supplier_property_code = :c")
                .param("s", supplierId.value())
                .param("c", code)
                .query(UUID.class)
                .optional();
    }

    private boolean propertyActive(SupplierId supplierId, String code) {
        return Boolean.TRUE.equals(jdbcClient
                .sql("SELECT active FROM supplier_property WHERE supplier_id = :s AND supplier_property_code = :c")
                .param("s", supplierId.value())
                .param("c", code)
                .query(Boolean.class)
                .single());
    }

    @Test
    @DisplayName("동일 카탈로그를 두 번 동기화하면 같은 내부 식별자가 나온다")
    void 동일_카탈로그를_두_번_동기화하면_같은_내부_식별자가_나온다() {
        var snapshot = List.of(property("A-10023", "Riverside Hotel Seoul", roomType("DLX-TWN", "Deluxe Twin", 2)));

        apply(A, snapshot);
        UUID first = propertyId(A, "A-10023").orElseThrow();
        apply(A, snapshot);
        UUID second = propertyId(A, "A-10023").orElseThrow();

        // ON CONFLICT DO UPDATE 의 SET 목록에 id 가 없다는 것이 이 테스트의 전부다.
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("숙소명이 바뀌어도 내부 식별자는 유지된다")
    void 숙소명이_바뀌어도_내부_식별자는_유지된다() {
        apply(A, List.of(property("A-10023", "Riverside Hotel Seoul", roomType("DLX-TWN", "Deluxe Twin", 2))));
        UUID before = propertyId(A, "A-10023").orElseThrow();

        apply(A, List.of(property("A-10023", "Riverside Hotel Seoul (Renovated)", roomType("DLX-TWN", "Deluxe Twin", 2))));

        assertThat(propertyId(A, "A-10023")).contains(before);
        assertThat(jdbcClient
                        .sql("SELECT name FROM supplier_property WHERE id = :id")
                        .param("id", before)
                        .query(String.class)
                        .single())
                .isEqualTo("Riverside Hotel Seoul (Renovated)");
    }

    @Test
    @DisplayName("사라졌다 돌아온 숙소는 같은 식별자로 복구된다")
    void 사라졌다_돌아온_숙소는_같은_식별자로_복구된다() {
        // 하드 삭제를 하지 않기로 한 결정이 값을 하는 지점이다.
        apply(A, List.of(property("A-10023", "Riverside Hotel Seoul", roomType("DLX-TWN", "Deluxe Twin", 2))));
        UUID original = propertyId(A, "A-10023").orElseThrow();

        apply(A, List.of(property("A-10044", "Namsan Garden Stay", roomType("STD-DBL", "Standard Double", 2))));
        assertThat(propertyActive(A, "A-10023")).isFalse();

        apply(A, List.of(property("A-10023", "Riverside Hotel Seoul", roomType("DLX-TWN", "Deluxe Twin", 2))));

        assertThat(propertyId(A, "A-10023")).contains(original);
        assertThat(propertyActive(A, "A-10023")).isTrue();
    }

    @Test
    @DisplayName("서로 다른 숙소의 같은 객실 코드는 다른 식별자를 갖는다")
    void 서로_다른_숙소의_같은_객실_코드는_다른_식별자를_갖는다() {
        // 객실 타입 코드의 유일성 범위가 숙소 안이라는 계약의 직접 증거다.
        apply(
                A,
                List.of(
                        property("A-10023", "Riverside Hotel Seoul", roomType("STD-DBL", "Standard Double", 2)),
                        property("A-10044", "Namsan Garden Stay", roomType("STD-DBL", "Standard Double", 2))));

        List<UUID> roomTypeIds = jdbcClient
                .sql("SELECT rt.id FROM supplier_room_type rt WHERE rt.supplier_room_type_code = 'STD-DBL'")
                .query(UUID.class)
                .list();

        assertThat(roomTypeIds).hasSize(2).doesNotHaveDuplicates();
    }

    @Test
    @DisplayName("A 동기화는 B 매핑을 비활성화하지 않는다")
    void A_동기화는_B_매핑을_비활성화하지_않는다() {
        // 비활성화 SQL 에서 공급사 스코프가 빠지면 A 동기화가 B 매핑을 전부 죽인다.
        apply(A, List.of(property("A-10023", "Riverside Hotel Seoul", roomType("DLX-TWN", "Deluxe Twin", 2))));
        apply(B, List.of(property("B77120", "Riverside Hotel Seoul", roomType("R-401", "Deluxe Twin Room", 2))));

        apply(A, List.of(property("A-10044", "Namsan Garden Stay", roomType("STD-DBL", "Standard Double", 2))));

        assertThat(propertyActive(B, "B77120")).isTrue();
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM supplier_room_type rt "
                                + "JOIN supplier_property p ON p.id = rt.property_id "
                                + "WHERE p.supplier_id = 'B' AND rt.active")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("빈 목록도 유효한 전체 스냅샷으로 적용된다")
    void 빈_목록도_유효한_전체_스냅샷으로_적용된다() {
        // 계약에 "0개는 올 수 없다" 가 없다. 정상적인 0개 상태를 우리가 막지 않는다.
        apply(A, List.of(property("A-10023", "Riverside Hotel Seoul", roomType("DLX-TWN", "Deluxe Twin", 2))));

        apply(A, List.of());

        assertThat(propertyActive(A, "A-10023")).isFalse();
        // 동기화 이력 자체는 남는다. "한 번도 동기화되지 않음" 과 구분되어야 한다.
        assertThat(jdbcClient
                        .sql("SELECT count(*) FROM supplier_catalog_state WHERE supplier_id = 'A'")
                        .query(Integer.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("적용 중 예외가 나면 전체가 롤백된다")
    void 적용_중_예외가_나면_전체가_롤백된다() {
        apply(A, List.of(property("A-10023", "Riverside Hotel Seoul", roomType("DLX-TWN", "Deluxe Twin", 2))));
        UUID original = propertyId(A, "A-10023").orElseThrow();

        // 숙소 upsert 는 성공하고 객실 타입에서 CHECK 제약이 터진다.
        // 숙소만 반영된 중간 상태가 남으면 안 된다.
        var poisoned = List.of(
                property("A-99999", "New Hotel", roomType("STD", "Standard", 0)));

        assertThatThrownBy(() -> apply(A, poisoned)).isInstanceOf(Exception.class);

        assertThat(propertyId(A, "A-99999")).isEmpty();
        assertThat(propertyId(A, "A-10023")).contains(original);
        assertThat(propertyActive(A, "A-10023")).isTrue();
    }
}
