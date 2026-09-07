package com.integration.stay.mock;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** normal | error | no-response — POST /control/{a|b}/mode?value=... 로 바꾼다. */
@RestController
class MockSupplierController {

    private static final long NO_RESPONSE_DELAY_MILLIS = 600_000;

    private final Map<String, String> modes = new ConcurrentHashMap<>();

    /**
     * 요청 도착 시각 기록.
     *
     * <p>공급사 간 격리를 경과 시간으로 보이면 안 된다. 응답이 모든 브랜치를 기다리므로
     * 총 소요는 순차든 병렬이든 비슷하게 나오고, 격리가 깨져 있어도 {@code time curl} 은
     * 통과한다. 두 공급사 요청이 <b>실제로 겹친 시각에 도착했는지</b>를 봐야 한다.
     */
    private final ConcurrentLinkedQueue<Map<String, String>> arrivals = new ConcurrentLinkedQueue<>();

    /**
     * 정상 응답에 붙는 인위적 지연.
     *
     * <p>no-response 모드와 다르다. 저쪽은 "응답이 오지 않는다" 를 재현해 타임아웃을 보이는
     * 것이고, 이쪽은 <b>정상이지만 느린 공급사</b>를 재현한다. 부하를 걸었을 때 상류가
     * 느릴수록 동시 호출이 오래 살아 있으므로, 우리 동시성 상한이 실제로 걸리는지 보려면
     * 이 지연이 필요하다.
     */
    private final Map<String, Long> delays = new ConcurrentHashMap<>();

    @PostMapping("/control/{supplier}/mode")
    Map<String, String> setMode(@PathVariable String supplier, @RequestParam String value) {
        modes.put(supplier, value);
        return Map.of(supplier, value);
    }

    @PostMapping("/control/{supplier}/delay")
    Map<String, String> setDelay(@PathVariable String supplier, @RequestParam long millis) {
        delays.put(supplier, millis);
        return Map.of(supplier, millis + "ms");
    }

    @GetMapping("/control/requests")
    List<Map<String, String>> requests() {
        return List.copyOf(arrivals);
    }

    @PostMapping("/control/requests/reset")
    Map<String, String> resetRequests() {
        arrivals.clear();
        return Map.of("cleared", "true");
    }

    @GetMapping("/control/modes")
    Map<String, String> modes() {
        return Map.of(
                "a", mode("a") + "/" + delays.getOrDefault("a", 0L) + "ms",
                "b", mode("b") + "/" + delays.getOrDefault("b", 0L) + "ms");
    }

    // ── ① 숙소 목록 (정적 콘텐츠) ─────────────────────────────
    // 목록 API 에도 장애 모드를 건다. 매핑을 만드는 단계가 실패하면 어떻게 되는지가
    // CATALOG_UNAVAILABLE 시연의 전부다.

    @GetMapping(value = "/a/v1/hotels", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> hotelsA() throws InterruptedException {
        return switch (mode("a")) {
            case "error" -> ResponseEntity.status(503).body(A_ERROR);
            case "no-response" -> delayed(MockResponses.hotelsA());
            default -> ResponseEntity.ok(MockResponses.hotelsA());
        };
    }

    @GetMapping(value = "/b/api/properties", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> propertiesB() throws InterruptedException {
        return switch (mode("b")) {
            case "error" -> ResponseEntity.ok(B_ERROR);
            case "no-response" -> delayed(MockResponses.propertiesB());
            default -> ResponseEntity.ok(MockResponses.propertiesB());
        };
    }

    // ── ② 재고·요금 조회 ──────────────────────────────────────

    @GetMapping(value = "/a/v1/availability", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> availabilityA(
            @RequestParam String hotelCodes,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam int adults,
            @RequestParam(defaultValue = "0") int children)
            throws InterruptedException {
        record("a", hotelCodes);
        applyDelay("a");
        // 50개 상한은 계약이다. 초과하면 공급사가 오류를 준다.
        if (MockResponses.requestedCodes(hotelCodes).size() > MAX_CODES) {
            return ResponseEntity.status(400).body(A_TOO_MANY);
        }
        String body = MockResponses.availabilityA(
                MockResponses.requestedCodes(hotelCodes), checkIn, checkOut, adults, children);
        return switch (mode("a")) {
            case "error" -> ResponseEntity.status(503).body(A_ERROR);
            case "no-response" -> delayed(body);
            default -> ResponseEntity.ok(body);
        };
    }

    @GetMapping(value = "/b/api/search", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<String> searchB(
            @RequestParam String propertyIds,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkIn,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate checkOut,
            @RequestParam int adults,
            @RequestParam(defaultValue = "0") int children)
            throws InterruptedException {
        record("b", propertyIds);
        applyDelay("b");
        if (MockResponses.requestedCodes(propertyIds).size() > MAX_CODES) {
            return ResponseEntity.ok(B_TOO_MANY);
        }
        String body = MockResponses.searchB(
                MockResponses.requestedCodes(propertyIds), checkIn, checkOut, adults, children);
        return switch (mode("b")) {
            // B 는 장애 상황에서도 HTTP 200 이다.
            case "error" -> ResponseEntity.ok(B_ERROR);
            case "no-response" -> delayed(body);
            default -> ResponseEntity.ok(body);
        };
    }

    private void record(String supplier, String codes) {
        arrivals.add(Map.of("supplier", supplier, "arrivedAt", Instant.now().toString(), "codes", codes));
    }

    private void applyDelay(String supplier) throws InterruptedException {
        long millis = delays.getOrDefault(supplier, 0L);
        if (millis > 0) {
            Thread.sleep(millis);
        }
    }

    private String mode(String supplier) {
        return modes.getOrDefault(supplier, "normal");
    }

    private ResponseEntity<String> delayed(String body) throws InterruptedException {
        Thread.sleep(NO_RESPONSE_DELAY_MILLIS);
        return ResponseEntity.ok(body);
    }

    /** 공급사 벌크 상한. 계약이다. */
    private static final int MAX_CODES = 50;

    private static final String A_ERROR =
            "{\"error\":\"SERVICE_UNAVAILABLE\",\"message\":\"temporarily unavailable\"}";

    private static final String A_TOO_MANY =
            "{\"error\":\"TOO_MANY_HOTEL_CODES\",\"message\":\"max 50 hotel codes\"}";

    private static final String B_ERROR =
            "{\"resultCode\":\"E503\",\"resultMessage\":\"TEMPORARILY_UNAVAILABLE\",\"data\":null}";

    private static final String B_TOO_MANY =
            "{\"resultCode\":\"E400\",\"resultMessage\":\"TOO_MANY_PROPERTY_IDS\",\"data\":null}";
}
