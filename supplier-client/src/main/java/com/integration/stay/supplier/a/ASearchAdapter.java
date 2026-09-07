package com.integration.stay.supplier.a;

import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.application.search.SearchCommand;
import com.integration.stay.application.search.SearchTelemetry;
import com.integration.stay.supplier.AbstractSupplierSearchAdapter;
import com.integration.stay.supplier.BatchResult;
import com.integration.stay.supplier.SupplierClientProperties;
import com.integration.stay.supplier.SupplierFailure;
import com.integration.stay.supplier.SupplierIds;
import com.integration.stay.supplier.normalize.OfferNormalizationResult;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriBuilder;
import reactor.core.publisher.Mono;

/** Supplier A. 실패를 HTTP 상태 코드로 알리므로 응답 봉투가 없다. */
public class ASearchAdapter extends AbstractSupplierSearchAdapter<AAvailabilityResponse> {

    public ASearchAdapter(WebClient webClient, SupplierClientProperties properties, SearchTelemetry telemetry) {
        super(SupplierIds.A, webClient, properties, telemetry);
    }

    @Override
    protected URI uriFor(UriBuilder builder, SearchCommand command, List<String> batch) {
        return builder.path("/a/v1/availability")
                .queryParam("hotelCodes", String.join(",", batch))
                .queryParam("checkIn", command.period().checkIn())
                .queryParam("checkOut", command.period().checkOut())
                .queryParam("adults", command.adults())
                .queryParam("children", command.children())
                .build();
    }

    @Override
    protected Class<AAvailabilityResponse> responseType() {
        return AAvailabilityResponse.class;
    }

    @Override
    protected Mono<AAvailabilityResponse> validateResponseEnvelope(AAvailabilityResponse response) {
        if (response.items() == null) {
            // 200 인데 items 가 없다. 응답 수준 계약 위반이므로 배치 단위로 실패시킨다.
            return Mono.error(SupplierFailure.invalidResponse(supplierId(), "items is null"));
        }
        return Mono.just(response);
    }

    @Override
    protected BatchResult normalizeAll(
            AAvailabilityResponse response, SearchCommand command, List<String> batch, CatalogSnapshot snapshot) {
        Set<String> requested = new HashSet<>(batch);
        List<OfferNormalizationResult> results = response.items().stream()
                .map(item -> ANormalizer.normalize(item, command, requested, snapshot))
                .toList();
        return assembleBatch(batch, results);
    }
}
