package com.integration.stay.mock;

import java.util.List;

/**
 * Mock 이 취급하는 숙소·객실 타입과 그 요금 기준.
 *
 * <p><b>고정 응답 문자열을 쓰지 않는 이유.</b> 기준 예시는 요청 파라미터를 무시하고 고정 응답을
 * 주는 수준이어도 된다고 했고, 처음에는 그렇게 만들었다. 그런데 직접 API 를 쏴 보니
 * <b>표준 예시 날짜(9/1~9/4) 외에는 모든 요청이 502</b> 였다.
 *
 * <p>우리 코드는 맞게 동작한 것이다 — 요청 기간을 덮지 않는 응답은 계약 위반이므로 거부한다.
 * 문제는 Mock 이 그 계약을 지키지 않는다는 것이고, 그러면 <b>리뷰어가 날짜를 한 번만 바꿔도
 * 시스템이 고장난 것처럼 보인다.</b>
 *
 * <p>인원도 같다. 계약은 "공급사는 요청 인원을 수용할 수 있는 객실 타입만 반환한다" 인데
 * 고정 응답은 그것을 무시해서 <b>2인실이 5명 검색 결과에 나왔다.</b>
 * 우리 정규화가 인원 적합을 다시 판정하지 않는 근거가 바로 그 계약인데,
 * Mock 이 그것을 어기면 데모가 우리 문서와 모순된다.
 *
 * <p>그래서 요청 기간과 인원에 맞춰 응답을 만든다. 표준 예시 날짜에서는 기준 예시와 같은 값
 * (A 429,000 / B 452,000, Namsan 9/2 재고 0)이 나오도록 기준값을 잡았다.
 */
final class MockCatalog {

    private MockCatalog() {}

    /**
     * @param nightlyNet 숙박일 순서대로 순환하는 1박 단가(세금 별도)
     * @param remaining 숙박일 순서대로 순환하는 잔여 객실 수
     */
    record RoomType(
            String code, String name, int maxOccupancy, List<Integer> nightlyNet, List<Integer> remaining) {}

    record Property(String code, String name, List<RoomType> roomTypes) {}

    /** 세율 10%. 기준 예시의 nightlyRate 와 taxAmount 비율이 그렇다. */
    static final int TAX_PERCENT = 10;

    static final List<Property> A = List.of(
            new Property(
                    "A-10023",
                    "Riverside Hotel Seoul",
                    List.of(new RoomType(
                            "DLX-TWN", "Deluxe Twin", 2, List.of(120_000, 150_000, 120_000), List.of(3, 1, 5)))),
            new Property(
                    "A-10044",
                    "Namsan Garden Stay",
                    // 둘째 날 재고가 0이다. 연박 판정을 min 으로 하지 않으면 틀린 답이 나오는 지점.
                    List.of(new RoomType(
                            "STD-DBL", "Standard Double", 2, List.of(88_000, 99_000, 88_000), List.of(2, 0, 4)))));

    /**
     * B 는 총액만 준다. 표준 3박에서 452,000 이 되도록 1박 기준(세금 포함)을 잡았다.
     * 150,000 + 152,000 + 150,000 = 452,000
     */
    static final List<Property> B = List.of(new Property(
            "B77120",
            "Riverside Hotel Seoul",
            List.of(new RoomType(
                    "R-401", "Deluxe Twin Room", 2, List.of(150_000, 152_000, 150_000), List.of(3, 1, 5)))));

    /**
     * 기준일. 이 날짜가 순환의 0번이며, 기준 예시 값이 여기서 시작한다.
     */
    static final java.time.LocalDate ANCHOR = java.time.LocalDate.parse("2026-09-01");

    /**
     * <b>날짜로</b> 값을 정한다. 요청 안에서의 순번이 아니다.
     *
     * <p>처음에는 순번으로 인덱싱했는데, 직접 쏴 보니 {@code 9/2~9/3} 1박 조회에서
     * Namsan 의 재고가 2로 나왔다. 9/2 는 재고 0이어야 하는데,
     * 그 요청에서는 9/2 가 0번째 밤이라 첫 값이 나온 것이다.
     *
     * <p>재고와 요금은 <b>날짜의 속성</b>이지 조회 창에서의 위치가 아니다.
     * 실제 공급사라면 어느 창으로 조회하든 같은 날짜에 같은 값을 준다.
     * 그러지 않으면 "9/2 재고가 0이라 연박이 안 된다" 는 시연이 조회 창에 따라 달라진다.
     */
    static int at(List<Integer> cycle, java.time.LocalDate date) {
        int offset = (int) java.time.temporal.ChronoUnit.DAYS.between(ANCHOR, date);
        return cycle.get(Math.floorMod(offset, cycle.size()));
    }

    static boolean accommodates(RoomType roomType, int adults, int children) {
        return adults + children <= roomType.maxOccupancy();
    }
}
