package com.integration.stay.domain.inventory;

/** 재고 판정 결과. 성공과 거부를 타입으로 분리해 호출부가 둘을 섞을 수 없게 한다. */
public sealed interface InventoryEvaluation {

    record Valid(Availability availability) implements InventoryEvaluation {}

    record Invalid(InventoryRejectReason reason, String detail) implements InventoryEvaluation {}
}
