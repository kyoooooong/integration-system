package com.integration.stay.supplier.a;

import com.integration.stay.application.catalog.CatalogPort;
import com.integration.stay.application.catalog.CatalogProperty;
import com.integration.stay.application.catalog.CatalogRoomType;
import com.integration.stay.domain.SupplierId;
import com.integration.stay.supplier.CatalogFetchException;
import com.integration.stay.supplier.SupplierClientProperties;
import com.integration.stay.supplier.SupplierIds;
import java.util.List;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Supplier A 숙소 목록 조회.
 *
 * <p>{@code block()} 을 쓴다. 이 호출은 고객 요청 스레드가 아니라 카탈로그 동기화
 * 스케줄러 스레드에서 일어나고, 공급사별 순차 실행이 의도된 동작이다. 검색 경로에서의
 * block 은 ArchUnit 이 막는다.
 */
public class ACatalogAdapter implements CatalogPort {

    private final WebClient webClient;
    private final SupplierClientProperties properties;

    public ACatalogAdapter(WebClient webClient, SupplierClientProperties properties) {
        this.webClient = webClient;
        this.properties = properties;
    }

    @Override
    public SupplierId supplierId() {
        return SupplierIds.A;
    }

    @Override
    public List<CatalogProperty> fetchCatalog() {
        AHotelsResponse response;
        try {
            response = webClient
                    .get()
                    .uri("/a/v1/hotels")
                    .retrieve()
                    .bodyToMono(AHotelsResponse.class)
                    .timeout(properties.responseTimeout())
                    .block();
        } catch (RuntimeException e) {
            throw new CatalogFetchException(supplierId(), "request failed", e);
        }
        if (response == null || response.items() == null) {
            throw new CatalogFetchException(supplierId(), "items is null");
        }
        return response.items().stream().map(this::toProperty).toList();
    }

    private CatalogProperty toProperty(AHotelsResponse.AHotel hotel) {
        List<AHotelsResponse.ARoomType> roomTypes =
                hotel.roomTypes() == null ? List.of() : hotel.roomTypes();
        return new CatalogProperty(
                hotel.hotelCode(),
                hotel.hotelName(),
                roomTypes.stream().map(rt -> toRoomType(hotel.hotelCode(), rt)).toList());
    }

    private CatalogRoomType toRoomType(String hotelCode, AHotelsResponse.ARoomType roomType) {
        if (roomType.maxOccupancy() == null) {
            // 0 으로 접으면 "필드가 없었다" 가 "0명 수용" 으로 기록되어 진단이 흐려진다.
            throw new CatalogFetchException(
                    supplierId(), "missing maxOccupancy: %s/%s".formatted(hotelCode, roomType.roomTypeCode()));
        }
        return new CatalogRoomType(roomType.roomTypeCode(), roomType.roomTypeName(), roomType.maxOccupancy());
    }
}
