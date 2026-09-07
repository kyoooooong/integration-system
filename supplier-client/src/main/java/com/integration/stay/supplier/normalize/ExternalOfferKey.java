package com.integration.stay.supplier.normalize;

import com.integration.stay.domain.SupplierId;

/**
 * 거부된 상품을 가리키는 연동 진단 정보.
 *
 * <p>공급사 코드는 도메인 개념이 아니라 연동 정보이므로 domain 모듈에 두지 않는다.
 * rawPayload 필드는 두지 않는다. 원본 응답 격리를 하지 않기로 했으므로 미리 뚫지 않는다.
 */
public record ExternalOfferKey(SupplierId supplier, String propertyCode, String roomTypeCode) {}
