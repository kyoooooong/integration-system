package com.integration.stay.application.catalog;

import com.integration.stay.domain.SupplierId;

/**
 * 공급사 하나의 동기화 결과.
 *
 * <p>실패를 예외로 던지지 않고 결과값으로 돌려준다. application 계층에 로거도 미터도
 * 두지 않기 위해서다. 기록은 Composition Root 가 한다.
 */
public sealed interface SupplierSyncOutcome {

    SupplierId supplierId();

    record Succeeded(SupplierId supplierId, CatalogSyncResult result) implements SupplierSyncOutcome {}

    record Failed(SupplierId supplierId, Exception cause) implements SupplierSyncOutcome {}
}
