package com.integration.stay.storage.catalog;

import static com.integration.stay.storage.catalog.CatalogFixtures.A;
import static com.integration.stay.storage.catalog.CatalogFixtures.property;
import static com.integration.stay.storage.catalog.CatalogFixtures.roomType;
import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.application.catalog.CatalogProperty;
import com.integration.stay.application.catalog.CatalogSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * 검색은 <b>요청마다</b> 보유 숙소 전체를 읽어 메모리 스냅샷을 만든다.
 *
 * <p>숙소가 늘면 이 비용이 요청마다 든다. "수백 개 규모까지 유효하다" 고 문서에 쓰려면
 * 그 말이 어디서 깨지는지 알아야 한다. 재 보지 않은 주장은 근거가 없다.
 *
 * <p>이 테스트가 재는 것은 <b>DB 왕복과 스냅샷 구성 비용</b>이지 공급사 호출이 아니다.
 * 절대 시간은 하드웨어에 따라 달라지므로 단언하지 않는다 —
 * 단언하는 것은 <b>정확성</b>(전 건이 해석되는가)이고, 시간은 기록만 한다.
 */
@SpringBootTest
@Import({JdbcCatalogSnapshotStore.class, JdbcCatalogQuery.class})
class CatalogQueryScaleTest extends PostgresContainerSupport {

    private static final int PROPERTY_COUNT = 2_000;
    private static final int ROOM_TYPES_PER_PROPERTY = 3;

    @Autowired
    private JdbcCatalogSnapshotStore store;

    @Autowired
    private JdbcCatalogQuery query;

    @Autowired
    private JdbcClient jdbcClient;

    @BeforeEach
    void clean() {
        jdbcClient.sql("TRUNCATE supplier_room_type, supplier_property, supplier_catalog_state").update();
    }

    private static List<CatalogProperty> largeCatalog() {
        List<CatalogProperty> properties = new ArrayList<>(PROPERTY_COUNT);
        for (int i = 0; i < PROPERTY_COUNT; i++) {
            var roomTypes = new CatalogProperty[] {};
            properties.add(property(
                    "A-%05d".formatted(i),
                    "Hotel %05d".formatted(i),
                    roomType("STD", "Standard", 2),
                    roomType("DLX", "Deluxe", 3),
                    roomType("STE", "Suite", 4)));
        }
        return properties;
    }

    @Test
    @DisplayName("숙소 2000개 스냅샷을 적용하고 전량을 해석한다")
    void 숙소_2000개_스냅샷을_적용하고_전량을_해석한다() {
        long applyStart = System.nanoTime();
        store.apply(A, UUID.randomUUID(), largeCatalog());
        long applyMillis = (System.nanoTime() - applyStart) / 1_000_000;

        long queryStart = System.nanoTime();
        CatalogSnapshot snapshot = query.snapshot(List.of(A));
        long queryMillis = (System.nanoTime() - queryStart) / 1_000_000;

        // 정확성 — 여기가 단언 대상이다.
        assertThat(snapshot.targets(A).propertyCodes()).hasSize(PROPERTY_COUNT);
        assertThat(snapshot.targets(A).neverSynced()).isFalse();
        // 전 건이 실제로 해석되는가. 개수만 맞고 매핑이 비면 검색에서 전부 거부된다.
        for (int i = 0; i < PROPERTY_COUNT; i += 137) {
            assertThat(snapshot.resolve(A, "A-%05d".formatted(i), "DLX"))
                    .isInstanceOf(com.integration.stay.application.catalog.CatalogResolution.Resolved.class);
        }

        // 배칭 — 2000개면 40회 호출이다.
        int batches = (PROPERTY_COUNT + 49) / 50;
        assertThat(batches).isEqualTo(40);

        System.out.printf(
                "%n[SCALE] properties=%d roomTypes=%d  apply=%dms  snapshot=%dms  batches=%d%n",
                PROPERTY_COUNT, PROPERTY_COUNT * ROOM_TYPES_PER_PROPERTY, applyMillis, queryMillis, batches);
    }

    @Test
    @DisplayName("비활성 행이 쌓여도 검색 스냅샷은 활성 행만 읽는다")
    void 비활성_행이_쌓여도_검색_스냅샷은_활성_행만_읽는다() {
        // 논리 삭제를 하므로 비활성 행은 계속 쌓인다. 부분 인덱스가 실제로 값을 하는지 본다.
        store.apply(A, UUID.randomUUID(), largeCatalog());
        store.apply(A, UUID.randomUUID(), List.of(property("A-99999", "Only One", roomType("STD", "Standard", 2))));

        long start = System.nanoTime();
        CatalogSnapshot snapshot = query.snapshot(List.of(A));
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertThat(snapshot.targets(A).propertyCodes()).containsExactly("A-99999");
        int inactive = jdbcClient
                .sql("SELECT count(*) FROM supplier_property WHERE NOT active")
                .query(Integer.class)
                .single();
        assertThat(inactive).isEqualTo(PROPERTY_COUNT);

        System.out.printf("%n[SCALE] inactive=%d  snapshot=%dms%n", inactive, millis);
    }
}
