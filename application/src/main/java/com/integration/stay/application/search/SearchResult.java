package com.integration.stay.application.search;

import com.integration.stay.domain.offer.StayOffer;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 통합 검색 결과.
 *
 * <p>HTTP 상태 코드로의 변환은 여기서 하지 않는다. 이 타입은 판정에 필요한 사실만 제공하고,
 * 프로토콜 매핑은 app 계층의 관심사다.
 */
public record SearchResult(List<SupplierOutcome> outcomes) {

    public SearchResult {
        outcomes = List.copyOf(outcomes);
    }

    /** 정규화를 통과했지만 재고가 0이라 응답에서 빠지는 건수. */
    public int soldOutCount() {
        return (int) outcomes.stream()
                .flatMap(o -> o.offers().stream())
                .filter(offer -> !offer.availability().bookable())
                .count();
    }

    /** 예약 가능한 상품만. 재고 0 제외는 API 계약이지 도메인 규칙이 아니다. */
    public List<StayOffer> bookableOffers() {
        return outcomes.stream()
                .flatMap(o -> o.offers().stream())
                .filter(offer -> offer.availability().bookable())
                .toList();
    }

    public boolean partial() {
        return outcomes.stream().anyMatch(SupplierOutcome::degraded);
    }

    public boolean hasUsableSupplier() {
        return outcomes.stream().anyMatch(SupplierOutcome::usable);
    }

    public boolean hasOurFault() {
        return failureTypes().stream().anyMatch(FailureType::isOurFault);
    }

    /** 재시도해도 같은 결과인 실패가 섞여 있는가. HTTP 500 의 조건이다. */
    public boolean hasDeterministicBug() {
        return failureTypes().stream().anyMatch(FailureType::isDeterministicBug);
    }

    /**
     * 모든 실패가 같은 종류일 때만 그 종류를 돌려준다.
     *
     * <p>혼합 실패에서 어느 하나를 우선할 근거가 없기 때문이다. 502/504 는 동질 실패일
     * 때만 쓴다.
     */
    public Optional<FailureType> homogeneousFailure() {
        Set<FailureType> types = failureTypes();
        return types.size() == 1 ? Optional.of(types.iterator().next()) : Optional.empty();
    }

    private Set<FailureType> failureTypes() {
        Set<FailureType> types = EnumSet.noneOf(FailureType.class);
        outcomes.stream().filter(SupplierOutcome::degraded).forEach(o -> o.failure().ifPresent(types::add));
        return types;
    }
}
