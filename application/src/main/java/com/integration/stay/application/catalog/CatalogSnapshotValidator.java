package com.integration.stay.application.catalog;

import com.integration.stay.domain.SupplierId;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 적용 전 전체 스냅샷 검증. 하나라도 어긋나면 전체를 거부한다.
 *
 * <p><b>빈 스냅샷은 유효한 전체 스냅샷으로 받는다.</b> 계약에 "0개는 올 수 없다" 가 없다.
 * 오류처럼 보인다는 직감으로 외부 계약에 없는 불변식을 만들면, 정상적인 0개 상태를
 * 우리가 막게 된다. 급감은 텔레메트리로 본다.
 */
public final class CatalogSnapshotValidator {

    private CatalogSnapshotValidator() {}

    public static void validate(SupplierId supplierId, List<CatalogProperty> snapshot) {
        if (snapshot == null) {
            throw new CatalogSnapshotRejectedException(supplierId, "snapshot is null");
        }
        Set<String> propertyCodes = new HashSet<>();
        for (CatalogProperty property : snapshot) {
            if (property == null) {
                throw new CatalogSnapshotRejectedException(supplierId, "null property");
            }
            if (isBlank(property.code())) {
                throw new CatalogSnapshotRejectedException(supplierId, "blank property code");
            }
            if (isBlank(property.name())) {
                throw new CatalogSnapshotRejectedException(
                        supplierId, "blank property name: code=" + property.code());
            }
            if (!propertyCodes.add(property.code())) {
                throw new CatalogSnapshotRejectedException(
                        supplierId, "duplicate property code: " + property.code());
            }

            // 숙소마다 새 Set 을 만든다. 전역 집합이면 서로 다른 숙소의 같은 STD-DBL 을
            // 중복으로 오판한다. 객실 코드의 유일성 범위가 숙소 안이기 때문이다.
            Set<String> roomTypeCodes = new HashSet<>();
            for (CatalogRoomType roomType : property.roomTypes()) {
                if (roomType == null) {
                    throw new CatalogSnapshotRejectedException(
                            supplierId, "null room type in property " + property.code());
                }
                if (isBlank(roomType.code())) {
                    throw new CatalogSnapshotRejectedException(
                            supplierId, "blank room type code in property " + property.code());
                }
                if (isBlank(roomType.name())) {
                    throw new CatalogSnapshotRejectedException(
                            supplierId,
                            "blank room type name: %s/%s".formatted(property.code(), roomType.code()));
                }
                if (roomType.maxOccupancy() <= 0) {
                    throw new CatalogSnapshotRejectedException(
                            supplierId,
                            "maxOccupancy must be > 0: %s/%s=%d"
                                    .formatted(property.code(), roomType.code(), roomType.maxOccupancy()));
                }
                if (!roomTypeCodes.add(roomType.code())) {
                    throw new CatalogSnapshotRejectedException(
                            supplierId,
                            "duplicate room type code in property %s: %s"
                                    .formatted(property.code(), roomType.code()));
                }
            }
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
