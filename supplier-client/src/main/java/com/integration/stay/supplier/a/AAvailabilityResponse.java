package com.integration.stay.supplier.a;

import java.util.List;

/** Supplier A 는 실패를 HTTP 상태 코드로 알린다. 성공 본문에 봉투가 없다. */
public record AAvailabilityResponse(List<AAvailabilityItem> items) {}
