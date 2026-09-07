package com.integration.stay.application;

import com.integration.stay.domain.SupplierId;

/**
 * 같은 공급사 식별자로 어댑터가 둘 이상 등록됐다.
 *
 * <h2>왜 기동을 실패시키는가</h2>
 *
 * 이 상황은 <b>런타임에 조용히 잘못된 결과를 만든다.</b>
 *
 * <ul>
 *   <li>검색: 같은 공급사를 두 번 호출해 응답의 {@code suppliers} 에 같은 이름이 두 번 나오고
 *       상품도 중복된다
 *   <li>카탈로그: 같은 식별자로 두 번 동기화하면 <b>두 번째 실행이 첫 번째가 넣은 매핑을
 *       비활성화한다.</b> 미관측 항목을 비활성화하는 것이 스냅샷 적용의 일부이기 때문이다
 * </ul>
 *
 * <p>둘 다 예외 없이 진행되므로 지표에도 오류로 잡히지 않는다.
 * 공급사를 추가하며 기존 어댑터를 복사한 뒤 식별자만 바꾸지 않으면 정확히 이렇게 된다 —
 * <b>가장 흔한 실수인데 가장 조용하다.</b>
 *
 * <p>그래서 조립 시점에 거부한다. 잘못된 배선은 런타임에 이상하게 동작하는 것보다
 * 기동에서 시끄럽게 실패하는 편이 낫다.
 */
public class DuplicateSupplierException extends RuntimeException {

    public DuplicateSupplierException(String role, SupplierId supplierId) {
        super("duplicate %s registered for supplier %s: 어댑터를 복사한 뒤 식별자를 바꾸지 않았을 수 있다"
                .formatted(role, supplierId.value()));
    }
}
