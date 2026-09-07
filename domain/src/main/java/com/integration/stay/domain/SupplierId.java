package com.integration.stay.domain;

/**
 * 공급사 식별자.
 *
 * <p>enum 이 아니라 value class 인 이유는 이것이 <b>닫힌 비즈니스 상태가 아니라
 * 확장 가능한 연동 신원</b>이기 때문이다. 예약 상태나 결제 수단이라면 새 값이 도메인 규칙을
 * 바꾸므로 exhaustive switch 가 필요하다. 공급사는 반대다. 도메인 규칙이 공급사에 따라
 * 달라지면 그것 자체가 ACL 실패이므로, exhaustive switch 가 필요한 상황이 애초에 없어야 한다.
 *
 * <p>enum 이었다면 공급사 C 추가 시 도메인을 고쳐야 하고, "오케스트레이션과 도메인 규칙은
 * 수정하지 않는다" 는 확장성 주장이 그 자리에서 반증된다. DB 에 모르는 값이 있을 때
 * enum 은 역직렬화 예외로 검색 전체를 죽이지만, value class 는 등록된 공급사로 거르면 된다.
 *
 * <p>공급사 목록 상수는 도메인이 아니라 어댑터(supplier-client)에 둔다. 도메인은 목록을 모른다.
 */
public record SupplierId(String value) {

    public SupplierId {
        if (value == null || value.isBlank()) {
            throw new DomainInvariantViolation("supplierId must not be blank");
        }
    }
}
