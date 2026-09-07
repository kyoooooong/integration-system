package com.integration.stay.application.catalog;

import com.integration.stay.domain.SupplierId;
import java.util.Collection;

/** 검색 대상 조회. */
public interface CatalogQuery {

    /**
     * 등록된 공급사들의 활성 매핑을 한 번에 읽는다.
     *
     * <p>{@code registered} 로 거르는 이유는 DB 에 남아 있는 옛 공급사 코드가 검색을 죽이지
     * 않게 하기 위해서다. 공급사 식별자가 enum 이었다면 모르는 값이 역직렬화 예외를 내
     * 검색 쿼리 전체가 죽는다.
     */
    CatalogSnapshot snapshot(Collection<SupplierId> registered);
}
