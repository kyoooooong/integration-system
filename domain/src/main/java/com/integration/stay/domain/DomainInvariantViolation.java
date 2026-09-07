package com.integration.stay.domain;

/**
 * 우리 불변식이 깨졌다. <b>클라이언트 잘못이 아니라 우리 버그다.</b>
 *
 * <h2>왜 {@code IllegalArgumentException} 을 쓰지 않는가</h2>
 *
 * 원래는 전부 {@code IllegalArgumentException} 이었고, 예외 핸들러가 그것을 400 으로
 * 매핑했다. 그런데 그 타입에는 <b>의미가 다른 두 가지가 섞여 있었다.</b>
 *
 * <pre>
 * 클라이언트가 잘못 보냈다   → 400. 요청을 고치면 된다
 * 우리 불변식이 깨졌다       → 500. 요청을 고쳐도 소용없다
 * </pre>
 *
 * 후자를 400 으로 내보내면 <b>우리 버그를 클라이언트 탓으로 돌리게 된다.</b>
 * 클라이언트는 요청을 고쳐 보다가 포기하고, 우리는 4xx 라서 조사하지 않는다.
 * 공급사 실패에서 소유권을 나눈 것과 같은 기준이다.
 *
 * <p>클라이언트 입력 검증은 {@code @Valid} 가 담당한다. 이 예외가 컨트롤러까지 올라왔다면
 * 그 검증이 뚫렸거나 우리 조립 코드가 틀린 것이므로, 400 이 아니라 500 으로 시끄럽게 드러난다.
 */
public class DomainInvariantViolation extends RuntimeException {

    public DomainInvariantViolation(String detail) {
        super(detail);
    }
}
