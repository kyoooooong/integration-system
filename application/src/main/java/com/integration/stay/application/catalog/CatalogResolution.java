package com.integration.stay.application.catalog;

/**
 * 공급사 코드 -> 내부 식별자 해석 결과.
 *
 * <p>실패를 둘로 나눈 이유는 지표에서 조치가 다르기 때문이다. 숙소를 모르는 것과 그 숙소
 * 안의 객실 타입을 모르는 것은 카탈로그가 낡은 방식이 다르다.
 */
public sealed interface CatalogResolution {

    record Resolved(CatalogRoomTypeRef ref) implements CatalogResolution {}

    /** 그 공급사 카탈로그에 이 숙소 코드가 없다. */
    record UnknownProperty() implements CatalogResolution {}

    /** 숙소는 있는데 그 안에 이 객실 타입 코드가 없다. */
    record UnknownRoomType() implements CatalogResolution {}
}
