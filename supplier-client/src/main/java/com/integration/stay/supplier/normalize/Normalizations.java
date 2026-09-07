package com.integration.stay.supplier.normalize;

import com.integration.stay.application.catalog.CatalogRoomTypeRef;
import com.integration.stay.domain.inventory.InventoryRejectReason;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Optional;

/**
 * 공급사와 무관하게 같은 방식으로 처리되는 정규화 조각들.
 *
 * <p>여기 있는 것은 <b>기계적 변환</b>뿐이다. 검증 순서(§ identity -> 배치 소속 -> 매핑 ->
 * 필수 필드 -> 날짜 -> 금액)는 각 Normalizer 안에 그대로 남겨 둔다. 순서 자체가 설계 진술이라
 * 헬퍼로 감추면 읽는 사람이 순서를 확인할 수 없다.
 */
public final class Normalizations {

    private Normalizations() {}

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** ISO 4217 코드로 해석한다. 해석되지 않으면 empty. */
    public static Optional<Currency> parseCurrency(String code) {
        try {
            return Optional.of(Currency.getInstance(code));
        } catch (IllegalArgumentException | NullPointerException e) {
            return Optional.empty();
        }
    }

    public static RejectReason toRejectReason(InventoryRejectReason reason) {
        return switch (reason) {
            case MISSING_FIELD -> RejectReason.MISSING_REQUIRED_FIELD;
            case DATE_COVERAGE_MISMATCH -> RejectReason.DATE_COVERAGE_MISMATCH;
            case INVALID_INVENTORY -> RejectReason.INVALID_INVENTORY;
        };
    }

    /**
     * 표시용 이름의 drift 를 진단으로 남긴다. <b>거부하지 않는다.</b>
     *
     * <p>표시용 이름의 소스는 카탈로그다. live 와 달라도 상품을 버릴 이유가 되지 않으며,
     * 다만 카탈로그가 낡았다는 신호이므로 기록한다.
     */
    public static List<Diagnostic> driftDiagnostics(CatalogRoomTypeRef ref, String livePropertyName, String liveRoomTypeName) {
        List<Diagnostic> diagnostics = new ArrayList<>(2);
        if (livePropertyName != null && !livePropertyName.equals(ref.propertyName())) {
            diagnostics.add(
                    new Diagnostic(DiagnosticKind.CATALOG_DRIFT, "propertyName", ref.propertyName(), livePropertyName));
        }
        if (liveRoomTypeName != null && !liveRoomTypeName.equals(ref.roomTypeName())) {
            diagnostics.add(
                    new Diagnostic(DiagnosticKind.CATALOG_DRIFT, "roomTypeName", ref.roomTypeName(), liveRoomTypeName));
        }
        return diagnostics;
    }
}
