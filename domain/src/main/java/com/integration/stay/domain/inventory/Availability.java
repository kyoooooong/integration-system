package com.integration.stay.domain.inventory;

import com.integration.stay.domain.DomainInvariantViolation;
/** 요청 기간 전체를 예약할 수 있는 객실 수. */
public record Availability(int bookableRooms) {

    public Availability {
        // clamp 하지 않는다. 음수는 여기 오기 전에 거부되어야 하고, 여기까지 왔다면 우리 버그다.
        // 음수를 0 으로 접으면 계약 위반 데이터가 정상 품절로 위장된다.
        if (bookableRooms < 0) {
            throw new DomainInvariantViolation("bookableRooms must be >= 0: " + bookableRooms);
        }
    }

    public boolean bookable() {
        return bookableRooms > 0;
    }
}
