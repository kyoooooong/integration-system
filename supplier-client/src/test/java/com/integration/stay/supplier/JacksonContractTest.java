package com.integration.stay.supplier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Boot 4 는 Jackson 3 로 전환되었다. Jackson 은 이 시스템에서 ACL 의 핵심 도구이므로
 * 그 의미론을 코드로 못박아 둔다. 여기서 깨지면 Boot 4 를 되돌려야 한다.
 *
 * <p>확인하는 것은 넷이다.
 * <ul>
 *   <li>공급사가 필드를 추가해도 배치 전체가 죽지 않는가 (unknown property)
 *   <li>필드 부재가 {@code null} 인가, 아니면 {@code false} 로 둔갑하는가
 *   <li>필드 부재가 {@code null} 인가, 아니면 {@code 0} 으로 둔갑하는가
 *   <li>별도 모듈 등록 없이 {@code LocalDate} 가 역직렬화되는가
 * </ul>
 */
class JacksonContractTest {

    private final ObjectMapper mapper = JsonMapper.builder().build();

    /** 공급사 B 의 재고·요금 item 형태. 외부 DTO 는 전부 boxed 다. */
    record BSearchItem(
            String propertyId,
            String roomId,
            Integer maxOccupancy,
            Boolean breakfastIncluded,
            String currency,
            Long totalPrice,
            Boolean taxIncluded,
            List<BInventoryRow> inventory) {}

    record BInventoryRow(LocalDate date, Integer remainingRooms) {}

    @Test
    @DisplayName("알 수 없는 필드가 있어도 역직렬화된다")
    void 알_수_없는_필드가_있어도_역직렬화된다() {
        // 공급사의 필드 추가는 하위 호환 변경이다. 그것이 우리 배치 실패가 되면 안 된다.
        String json =
                """
                { "propertyId": "B77120", "roomId": "R-401", "loyaltyTier": "GOLD" }
                """;

        BSearchItem item = mapper.readValue(json, BSearchItem.class);

        assertThat(item.propertyId()).isEqualTo("B77120");
        assertThat(item.roomId()).isEqualTo("R-401");
    }

    @Test
    @DisplayName("필드가 없으면 null 이고 명시적 false 와 구분된다")
    void 필드가_없으면_null이고_명시적_false와_구분된다() {
        // primitive 였다면 "taxIncluded 필드가 없음" 이 "공급사가 세금 미포함이라고 선언" 으로 둔갑한다.
        BSearchItem absent = mapper.readValue("""
                { "propertyId": "B77120" }
                """, BSearchItem.class);
        BSearchItem explicitFalse = mapper.readValue("""
                { "propertyId": "B77120", "taxIncluded": false }
                """, BSearchItem.class);

        assertThat(absent.taxIncluded()).isNull();
        assertThat(explicitFalse.taxIncluded()).isFalse();
    }

    @Test
    @DisplayName("필드가 없으면 null 이고 명시적 0 과 구분된다")
    void 필드가_없으면_null이고_명시적_0과_구분된다() {
        // 0 = 공급사가 "없다" 고 말했다 / 누락 = 공급사가 아무 말도 안 했다. 이 둘은 다른 조치를 부른다.
        BInventoryRow absent = mapper.readValue("""
                { "date": "2026-09-02" }
                """, BInventoryRow.class);
        BInventoryRow explicitZero = mapper.readValue("""
                { "date": "2026-09-02", "remainingRooms": 0 }
                """, BInventoryRow.class);

        assertThat(absent.remainingRooms()).isNull();
        assertThat(explicitZero.remainingRooms()).isZero();
    }

    @Test
    @DisplayName("LocalDate 가 역직렬화된다")
    void LocalDate_가_역직렬화된다() {
        // jackson-datatype-jsr310 이 클래스패스에 없다. 그래도 되는지 확인한다.
        BInventoryRow row = mapper.readValue("""
                { "date": "2026-09-02", "remainingRooms": 1 }
                """, BInventoryRow.class);

        assertThat(row.date()).isEqualTo(LocalDate.of(2026, 9, 2));
    }
}
