package com.integration.stay.domain.inventory;

import java.time.LocalDate;

/**
 * 일별 재고 한 행. 공급사 DTO 가 이 인터페이스를 구현해 도메인 규칙에 넘어온다.
 *
 * <p>{@code remainingRooms} 가 <b>boxed</b> 인 것이 핵심이다. primitive 면 Jackson 이
 * 필드 부재를 0 으로 채우고, 그러면 "공급사가 아무 말도 안 했다" 가
 * "공급사가 없다고 말했다" 로 둔갑한다. 이 둘은 취해야 할 조치가 다르다.
 */
public interface DailyInventory {

    LocalDate date();

    /** boxed. "없음" 과 "0" 을 구분한다. */
    Integer remainingRooms();
}
