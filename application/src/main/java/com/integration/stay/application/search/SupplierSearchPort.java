package com.integration.stay.application.search;

import com.integration.stay.application.catalog.CatalogSnapshot;
import com.integration.stay.domain.SupplierId;
import reactor.core.publisher.Mono;

/**
 * 공급사 재고·요금 조회. 공급사마다 하나씩 구현된다.
 *
 * <p>{@code Mono} 를 시그니처에 노출하는 이유는 이 유스케이스의 핵심 자체가 비동기
 * fan-out 이기 때문이다. 동기 포트로 두면 결국 block() 이 필요해져 병렬이 깨진다.
 * Reactor 는 Spring 이 아니라 별도 라이브러리이므로 application 이 프레임워크에
 * 묶이는 것도 아니다.
 */
public interface SupplierSearchPort {

    SupplierId supplierId();

    Mono<SupplierOutcome> searchAll(SearchCommand command, CatalogSnapshot snapshot);
}
