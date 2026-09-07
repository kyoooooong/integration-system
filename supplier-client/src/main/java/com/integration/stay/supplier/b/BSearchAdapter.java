package com.integration.stay.supplier.b;

import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.application.search.FailureType;
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

/**
 * Supplier B. <b>장애 상황에서도 HTTP 200 을 준다.</b>
 *
 * <p>{@code retrieve().onStatus(...)} 만으로는 이 공급사의 장애를 절대 감지할 수 없다.
 * 실패 판정을 A 와 통일시키는 지점이 {@link #validateResponseEnvelope} 하나다.
 */
public class BSearchAdapter extends AbstractSupplierSearchAdapter<BSearchResponse> {

    public BSearchAdapter(WebClient webClient, SupplierClientProperties properties, SearchTelemetry telemetry) {
        super(SupplierIds.B, webClient, properties, telemetry);
    }

    @Override
    protected URI uriFor(UriBuilder builder, SearchCommand command, List<String> batch) {
        return builder.path("/b/api/search")
                .queryParam("propertyIds", String.join(",", batch))
                .queryParam("checkIn", command.period().checkIn())
                .queryParam("checkOut", command.period().checkOut())
                .queryParam("adults", command.adults())
                .queryParam("children", command.children())
                .build();
    }

    @Override
    protected Class<BSearchResponse> responseType() {
        return BSearchResponse.class;
    }

    @Override
    protected Mono<BSearchResponse> validateResponseEnvelope(BSearchResponse response) {
        if (!response.succeeded()) {
            return Mono.error(new SupplierFailure(
                    supplierId(),
                    classifyResultCode(response.resultCode()),
                    "resultCode=%s message=%s".formatted(response.resultCode(), response.resultMessage())));
        }
        if (response.data() == null || response.data().items() == null) {
            // 성공 코드인데 data 가 없다. 응답 수준 계약 위반이다.
            return Mono.error(SupplierFailure.invalidResponse(supplierId(), "resultCode=0000 but data is null"));
        }
        return Mono.just(response);
    }

    /** A 의 상태 코드 분류와 같은 의미로 맞춘다. 이 대응이 "실패 판정 통일" 의 실체다. */
    private FailureType classifyResultCode(String resultCode) {
        if (resultCode == null) {
            return FailureType.INVALID_RESPONSE;
        }
        return switch (resultCode) {
            case "E429" -> FailureType.RATE_LIMITED;
            case "E500", "E503" -> FailureType.UNAVAILABLE;
            // 우리 요청이 잘못됐거나 우리 인증이 잘못됐다. 공급사 가용성 문제가 아니다.
            case "E400", "E401" -> FailureType.INTERNAL_ERROR;
            default -> FailureType.INVALID_RESPONSE;
        };
    }

    @Override
    protected BatchResult normalizeAll(
            BSearchResponse response, SearchCommand command, List<String> batch, CatalogSnapshot snapshot) {
        Set<String> requested = new HashSet<>(batch);
        List<OfferNormalizationResult> results = response.data().items().stream()
                .map(item -> BNormalizer.normalize(item, command, requested, snapshot))
                .toList();
        return assembleBatch(batch, results);
    }
}
