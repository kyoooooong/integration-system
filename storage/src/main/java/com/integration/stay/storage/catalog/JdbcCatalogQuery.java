package com.integration.stay.storage.catalog;

import com.integration.stay.application.catalog.CatalogQuery;
import com.integration.stay.application.catalog.CatalogReadUnavailableException;
import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.domain.SupplierId;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 검색 대상 조회.
 *
 * <p><b>단일 SELECT 인 것이 성능 최적화가 아니라 정합성 규칙이다.</b> 두 번의 SELECT 로
 * 나누면 READ COMMITTED 에서 서로 다른 스냅샷을 조합할 수 있어, 숙소는 옛 동기화 결과인데
 * 객실 타입은 새 동기화 결과인 조합이 만들어진다.
 *
 * <p>{@code supplier_catalog_state} 에서 출발하는 것도 의도다. state 행이 없는 것("한 번도
 * 동기화되지 않았다")과 LEFT JOIN 결과가 NULL 인 것("동기화됐고 활성 숙소가 0개다")이
 * 구분된다. 이 둘을 합치면 고객이 그 공급사 상품을 못 받았는데도 응답이 "완전한 결과"라고
 * 말하게 된다.
 *
 * <p>캐시를 두지 않는다. 요청당 이 쿼리 1회다.
 */
@Component
public class JdbcCatalogQuery implements CatalogQuery {

    private static final String SELECT_TARGETS =
            """
            SELECT sc.supplier_id,
                   p.id  AS property_id,  p.supplier_property_code,  p.name AS property_name,
                   rt.id AS room_type_id, rt.supplier_room_type_code, rt.name AS room_type_name
              FROM supplier_catalog_state sc
              LEFT JOIN supplier_property  p  ON p.supplier_id  = sc.supplier_id AND p.active
              LEFT JOIN supplier_room_type rt ON rt.property_id = p.id           AND rt.active
             WHERE sc.supplier_id IN (:registered)
            """;

    private final JdbcClient jdbcClient;

    public JdbcCatalogQuery(JdbcClient jdbcClient) {
        this.jdbcClient = jdbcClient;
    }

    @Override
    @Transactional(readOnly = true)
    public CatalogSnapshot snapshot(Collection<SupplierId> registered) {
        if (registered.isEmpty()) {
            return CatalogSnapshot.builder().build();
        }
        List<String> ids = registered.stream().map(SupplierId::value).toList();

        CatalogSnapshot.Builder builder = CatalogSnapshot.builder();
        try {
            jdbcClient
                    .sql(SELECT_TARGETS)
                    .param("registered", ids)
                    .query((rs, rowNum) -> {
                        SupplierId supplierId = new SupplierId(rs.getString("supplier_id"));
                        // state 행이 있으면 동기화 이력이 있다는 뜻이다. 활성 숙소가 0개여도 그렇다.
                        builder.synced(supplierId);

                        UUID roomTypeId = rs.getObject("room_type_id", UUID.class);
                        if (roomTypeId != null) {
                            builder.roomType(
                                    supplierId,
                                    rs.getString("supplier_property_code"),
                                    rs.getObject("property_id", UUID.class),
                                    rs.getString("property_name"),
                                    rs.getString("supplier_room_type_code"),
                                    roomTypeId,
                                    rs.getString("room_type_name"));
                        }
                        return supplierId;
                    })
                    .list();
        } catch (DataAccessResourceFailureException e) {
            // 커넥션을 얻지 못했거나 DB 에 닿지 못했다. 저장소 기술에 묶인 예외 타입이
            // app 계층까지 올라가지 않도록 여기서 번역한다.
            throw new CatalogReadUnavailableException("cannot reach catalog store", e);
        }

        return builder.build();
    }
}
