package com.integration.stay.storage.catalog;

import com.integration.stay.application.catalog.CatalogProperty;
import com.integration.stay.application.catalog.CatalogRoomType;
import com.integration.stay.application.catalog.CatalogSnapshotStore;
import com.integration.stay.application.catalog.CatalogSyncResult;
import com.integration.stay.domain.SupplierId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 전체 스냅샷을 하나의 트랜잭션으로 적용한다.
 *
 * <p><b>이 클래스가 별도 빈인 것이 중요하다.</b> 같은 빈 안에서 호출하면 self-invocation 이
 * 트랜잭션 프록시를 우회해서, 애노테이션은 붙어 있는데 트랜잭션은 없는 상태가 된다.
 *
 * <p>JPA 가 아니라 Spring JDBC 를 쓰는 이유는 여기 있다. 멱등성의 핵심인
 * {@code ON CONFLICT DO UPDATE} 를 JPA 로는 표준으로 표현할 수 없어 결국 네이티브 SQL 을
 * 쓰게 된다. select-then-save 로 우회하면 동시 실행에서 유니크 위반이 나고, 예외를 잡아
 * 재조회하는 보정 코드가 붙는다. 그 보정 코드는 반드시 버그를 낸다.
 */
@Component
public class JdbcCatalogSnapshotStore implements CatalogSnapshotStore {

    private static final String UPSERT_PROPERTY =
            """
            INSERT INTO supplier_property (id, supplier_id, supplier_property_code, name, active, last_seen_run)
            VALUES (?, ?, ?, ?, TRUE, ?)
            ON CONFLICT (supplier_id, supplier_property_code)
            DO UPDATE SET name          = EXCLUDED.name,
                          active        = TRUE,
                          last_seen_run = EXCLUDED.last_seen_run,
                          updated_at    = now()
            """;
    // id 가 SET 목록에 없다. 내부 식별자 안정성의 전부가 이 한 줄이다.
    // active = TRUE 는 사라졌다 돌아온 숙소를 자동 복구한다.

    private static final String UPSERT_ROOM_TYPE =
            """
            INSERT INTO supplier_room_type
                (id, property_id, supplier_room_type_code, name, max_occupancy, active, last_seen_run)
            VALUES (?, ?, ?, ?, ?, TRUE, ?)
            ON CONFLICT (property_id, supplier_room_type_code)
            DO UPDATE SET name          = EXCLUDED.name,
                          max_occupancy = EXCLUDED.max_occupancy,
                          active        = TRUE,
                          last_seen_run = EXCLUDED.last_seen_run,
                          updated_at    = now()
            """;

    private final JdbcClient jdbcClient;
    private final JdbcTemplate jdbcTemplate;

