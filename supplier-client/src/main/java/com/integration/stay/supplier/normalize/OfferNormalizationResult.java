package com.integration.stay.supplier.normalize;

import com.integration.stay.domain.offer.StayOffer;
import java.util.List;

/**
 * item 하나의 정규화 결과.
 *
 * <p>Normalizer 는 순수 함수다. 지표를 직접 올리지 않고 진단을 결과값으로 반환하며,
 * 기록은 어댑터가 한다. 그래서 정규화 테스트에 Micrometer 가 한 줄도 들어가지 않는다.
 */
public sealed interface OfferNormalizationResult {

    record Success(StayOffer offer, List<Diagnostic> diagnostics) implements OfferNormalizationResult {

        public Success {
            diagnostics = List.copyOf(diagnostics);
        }
    }

    record Rejected(RejectReason reason, ExternalOfferKey key, String detail) implements OfferNormalizationResult {}
}
