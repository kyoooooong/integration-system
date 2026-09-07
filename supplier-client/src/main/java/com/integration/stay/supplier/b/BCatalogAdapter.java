package com.integration.stay.supplier.b;

import com.integration.stay.application.catalog.CatalogPort;
import com.integration.stay.application.catalog.CatalogProperty;
import com.integration.stay.application.catalog.CatalogRoomType;
import com.integration.stay.domain.SupplierId;
import com.integration.stay.supplier.CatalogFetchException;
import com.integration.stay.supplier.SupplierClientProperties;
import com.integration.stay.supplier.SupplierIds;
import java.util.List;
import org.springframework.web.reactive.function.client.WebClient;

/** Supplier B 숙소 목록 조회. 목록 API 도 HTTP 200 위에 resultCode 로 실패를 알린다. */
public class BCatalogAdapter implements CatalogPort {

    private final WebClient webClient;
    private final SupplierClientProperties properties;

    public BCatalogAdapter(WebClient webClient, SupplierClientProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public SupplierId supplierId() {
        return SupplierIds.B;
    }

    @Override
    public List<CatalogProperty> fetchCatalog() {
        BPropertiesResponse response;
        try {
            response = webClient
                    .get()
                    .uri("/b/api/properties")
                    .retrieve()
                    .bodyToMono(BPropertiesResponse.class)
                    .timeout(properties.responseTimeout())
                    .block();
        } catch (RuntimeException e) {
            throw new CatalogFetchException(supplierId(), "request failed", e);
        }
        if (response == null) {
            throw new CatalogFetchException(supplierId(), "empty response body");
        }
        if (!BSearchResponse.SUCCESS_CODE.equals(response.resultCode())) {
            throw new CatalogFetchException(
                    supplierId(),
                    "resultCode=%s message=%s".formatted(response.resultCode(), response.resultMessage()));
        }
        if (response.data() == null || response.data().items() == null) {
            throw new CatalogFetchException(supplierId(), "resultCode=0000 but data is null");
        }
        return response.data().items().stream().map(this::toProperty).toList();
    }

    private CatalogProperty toProperty(BPropertiesResponse.BProperty property) {
        List<BPropertiesResponse.BRoom> rooms = property.rooms() == null ? List.of() : property.rooms();
        return new CatalogProperty(
                property.propertyId(),
                property.propertyName(),
                rooms.stream().map(room -> toRoomType(property.propertyId(), room)).toList());
    }

    private CatalogRoomType toRoomType(String propertyId, BPropertiesResponse.BRoom room) {
        if (room.maxOccupancy() == null) {
            throw new CatalogFetchException(
                    supplierId(), "missing maxOccupancy: %s/%s".formatted(propertyId, room.roomId()));
        }
        return new CatalogRoomType(room.roomId(), room.roomName(), room.maxOccupancy());
    }
}
