package com.integration.stay.storage.catalog;

import static com.integration.stay.storage.catalog.CatalogFixtures.A;
import static com.integration.stay.storage.catalog.CatalogFixtures.B;
import static com.integration.stay.storage.catalog.CatalogFixtures.property;
import static com.integration.stay.storage.catalog.CatalogFixtures.roomType;
import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.application.catalog.CatalogResolution;
import com.integration.stay.application.catalog.CatalogSnapshot;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootTest
@Import({JdbcCatalogSnapshotStore.class, JdbcCatalogQuery.class})
class JdbcCatalogQueryTest extends PostgresContainerSupport {

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

    @Test
    @DisplayName("동기화 이력이 없으면 neverSynced 다")
    void 동기화_이력이_없으면_neverSynced다() {
        // 이 구분이 없으면 "고객이 그 공급사 상품을 못 받았는데 응답은 완전한 결과라고
        // 말하는" 상태가 된다.
        store.apply(A, UUID.randomUUID(), List.of(property("A-10023", "Riverside", roomType("DLX-TWN", "Twin", 2))));

        CatalogSnapshot snapshot = query.snapshot(List.of(A, B));

        assertThat(snapshot.targets(A).neverSynced()).isFalse();
        assertThat(snapshot.targets(B).neverSynced()).isTrue();
    }

    @Test
    @DisplayName("동기화됐고 활성 숙소가 0개인 것은 neverSynced 가 아니다")
    void 동기화됐고_활성_숙소가_0개인_것은_neverSynced가_아니다() {
        store.apply(A, UUID.randomUUID(), List.of(property("A-10023", "Riverside", roomType("DLX-TWN", "Twin", 2))));
        store.apply(A, UUID.randomUUID(), List.of());

        CatalogSnapshot snapshot = query.snapshot(List.of(A));

        assertThat(snapshot.targets(A).neverSynced()).isFalse();
        assertThat(snapshot.targets(A).propertyCodes()).isEmpty();
    }

    @Test
    @DisplayName("비활성 매핑은 검색 대상에서 빠지고 해석되지 않는다")
    void 비활성_매핑은_검색_대상에서_빠지고_해석되지_않는다() {
        store.apply(A, UUID.randomUUID(), List.of(property("A-10023", "Riverside", roomType("DLX-TWN", "Twin", 2))));
        store.apply(A, UUID.randomUUID(), List.of(property("A-10044", "Namsan", roomType("STD-DBL", "Double", 2))));

        CatalogSnapshot snapshot = query.snapshot(List.of(A));

        assertThat(snapshot.targets(A).propertyCodes()).containsExactly("A-10044");
        assertThat(snapshot.resolve(A, "A-10023", "DLX-TWN")).isInstanceOf(CatalogResolution.UnknownProperty.class);
    }

    @Test
    @DisplayName("등록되지 않은 공급사는 조회 대상에서 제외된다")
    void 등록되지_않은_공급사는_조회_대상에서_제외된다() {
        // DB 에 남은 옛 공급사 코드가 검색 전체를 죽이지 않는다.
        // 공급사 식별자가 enum 이었다면 역직렬화 예외로 이 쿼리가 통째로 실패한다.
        store.apply(A, UUID.randomUUID(), List.of(property("A-10023", "Riverside", roomType("DLX-TWN", "Twin", 2))));
        store.apply(B, UUID.randomUUID(), List.of(property("B77120", "Riverside", roomType("R-401", "Twin", 2))));

        CatalogSnapshot snapshot = query.snapshot(List.of(A));

        assertThat(snapshot.targets(A).propertyCodes()).containsExactly("A-10023");
        assertThat(snapshot.targets(B).neverSynced()).isTrue();
    }

    @Test
    @DisplayName("해석 결과는 숙소 미상과 객실 타입 미상을 구분한다")
    void 해석_결과는_숙소_미상과_객실_타입_미상을_구분한다() {
        store.apply(A, UUID.randomUUID(), List.of(property("A-10023", "Riverside", roomType("DLX-TWN", "Twin", 2))));

        CatalogSnapshot snapshot = query.snapshot(List.of(A));

        assertThat(snapshot.resolve(A, "A-10023", "DLX-TWN")).isInstanceOf(CatalogResolution.Resolved.class);
        assertThat(snapshot.resolve(A, "A-10023", "NOPE")).isInstanceOf(CatalogResolution.UnknownRoomType.class);
        assertThat(snapshot.resolve(A, "A-99999", "DLX-TWN")).isInstanceOf(CatalogResolution.UnknownProperty.class);
    }
}
