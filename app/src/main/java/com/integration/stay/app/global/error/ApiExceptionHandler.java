package com.integration.stay.app.global.error;

import com.integration.stay.application.catalog.CatalogReadUnavailableException;
import com.integration.stay.domain.DomainInvariantViolation;
import com.integration.stay.domain.InvalidStayPeriodException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 오류 응답을 한 곳에서 만든다.
 *
 * <h2>기준 둘</h2>
 *
 * <b>① 클라이언트가 고칠 수 있는 오류만 상세를 알려준다.</b>
 * 어떤 파라미터가 왜 틀렸는지는 알려줘야 고칠 수 있지만, 내부 오류의 상세는 알려줘도
 * 클라이언트가 할 수 있는 일이 없고 내부 구조·경로·SQL 만 샌다.
 *
 * <b>② 잡지 않는 예외는 catch-all 로 500 이 된다.</b> 따라서 <b>500 이 아니어야 할 것을
 * 빠뜨리면 조용히 틀린 응답이 나간다.</b> 실제로 그랬다 — 지원하지 않는 메서드와 없는 경로가
 * 둘 다 500 으로 나가고 있었다. 클라이언트가 요청을 잘못 보냈는데 "우리 서버가 고장났다" 고
 * 답한 것이다.
 *
 * <p>그래서 <b>일어날 수 있다고 확인한 것만</b> 명시적으로 잡는다.
 * 이 엔드포인트는 본문 없는 GET 하나뿐이므로 {@code HttpMessageNotReadableException} 같은
 * 것은 발생할 수 없고, 발생하지 않는 예외의 핸들러를 미리 만들지 않는다.
 *
 * <p>검색 자체의 실패(공급사 장애·타임아웃)는 여기로 오지 않는다. 그것은 예외가 아니라
 * 값으로 표현되며, 상태 코드가 503 이어도 본문에는 어느 공급사가 왜 실패했는지가 담긴다.
 * 실패를 예외로 던지면 그 정보를 함께 전달할 자리가 없어진다.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    /** Spring 이 타입 변환 실패에 붙이는 오류 코드. */
    private static final String TYPE_MISMATCH = "typeMismatch";

    // ── 클라이언트가 고칠 수 있는 것 ────────────────────────────────

    /** 도메인 불변식 위반이지만 원인이 클라이언트 입력이므로 400 이다. */
    @ExceptionHandler(InvalidStayPeriodException.class)
    ResponseEntity<ErrorResponse> handleInvalidPeriod(InvalidStayPeriodException e) {
        return respond(ErrorCode.INVALID_DATE_RANGE, ErrorResponse.of(ErrorCode.INVALID_DATE_RANGE, e.getMessage()));
    }

    /**
     * 필드별로 무엇이 왜 틀렸는지 <b>구조로</b> 전달한다.
     *
     * <p>메시지를 이어 붙인 문자열은 사람은 읽을 수 있어도 클라이언트가 파싱할 수 없고,
     * "어느 입력 칸에 오류를 표시할지" 를 정할 수 없다.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        Map<String, String> errors = new LinkedHashMap<>();
        e.getBindingResult().getFieldErrors().forEach(fe -> errors.putIfAbsent(fe.getField(), describe(fe)));
        return respond(ErrorCode.INVALID_PARAMETER, ErrorResponse.of(ErrorCode.INVALID_PARAMETER, errors));
    }

    /**
     * 필드 오류를 클라이언트에게 안전한 문장으로 바꾼다.
     *
     * <p><b>타입 변환 실패의 기본 메시지를 그대로 쓰면 안 된다.</b>
     * Spring 은 {@code "Failed to convert value of type 'java.lang.String' to required type
     * 'java.time.LocalDate'"} 같은 문장을 만드는데, 여기에는 내부 타입의 정규화 이름이 들어 있다.
     * 클라이언트는 그 문자열로 할 수 있는 일이 없고 우리 내부 구조만 드러난다.
     *
     * <p>기대 타입의 <b>단순 이름만</b> 뽑아 쓴다. Spring 이 만드는 오류 코드 중
     * {@code typeMismatch.<정규화 이름>} 형태가 있어서 거기서 얻을 수 있고,
     * 형태가 바뀌면 일반 문장으로 떨어진다.
     */
    private static String describe(FieldError error) {
        if (TYPE_MISMATCH.equals(error.getCode())) {
            return "expected " + expectedTypeOf(error);
        }
        return error.getDefaultMessage() == null ? "invalid value" : error.getDefaultMessage();
    }

    private static String expectedTypeOf(FieldError error) {
        String[] codes = error.getCodes();
        if (codes == null) {
            return "valid value";
        }
        for (String code : codes) {
            if (code.startsWith(TYPE_MISMATCH + ".") && code.indexOf('.', TYPE_MISMATCH.length() + 1) > 0) {
                String candidate = code.substring(code.lastIndexOf('.') + 1);
                // 마지막 조각이 타입의 단순 이름인 경우만 쓴다 (필드명 코드와 구분).
                if (!candidate.isEmpty() && Character.isUpperCase(candidate.charAt(0))) {
                    return candidate;
                }
            }
        }
        return "valid value";
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException e) {
        return respond(
                ErrorCode.INVALID_PARAMETER,
                ErrorResponse.of(ErrorCode.INVALID_PARAMETER, Map.of(e.getParameterName(), "required")));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        // 변환 실패 메시지에는 내부 타입 이름이 섞여 있다. 필드 이름과 기대 형식만 알려준다.
        String expected = e.getRequiredType() == null ? "valid value" : e.getRequiredType().getSimpleName();
        return respond(
                ErrorCode.INVALID_PARAMETER,
                ErrorResponse.of(ErrorCode.INVALID_PARAMETER, Map.of(e.getName(), "expected " + expected)));
    }

    // ── 요청 자체가 잘못 온 것 ──────────────────────────────────────
    //
    // 이 둘이 없으면 catch-all 로 500 이 된다. 클라이언트가 메서드나 경로를 잘못 썼는데
    // "우리 서버가 고장났다" 고 답하게 되고, 클라이언트는 재시도하고 우리는 5xx 알람을 받는다.

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException e) {
        return respond(ErrorCode.METHOD_NOT_ALLOWED, ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNotFound(NoResourceFoundException e) {
        return respond(ErrorCode.NOT_FOUND, ErrorResponse.of(ErrorCode.NOT_FOUND));
    }

    // ── 우리 것 ────────────────────────────────────────────────────

    /**
     * DB 에 지금 닿을 수 없다.
     *
     * <p>커넥션 풀 고갈과 DB 장애가 모두 여기로 온다. 검색의 카탈로그 스냅샷 조회는
     * {@code Mono} 조립 전에 서블릿 스레드에서 동기 실행되므로, 여기서 나는 예외는
     * 리액티브 파이프라인을 거치지 않고 곧장 이 핸들러로 온다.
     *
     * <p>저장소 기술에 묶인 예외 타입은 여기까지 오지 않는다. 포트 경계에서 한 번
     * 번역되므로 컨트롤러는 JDBC 를 모른다.
     *
     * <p><b>500 이 아니라 503 이다.</b> 일시적 용량 문제를 500 으로 보고하면
     * 클라이언트가 "재시도하지 마라" 로 읽는다.
     */
    @ExceptionHandler(CatalogReadUnavailableException.class)
    ResponseEntity<ErrorResponse> handleDependencyUnavailable(CatalogReadUnavailableException e) {
        log.error("dependency unavailable", e);
        return respond(ErrorCode.DEPENDENCY_UNAVAILABLE, ErrorResponse.of(ErrorCode.DEPENDENCY_UNAVAILABLE));
    }

    /**
     * 우리 불변식이 깨졌다. <b>클라이언트 잘못이 아니다.</b>
     *
     * <p>원래 이 예외들은 {@code IllegalArgumentException} 이었고 400 으로 나가고 있었다.
     * 그러면 우리 버그를 클라이언트 탓으로 돌리게 된다 — 클라이언트는 요청을 고쳐 보다가
     * 포기하고, 우리는 4xx 라서 조사하지 않는다.
     *
     * <p>클라이언트 입력 검증은 {@code @Valid} 가 담당한다. 이 예외가 여기까지 왔다면
     * 그 검증이 뚫렸거나 우리 조립 코드가 틀린 것이다.
     */
    @ExceptionHandler(DomainInvariantViolation.class)
    ResponseEntity<ErrorResponse> handleInvariantViolation(DomainInvariantViolation e) {
        log.error("domain invariant violated", e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    /**
     * 마지막 그물.
     *
     * <p>없으면 예상 못 한 예외가 프레임워크 기본 응답으로 나가면서 스택트레이스나
     * 내부 경로가 섞여 나갈 수 있다. 여기서는 <b>로그에만 상세를 남기고</b>
     * 클라이언트에는 고정 메시지를 준다.
     *
     * <p>500 은 "재시도하지 마라" 라는 뜻이다. 예상하지 못한 예외는 재시도해도 같으므로
     * 500 이 맞다. 일시적 용량 문제는 여기 오지 않고 값으로 표현되어 503 이 된다.
     */
    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnexpected(Exception e) {
        log.error("unhandled exception", e);
        return respond(ErrorCode.INTERNAL_ERROR, ErrorResponse.of(ErrorCode.INTERNAL_ERROR));
    }

    private static ResponseEntity<ErrorResponse> respond(ErrorCode code, ErrorResponse body) {
        return ResponseEntity.status(code.status()).body(body);
    }
}
