package com.integration.stay.application.catalog;

import com.integration.stay.domain.SupplierId;
import java.util.List;
import java.util.UUID;

/** 스냅샷 적용. 구현은 단일 트랜잭션이어야 한다. */
public interface CatalogSnapshotStore {

    /**
     * 전체 스냅샷을 하나의 트랜잭션으로 적용한다.
     *
     * <p>이 메서드가 트랜잭션 경계인 것이 설계의 핵심이다. 중간에 프로세스가 종료되면
     * 전부 롤백되어 이전 스냅샷이 그대로 남는다. 부분 적용된 상태가 존재하지 않으므로
     * 보정 배치도, 좀비 정리도, 재개 offset 도 필요 없다.
     * <b>문제를 복구하는 코드를 추가한 게 아니라 부분 상태 자체를 만들지 않은 것이다.</b>
     */
    CatalogSyncResult apply(SupplierId supplierId, UUID runId, List<CatalogProperty> snapshot);
}
