package com.integration.stay.supplier.a;

import com.integration.stay.domain.inventory.DailyInventory;
import java.time.LocalDate;

/**
 * Supplier A 의 일별 요금·재고 행. 외부 DTO 이므로 <b>전부 boxed</b> 다.
 *
 * <p>primitive 를 쓰면 Jackson 이 필드 부재를 0 으로 채우고, 그러면 "공급사가 아무 말도
 * 안 했다" 가 "재고 0" 으로 둔갑한다. FAIL_ON_NULL_FOR_PRIMITIVES 를 켜는 대안은
 * 역직렬화 실패가 되어 배치 전체를 죽인다.
 *
 * <p>{@code nightlyRate} 는 세금 별도(net) 금액이고, 그날의 고객 결제 금액은
 * {@code nightlyRate + taxAmount} 다.
 */
public record ADailyRate(LocalDate date, Integer remainingRooms, Long nightlyRate, Long taxAmount)
        implements DailyInventory {}
