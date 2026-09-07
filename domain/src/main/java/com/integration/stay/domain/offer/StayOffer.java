package com.integration.stay.domain.offer;

import com.integration.stay.domain.DomainInvariantViolation;
import com.integration.stay.domain.SupplierId;
import com.integration.stay.domain.inventory.Availability;
import com.integration.stay.domain.pricing.StayPrice;
import java.util.Objects;
import java.util.UUID;

/**
 * 표준 숙박 상품. 공급사가 어디든 고객은 이 형태로 본다.
 *
 * <p>{@code Comparable} 을 구현하지 않는다. 조식 포함 여부 같은 조건이 다른 상품끼리
 * 금액만으로 순서를 매기면, 비교 불가능한 것을 비교 가능한 척하게 된다.
 * 보호를 구조가 아니라 행위로 옮긴 것이다.
 *
 * @param source 출처 공급사. enum 이 아니므로 공급사 추가가 이 타입을 바꾸지 않는다
 */
public record StayOffer(
        UUID propertyId,
        String propertyName,
        UUID roomTypeId,
        String roomTypeName,
        int maxOccupancy,
        SupplierId source,
        Availability availability,
        StayPrice price,
        OfferConditions conditions) {

    public StayOffer {
        Objects.requireNonNull(propertyId, "propertyId");
        Objects.requireNonNull(propertyName, "propertyName");
        Objects.requireNonNull(roomTypeId, "roomTypeId");
        Objects.requireNonNull(roomTypeName, "roomTypeName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(price, "price");
        Objects.requireNonNull(conditions, "conditions");
        if (maxOccupancy <= 0) {
            throw new DomainInvariantViolation("maxOccupancy must be > 0: " + maxOccupancy);
        }
    }
}
