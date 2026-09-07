package com.integration.stay.supplier.b;

import java.util.List;

/**
 * Supplier B 의 재고·요금 조회 item.
 *
 * <p>{@code taxIncluded} 가 boxed 인 것이 특히 중요하다. primitive 였다면 필드 부재가
 * false 가 되어 "공급사가 세금 미포함이라고 선언" 으로 둔갑하고, 우리는 gross 표준
 * 가정이 깨진 것을 알아채지 못한 채 실제보다 작은 금액을 고객에게 노출하게 된다.
 */
public record BSearchItem(
        String propertyId,
        String propertyName,
        String roomId,
        String roomName,
        Integer maxOccupancy,
        Boolean breakfastIncluded,
        String currency,
        Long totalPrice,
        Boolean taxIncluded,
        List<BInventoryRow> inventory) {}
