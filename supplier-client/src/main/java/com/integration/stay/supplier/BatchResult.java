package com.integration.stay.supplier;

import com.integration.stay.application.search.FailureType;
import com.integration.stay.domain.offer.StayOffer;
import com.integration.stay.supplier.normalize.OfferNormalizationResult;
import java.util.List;

/**
 * 배치 하나의 결과.
 *
 * <p><b>정상 빈 결과와 쓸 수 없는 결과를 타입으로 분리한다.</b> boolean success 로 두면
 * "배치 1은 503, 배치 2는 전부 거부" 인 상황에서 succeeded &lt; expected 판정이 먼저 걸려
 * PARTIAL 이 되는 버그가 난다. 의미를 가장 안쪽 경계에서 정확히 정하면 바깥 집계가 단순해진다.
 *
 * <pre>
 * items == []                -> Success   공급사가 조건에 맞는 상품이 없다고 정상 응답했다
 * items != [] && offer >= 1  -> Success   일부 item 거부는 허용한다
 * items != [] && offer == 0  -> Failure   응답은 왔는데 하나도 쓸 수 없다
 * </pre>
 */
public sealed interface BatchResult {

    List<String> batch();

    List<OfferNormalizationResult.Rejected> rejections();

    record Success(
            List<String> batch, List<StayOffer> offers, List<OfferNormalizationResult.Rejected> rejections)
            implements BatchResult {

        public Success {
            batch = List.copyOf(batch);
            offers = List.copyOf(offers);
            rejections = List.copyOf(rejections);
        }
    }

    record Failure(List<String> batch, FailureType type, List<OfferNormalizationResult.Rejected> rejections)
            implements BatchResult {

        public Failure {
            batch = List.copyOf(batch);
            rejections = List.copyOf(rejections);
        }
    }
}
