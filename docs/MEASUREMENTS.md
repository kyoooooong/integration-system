# Measurements

성능 숫자는 Supplier의 실제 속도를 주장하기보다, 우리가 둔 상한이 의도한 계층에서 동작하는지 확인하는 용도로 남깁니다.

## Search Load

| 항목 | 값 |
|---|---|
| 날짜 | 2026-09-07 |
| 도구 | k6 |
| 대상 | `GET /api/v1/stays/search` |
| Mock 지연 | Supplier A 200ms, Supplier B 200ms |
| 설정 | `batch-concurrency=4`, `bulkhead-max-concurrent-calls=16`, `max-connections=24` |
| 목적 | 포화가 공급사 timeout이나 내부 500이 아니라 의도한 자원 상한으로 드러나는지 확인 |
| 캡처 | [`docs/measurements/k6-search-load-2026-09-07.txt`](measurements/k6-search-load-2026-09-07.txt) |

이 캡처는 운영 처리량 보장이 아닙니다. Mock Supplier와 로컬 환경에서, 현재 설정의 상한이 실제로 동작하는지 확인한 기록입니다.

캡처에서 확인한 핵심 결과는 다음과 같습니다.

| 항목 | 결과 |
|---|---|
| threshold | `http_req_failed rate=0.00%`, `search_price_correct rate=100.00%` |
| 포화 발생 | `sat_90=126`, `sat_120=312`, `sat_160=769` |
| 부분 응답 | `search_partial=2161` |
| 가격 정확성 | `5039 / 5039` |
| 전체 요청 | `6246` |

즉 부하가 올라가도 연결 오류나 내부 500으로 무너지지 않았고, 일부 요청은 의도한 상한에서 partial/503으로 degrade했습니다. 가격 검증은 부하와 무관하게 100%를 유지했습니다.
