package com.integration.stay.application.catalog;

import com.integration.stay.domain.SupplierId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 한 검색 요청이 사용하는 카탈로그 스냅샷.
 *
 * <p>요청당 1회 확보해서 정규화까지 같은 스냅샷을 쓴다. 검색 도중 카탈로그 동기화가
 * 커밋되어도 한 응답 안에서 매핑이 바뀌지 않는다.
 */
public final class CatalogSnapshot {

    private final Map<SupplierId, SupplierTargets> targetsBySupplier;
    private final Map<PropertyKey, Map<String, CatalogRoomTypeRef>> roomTypesByProperty;

    private CatalogSnapshot(
            Map<SupplierId, SupplierTargets> targetsBySupplier,
            Map<PropertyKey, Map<String, CatalogRoomTypeRef>> roomTypesByProperty) {
        this.targetsBySupplier = Map.copyOf(targetsBySupplier);
        this.roomTypesByProperty = Map.copyOf(roomTypesByProperty);
    }

    /** 객실 타입 코드는 해당 숙소 안에서만 유일하다. 그래서 키에 숙소 코드가 함께 들어간다. */
    private record PropertyKey(SupplierId supplierId, String propertyCode) {}

    public SupplierTargets targets(SupplierId supplierId) {
        SupplierTargets targets = targetsBySupplier.get(supplierId);
        // 등록된 공급사인데 스냅샷에 없다면 한 번도 동기화되지 않은 것이다.
        return targets != null ? targets : SupplierTargets.neverSynced(supplierId);
    }

    public CatalogResolution resolve(SupplierId supplierId, String propertyCode, String roomTypeCode) {
        Map<String, CatalogRoomTypeRef> roomTypes = roomTypesByProperty.get(new PropertyKey(supplierId, propertyCode));
        if (roomTypes == null) {
            return new CatalogResolution.UnknownProperty();
        }
        CatalogRoomTypeRef ref = roomTypes.get(roomTypeCode);
        if (ref == null) {
            return new CatalogResolution.UnknownRoomType();
        }
        return new CatalogResolution.Resolved(ref);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {

        private final Map<SupplierId, Boolean> syncedSuppliers = new HashMap<>();
        private final Map<SupplierId, List<String>> propertyCodes = new HashMap<>();
        private final Map<PropertyKey, Map<String, CatalogRoomTypeRef>> roomTypes = new HashMap<>();

        /**
         * 동기화 이력이 있는 공급사로 표시한다. 활성 숙소가 0개여도 호출해야 한다.
         * 이 호출이 없으면 그 공급사는 CATALOG_UNAVAILABLE 로 취급된다.
         */
        public Builder synced(SupplierId supplierId) {
            syncedSuppliers.put(Objects.requireNonNull(supplierId), Boolean.TRUE);
            propertyCodes.computeIfAbsent(supplierId, k -> new ArrayList<>());
            return this;
        }

        public Builder roomType(
                SupplierId supplierId,
                String propertyCode,
                UUID propertyId,
                String propertyName,
                String roomTypeCode,
                UUID roomTypeId,
                String roomTypeName) {
            synced(supplierId);
            PropertyKey key = new PropertyKey(supplierId, propertyCode);
            if (!roomTypes.containsKey(key)) {
                propertyCodes.get(supplierId).add(propertyCode);
            }
            roomTypes.computeIfAbsent(key, k -> new HashMap<>())
                    .put(roomTypeCode, new CatalogRoomTypeRef(propertyId, propertyName, roomTypeId, roomTypeName));
            return this;
        }

        public CatalogSnapshot build() {
            Map<SupplierId, SupplierTargets> targets = new HashMap<>();
            syncedSuppliers.keySet().forEach(supplierId ->
                    targets.put(supplierId, new SupplierTargets(supplierId, false, propertyCodes.get(supplierId))));
            Map<PropertyKey, Map<String, CatalogRoomTypeRef>> copied = new HashMap<>();
            roomTypes.forEach((key, value) -> copied.put(key, Map.copyOf(value)));
            return new CatalogSnapshot(targets, copied);
        }
    }
}