    public JdbcCatalogSnapshotStore(JdbcClient jdbcClient, JdbcTemplate jdbcTemplate) {
        this.jdbcClient = jdbcClient;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 무한 대기를 막는다 (EVALUATION_DEFAULT).
     *
     * <p>동기화는 스케줄러 스레드에서 {@code fixedDelay} 로 돌고 그 풀 크기는 1이다.
     * 이 트랜잭션이 멈추면 <b>이후 카탈로그 동기화가 영원히 실행되지 않는다.</b>
     * 검색은 마지막 스냅샷으로 계속 동작하고 health 도 UP 이므로,
     * 그 상태는 "왜 신규 숙소가 안 보이지" 로 한참 뒤에 발견된다.
     *
     * <p>30초는 최적값 주장이 아니라 상한이다. 자동 테스트는 2,000개 스냅샷 적용과 조회가
     * 완료되는지 확인하되 절대 시간은 단언하지 않는다. 이 값의 목적은 성능 튜닝이 아니라
     * 멈춘 트랜잭션이 다음 동기화를 영원히 막는 상황을 끊는 것이다.
     */
    @Override
    @Transactional(timeout = 30)
    public CatalogSyncResult apply(SupplierId supplierId, UUID runId, List<CatalogProperty> snapshot) {
        upsertProperties(supplierId, runId, snapshot);

        // 충돌 시 기존 id 가 유지되므로 우리가 생성한 UUID 가 아니라 실제 id 를 다시 읽는다.
        Map<String, UUID> propertyIds = reloadIdsSeenInThisRun(supplierId, runId);
        if (propertyIds.size() != snapshot.size()) {
            throw new CatalogApplyInvariantViolation(
                    "expected %d properties after upsert, found %d".formatted(snapshot.size(), propertyIds.size()));
        }

        int roomTypesApplied = upsertRoomTypes(runId, snapshot, propertyIds);

        // 자식 먼저 비활성화한다. 그리고 반드시 공급사 스코프여야 한다.
        // 스코프가 빠지면 A 동기화가 B 의 매핑을 전부 죽인다.
        int roomTypesDeactivated = deactivateUnseenRoomTypes(supplierId, runId);
        int propertiesDeactivated = deactivateUnseenProperties(supplierId, runId);

        // 매핑과 원자적으로 갱신된다. state 만 앞서가는 상태가 존재하지 않는다.
        upsertCatalogState(supplierId, runId);

        return new CatalogSyncResult(
                snapshot.size(), roomTypesApplied, propertiesDeactivated, roomTypesDeactivated);
    }

    private void upsertProperties(SupplierId supplierId, UUID runId, List<CatalogProperty> snapshot) {
        List<Object[]> batch = new ArrayList<>(snapshot.size());
        for (CatalogProperty property : snapshot) {
            // 내부 식별자는 애플리케이션에서 발급한다. 공급사 코드에서 유도하면 우리 식별자의
            // 안정성이 공급사의 코드 체계 유지에 종속된다.
            batch.add(new Object[] {
                UUID.randomUUID(), supplierId.value(), property.code(), property.name(), runId
            });
        }
        jdbcTemplate.batchUpdate(UPSERT_PROPERTY, batch);
    }

    private Map<String, UUID> reloadIdsSeenInThisRun(SupplierId supplierId, UUID runId) {
        Map<String, UUID> ids = new HashMap<>();
        jdbcClient
                .sql("SELECT supplier_property_code, id FROM supplier_property "
                        + "WHERE supplier_id = :supplierId AND last_seen_run = :runId")
                .param("supplierId", supplierId.value())
                .param("runId", runId)
                .query((rs, rowNum) -> ids.put(rs.getString(1), rs.getObject(2, UUID.class)))
                .list();
        return ids;
    }

    private int upsertRoomTypes(UUID runId, List<CatalogProperty> snapshot, Map<String, UUID> propertyIds) {
        List<Object[]> batch = new ArrayList<>();
        for (CatalogProperty property : snapshot) {
            UUID propertyId = propertyIds.get(property.code());
            if (propertyId == null) {
                throw new CatalogApplyInvariantViolation("no id for property code " + property.code());
            }
            for (CatalogRoomType roomType : property.roomTypes()) {
                batch.add(new Object[] {
                    UUID.randomUUID(),
                    propertyId,
                    roomType.code(),
                    roomType.name(),
                    roomType.maxOccupancy(),
                    runId
                });
            }
        }
        jdbcTemplate.batchUpdate(UPSERT_ROOM_TYPE, batch);
        return batch.size();
    }

    private int deactivateUnseenRoomTypes(SupplierId supplierId, UUID runId) {
        return jdbcClient
                .sql("""
                     UPDATE supplier_room_type rt SET active = FALSE, updated_at = now()
                       FROM supplier_property p
                      WHERE rt.property_id = p.id AND p.supplier_id = :supplierId
                        AND rt.last_seen_run <> :runId AND rt.active
                     """)
                .param("supplierId", supplierId.value())
                .param("runId", runId)
                .update();
    }

    private int deactivateUnseenProperties(SupplierId supplierId, UUID runId) {
        return jdbcClient
                .sql("""
                     UPDATE supplier_property SET active = FALSE, updated_at = now()
                      WHERE supplier_id = :supplierId AND last_seen_run <> :runId AND active
                     """)
                .param("supplierId", supplierId.value())
                .param("runId", runId)
                .update();
    }

    private void upsertCatalogState(SupplierId supplierId, UUID runId) {
        jdbcClient
                .sql("""
                     INSERT INTO supplier_catalog_state (supplier_id, last_success_run, last_success_at)
                     VALUES (:supplierId, :runId, now())
                     ON CONFLICT (supplier_id)
                     DO UPDATE SET last_success_run = EXCLUDED.last_success_run,
                                   last_success_at  = EXCLUDED.last_success_at
                     """)
                .param("supplierId", supplierId.value())
                .param("runId", runId)
                .update();
    }
}
