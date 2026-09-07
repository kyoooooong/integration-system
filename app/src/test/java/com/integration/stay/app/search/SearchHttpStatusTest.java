package com.integration.stay.app.search;

import static org.assertj.core.api.Assertions.assertThat;

import com.integration.stay.application.search.FailureType;
import com.integration.stay.application.search.SearchResult;
import com.integration.stay.application.search.SupplierOutcome;
import com.integration.stay.domain.SupplierId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/**
 * 문서의 매핑표를 코드로 못박는다.
 *
 * <p>문서에 표를 적어 두고 일부 행만 검증하면, 검증하지 않은 행이 조용히 틀어져도
 * 아무도 모른다. <b>문서가 코드보다 낙관적이면 그건 거짓말이다.</b>
 *
 * <p>Spring 컨텍스트를 띄우지 않는다. 이 판정은 순수 함수다.
 */
class SearchHttpStatusTest {

    private static final SupplierId A = new SupplierId("A");
    private static final SupplierId B = new SupplierId("B");

    private static HttpStatus statusOf(SupplierOutcome... outcomes) {
        return SearchHttpStatus.of(new SearchResult(List.of(outcomes)));
    }

    @Test
    @DisplayName("쓸 만한 공급사가 하나라도 있으면 200 이다")
    void 쓸_만한_공급사가_하나라도_있으면_200이다() {
        assertThat(statusOf(
                        SupplierOutcome.success(A, List.of()),
                        SupplierOutcome.failed(B, FailureType.TIMEOUT)))
                .isEqualTo(HttpStatus.OK);
        assertThat(statusOf(
                        SupplierOutcome.success(A, List.of()),
                        SupplierOutcome.failed(B, FailureType.INVALID_RESPONSE)))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("전 숙소가 만실이어도 200 이다")
    void 전_숙소가_만실이어도_200이다() {
        // 이 판정을 offers.isEmpty() 로 하면 성수기에 503 이 나간다.
        // "결과 없음" 과 "조회 실패" 를 구분하려고 만든 로직이 정반대로 동작하는 경우다.
        assertThat(statusOf(SupplierOutcome.success(A, List.of()), SupplierOutcome.success(B, List.of())))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("활성 숙소가 0개여도 200 이다")
    void 활성_숙소가_0개여도_200이다() {
        assertThat(statusOf(SupplierOutcome.noTargets(A), SupplierOutcome.noTargets(B)))
                .isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("동질 타임아웃은 504 다")
    void 동질_타임아웃은_504다() {
        assertThat(statusOf(
                        SupplierOutcome.failed(A, FailureType.TIMEOUT),
                        SupplierOutcome.failed(B, FailureType.TIMEOUT)))
                .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    @Test
    @DisplayName("동질 계약 위반은 502 다")
    void 동질_계약_위반은_502다() {
        assertThat(statusOf(
                        SupplierOutcome.failed(A, FailureType.INVALID_RESPONSE),
                        SupplierOutcome.failed(B, FailureType.INVALID_RESPONSE)))
                .isEqualTo(HttpStatus.BAD_GATEWAY);
    }

    @Test
    @DisplayName("혼합 실패는 503 이다")
    void 혼합_실패는_503이다() {
        // 502/504 는 동질 실패일 때만 쓴다. 혼합에서 어느 하나를 우선할 근거가 없다.
        assertThat(statusOf(
                        SupplierOutcome.failed(A, FailureType.INVALID_RESPONSE),
                        SupplierOutcome.failed(B, FailureType.TIMEOUT)))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("우리 버그가 섞이면 503 이 아니라 500 이다")
    void 우리_버그가_섞이면_503이_아니라_500이다() {
        // 우리 잘못이 다른 실패에 가려지면 조사되지 않는다.
        assertThat(statusOf(
                        SupplierOutcome.failed(A, FailureType.INTERNAL_ERROR),
                        SupplierOutcome.failed(B, FailureType.TIMEOUT)))
                .isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    @Test
    @DisplayName("우리 포화는 500 이 아니라 503 이다")
    void 우리_포화는_500이_아니라_503이다() {
        // 500 은 "재시도하지 마라" 라는 뜻이다. 포화는 잠시 뒤 성공할 수 있다.
        // "우리 잘못" 이라는 이유만으로 500 을 주면 클라이언트가 잘못 판단한다.
        assertThat(statusOf(
                        SupplierOutcome.failed(A, FailureType.LOCAL_SATURATION),
                        SupplierOutcome.failed(B, FailureType.LOCAL_SATURATION)))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("한 번도 동기화되지 않은 공급사만 있으면 503 이다")
    void 한_번도_동기화되지_않은_공급사만_있으면_503이다() {
        assertThat(statusOf(SupplierOutcome.catalogUnavailable(A), SupplierOutcome.catalogUnavailable(B)))
                .isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("동기화 안 된 공급사가 있어도 다른 쪽이 쓸 만하면 200 이다")
    void 동기화_안_된_공급사가_있어도_다른_쪽이_쓸_만하면_200이다() {
        assertThat(statusOf(SupplierOutcome.catalogUnavailable(A), SupplierOutcome.success(B, List.of())))
                .isEqualTo(HttpStatus.OK);
    }
}
