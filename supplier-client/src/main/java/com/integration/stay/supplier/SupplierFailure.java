package com.integration.stay.supplier;

import com.integration.stay.application.search.FailureType;
import com.integration.stay.domain.SupplierId;

/** 분류가 끝난 공급사 실패. 원인을 문자열로 재해석할 필요가 없도록 타입을 들고 다닌다. */
public class SupplierFailure extends RuntimeException {

    private final transient SupplierId supplierId;
    private final FailureType type;

    public SupplierFailure(SupplierId supplierId, FailureType type, String detail) {
        super("%s: supplier=%s, %s".formatted(type, supplierId.value(), detail));
        this.supplierId = supplierId;
        this.type = type;
    }

    public static SupplierFailure invalidResponse(SupplierId supplierId, String detail) {
        return new SupplierFailure(supplierId, FailureType.INVALID_RESPONSE, detail);
    }

    public SupplierId supplierId() {
        return supplierId;
    }

    public FailureType type() {
        return type;
    }
}
