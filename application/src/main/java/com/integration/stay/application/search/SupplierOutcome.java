package com.integration.stay.application.search;

import com.integration.stay.domain.SupplierId;
import com.integration.stay.domain.offer.StayOffer;
import java.util.List;
import java.util.Optional;

/** 공급사 하나의 검색 결과. */
public record SupplierOutcome(
        SupplierId supplierId,
        SupplierSearchStatus status,
        List<StayOffer> offers,
        Optional<FailureType> failure) {

    public SupplierOutcome {
        offers = List.copyOf(offers);
    }

    public static SupplierOutcome success(SupplierId id, List<StayOffer> offers) {
        return new SupplierOutcome(id, SupplierSearchStatus.SUCCESS, offers, Optional.empty());
    }

    public static SupplierOutcome partial(SupplierId id, List<StayOffer> offers, FailureType dominant) {
        return new SupplierOutcome(id, SupplierSearchStatus.PARTIAL, offers, Optional.of(dominant));
    }

    public static SupplierOutcome failed(SupplierId id, FailureType failure) {
        return new SupplierOutcome(id, SupplierSearchStatus.FAILED, List.of(), Optional.of(failure));
    }

    public static SupplierOutcome noTargets(SupplierId id) {
        return new SupplierOutcome(id, SupplierSearchStatus.NO_TARGETS, List.of(), Optional.empty());
    }

    public static SupplierOutcome catalogUnavailable(SupplierId id) {
        return new SupplierOutcome(
                id,
                SupplierSearchStatus.CATALOG_UNAVAILABLE,
                List.of(),
                Optional.of(FailureType.CATALOG_UNAVAILABLE));
    }

    /**
     * 이 공급사에서 쓸 만한 응답을 받았는가.
     *
     * <p><b>offers.isEmpty() 로 판정하지 않는다.</b> 그렇게 하면 전 숙소가 만실인 성수기에
     * "조회 실패" 가 되어 503 이 나간다. "결과 없음" 과 "조회 실패" 를 구분하려고 만든
     * 로직이 정반대로 동작하는 경우다.
     */
    public boolean usable() {
        return switch (status) {
            case SUCCESS, PARTIAL, NO_TARGETS -> true;
            case FAILED, CATALOG_UNAVAILABLE -> false;
        };
    }

    /** 고객에게 결과 일부가 빠졌음을 알려야 하는가. */
    public boolean degraded() {
        return switch (status) {
            case PARTIAL, FAILED, CATALOG_UNAVAILABLE -> true;
            case SUCCESS, NO_TARGETS -> false;
        };
    }
}
