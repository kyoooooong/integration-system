package com.integration.stay.application.catalog;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.integration.stay.domain.SupplierId;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CatalogSnapshotValidatorTest {

    private static final SupplierId A = new SupplierId("A");

    private static CatalogProperty property(String code, CatalogRoomType... roomTypes) {
        return new CatalogProperty(code, "name-" + code, List.of(roomTypes));
    }

    private static CatalogRoomType roomType(String code, int maxOccupancy) {
        return new CatalogRoomType(code, "name-" + code, maxOccupancy);
    }

    @Test
    @DisplayName("같은 숙소 안의 중복 객실 코드는 스냅샷 전체를 거부한다")
    void 같은_숙소_안의_중복_객실_코드는_스냅샷_전체를_거부한다() {
        var snapshot = List.of(property("A-10023", roomType("STD-DBL", 2), roomType("STD-DBL", 3)));

        assertThatThrownBy(() -> CatalogSnapshotValidator.validate(A, snapshot))
                .isInstanceOf(CatalogSnapshotRejectedException.class);
    }

    @Test
    @DisplayName("서로 다른 숙소의 같은 객실 코드는 중복이 아니다")
    void 서로_다른_숙소의_같은_객실_코드는_중복이_아니다() {
        // 중복 검사에 전역 Set 을 쓰면 이 정상 상황을 거부하게 된다.
        // 객실 코드의 유일성 범위는 숙소 안이다.
        var snapshot = List.of(property("A-10023", roomType("STD-DBL", 2)), property("A-10044", roomType("STD-DBL", 2)));

        assertThatCode(() -> CatalogSnapshotValidator.validate(A, snapshot)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("중복 숙소 코드는 거부한다")
    void 중복_숙소_코드는_거부한다() {
        var snapshot = List.of(property("A-10023", roomType("STD-DBL", 2)), property("A-10023", roomType("DLX", 2)));

        assertThatThrownBy(() -> CatalogSnapshotValidator.validate(A, snapshot))
                .isInstanceOf(CatalogSnapshotRejectedException.class);
    }

    @Test
    @DisplayName("maxOccupancy 가 0 이하면 거부한다")
    void maxOccupancy가_0_이하면_거부한다() {
        var snapshot = List.of(property("A-10023", roomType("STD-DBL", 0)));

        assertThatThrownBy(() -> CatalogSnapshotValidator.validate(A, snapshot))
                .isInstanceOf(CatalogSnapshotRejectedException.class);
    }

    @Test
    @DisplayName("코드가 공백이면 거부한다")
    void 코드가_공백이면_거부한다() {
        assertThatThrownBy(() -> CatalogSnapshotValidator.validate(A, List.of(property("  ", roomType("STD", 2)))))
                .isInstanceOf(CatalogSnapshotRejectedException.class);
        assertThatThrownBy(() -> CatalogSnapshotValidator.validate(A, List.of(property("A-1", roomType(" ", 2)))))
                .isInstanceOf(CatalogSnapshotRejectedException.class);
    }

    @Test
    @DisplayName("빈 스냅샷은 유효하다")
    void 빈_스냅샷은_유효하다() {
        // 계약에 "0개는 올 수 없다" 가 없다. 오류처럼 보인다는 직감으로 외부 계약에 없는
        // 불변식을 만들면 정상적인 0개 상태를 우리가 막게 된다.
        assertThatCode(() -> CatalogSnapshotValidator.validate(A, List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("객실 타입이 없는 숙소도 유효하다")
    void 객실_타입이_없는_숙소도_유효하다() {
        assertThatCode(() -> CatalogSnapshotValidator.validate(A, List.of(property("A-10023"))))
                .doesNotThrowAnyException();
    }
}
