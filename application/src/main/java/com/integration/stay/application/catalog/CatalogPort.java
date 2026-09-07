package com.integration.stay.application.catalog;

import com.integration.stay.domain.SupplierId;
import java.util.List;

/**
 * 공급사 숙소 목록 조회. 공급사마다 하나씩 구현된다.
 *
 * <p>검색 경로와 달리 {@code Mono} 를 쓰지 않는다. 카탈로그 동기화는 고객 지연 경로가
 * 아니라 배경 스케줄러 작업이고, 공급사별 격리에 필요한 것은 try-catch 하나뿐이라
 * 리액티브 오케스트레이션을 도입할 이유가 없다.
 */
public interface CatalogPort {

    SupplierId supplierId();

    /** 공급사가 취급하는 <b>전체</b> 목록. 조건 파라미터가 없다. */
    List<CatalogProperty> fetchCatalog();
}
