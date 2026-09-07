package com.integration.stay.application.catalog;

import com.integration.stay.domain.DomainInvariantViolation;
import com.integration.stay.domain.SupplierId;
import java.util.List;
import java.util.Objects;

/**
 * 한 공급사에 물어볼 숙소 코드 목록.
 *
 * <p><b>검색이 아니라 카탈로그의 개념이다.</b> 이것은 "검색이 쓰는 값" 이 아니라
 * "카탈로그가 검색에게 주는 계약" 이다. 처음에는 search 패키지에 뒀는데,
 * 패키지를 나누자 {@code CatalogSnapshot → SupplierTargets → search} 로 순환이 생겼다.
 * 순환은 도구가 만든 문제가 아니라 소유권을 잘못 정했다는 신호였다.
 *
 * <p>{@code neverSynced} 와 "활성 숙소 0개" 를 구분하는 것이 이 타입의 존재 이유다.
 * 둘 다 코드 목록이 비어 있지만 의미가 완전히 다르다.
 * 전자는 우리가 그 공급사를 호출조차 못 하는 상태(부분 실패)이고,
 * 후자는 정상적으로 동기화된 결과 팔 물건이 없는 상태(부분 실패 아님)다.
 * 합치면 "고객이 B 상품을 못 받았는데 응답은 완전한 결과라고 말하는" 버그가 된다.
 */
public record SupplierTargets(SupplierId supplierId, boolean neverSynced, List<String> propertyCodes) {

    public SupplierTargets {
        Objects.requireNonNull(supplierId, "supplierId");
        propertyCodes = List.copyOf(propertyCodes);
        if (neverSynced && !propertyCodes.isEmpty()) {
            throw new DomainInvariantViolation("neverSynced supplier must have no property codes");
        }
    }

    public static SupplierTargets neverSynced(SupplierId supplierId) {
        return new SupplierTargets(supplierId, true, List.of());
    }
}
