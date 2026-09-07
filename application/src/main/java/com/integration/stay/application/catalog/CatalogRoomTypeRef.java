package com.integration.stay.application.catalog;

import java.util.UUID;

/**
 * 카탈로그가 소유한 정보. 정규화가 공급사 코드로부터 해석해 가져간다.
 *
 * <p>여기 담긴 것은 <b>식별과 표시</b>뿐이다. maxOccupancy·요금·재고·조식 같은 판매 조건은
 * 들어 있지 않다. 그것들의 유일한 소스는 live 응답이며, 낡은 카탈로그 값으로 폴백하지 않는다.
 * 폴백하면 "2인실인데 3명 검색 결과에 나옴" 같은 자기모순이 생긴다.
 */
public record CatalogRoomTypeRef(UUID propertyId, String propertyName, UUID roomTypeId, String roomTypeName) {}
