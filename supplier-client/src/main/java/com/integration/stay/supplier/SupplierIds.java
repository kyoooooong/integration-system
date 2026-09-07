package com.integration.stay.supplier;

import com.integration.stay.domain.SupplierId;

/**
 * 공급사 상수. 도메인이 아니라 어댑터에 둔다.
 *
 * <p>도메인은 공급사 목록을 모른다. 공급사 C 가 추가되면 여기에 한 줄이 늘고
 * domain 모듈은 컴파일조차 다시 하지 않는다.
 */
public final class SupplierIds {

    public static final SupplierId A = new SupplierId("A");
    public static final SupplierId B = new SupplierId("B");

    private SupplierIds() {}
}
