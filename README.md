# Multi-Supplier Stay Aggregator

여러 숙박 Supplier의 서로 다른 계약을 하나의 내부 모델로 정규화하고,
실시간 가격·재고를 병렬 조회하는 Java 21 + Spring Boot 4 + PostgreSQL 기반 백엔드입니다.

```http
GET /api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0
```

구현 범위를 넓히기보다 현재 요구에서 **잘못된 값을 만들기 쉬운 지점, 실패가 불필요하게 번지는 지점,
데이터와 요청이 늘어날수록 비용이 반복되는 지점**을 먼저 줄이는 방향으로 접근했습니다.

이번 구현에서는 다음 기준을 중심에 두었습니다.

- Supplier가 제공하지 않은 정보는 임의로 만들어내지 않으려고 했습니다.
- 일부 Supplier의 실패가 전체 검색 실패로 번지지 않도록 실패 경계를 나눴습니다.
- 정적 데이터와 실시간 데이터를 같은 lifecycle로 다루지 않았습니다.
- 동시성을 높이기보다 어디에서 상한을 둘지 먼저 정했습니다.
- 기술을 추가할 때는 장점뿐 아니라 비용과 다시 검토할 조건도 함께 남겼습니다.

---

## 1. 한눈에 보는 주요 판단

| 지점 | 검토한 선택지 | 현재 선택 | 선택한 이유 | 감수한 점 |
|---|---|---|---|---|
| 가격 표준 | 일별 가격 / 총액만 / 총액 + 상세 optional | **총액 필수 + 상세 optional** | 일별 가격은 정확한 총액으로 합칠 수 있지만, 총액을 일별 가격으로 복원할 근거는 없다고 보았습니다 | 클라이언트가 optional 필드를 처리해야 합니다 |
| Catalog | 검색마다 조회 / 기동 시 1회 / 주기 동기화 | **PostgreSQL 저장 + 기동 직후/주기 동기화** | 정적 성격의 목록 조회를 검색 경로에서 분리하면서 내부 ID와 변경 사항을 함께 관리하려고 했습니다 | Scheduler와 동기화 상태를 운영해야 합니다 |
| 가격·재고 | DB 저장 / Redis cache / live 조회 | **검색 시 Supplier live 조회** | 예약 전 재검증이 없는 현재 범위에서는 stale 가격 허용 기준을 정하기 어렵다고 보았습니다 | 반복 검색의 upstream 비용은 남습니다 |
| Supplier 호출 | 숙소별 호출 / bulk 순차 / bulk 병렬 | **최대 50개 bulk + 유계 병렬** | Supplier bulk 계약을 활용하되 한 요청이 무제한 fan-out 하지 않도록 했습니다 | batch 단위 상태가 추가됩니다 |
| 장애 처리 | 전체 실패 / Supplier 단위 / Supplier+batch+item | **3단계 격리** | 정상 결과를 가능한 범위에서 유지하면서 잘못된 item만 제외하는 편이 적절하다고 판단했습니다 | 응답 상태가 단순 성공/실패보다 넓어집니다 |
| 포화 처리 | permit 대기 / 즉시 실패 | **Bulkhead 즉시 실패** | 일부 Supplier 결과만으로 응답할 수 있어 긴 대기열보다 빠르게 degrade하는 편을 선택했습니다 | 순간 burst에서도 partial/503이 발생할 수 있습니다 |
| 중복 숙소 | 이름 기반 병합 / 별도 노출 | **Supplier별 별도 노출** | 공통 식별자가 없고 판매 조건도 다르므로 추정 병합의 오탐 비용이 크다고 보았습니다 | 화면에서 같은 숙소가 중복돼 보일 수 있습니다 |
| 통화 | 단일 통화 강제 / 즉시 FX 변환 / 원본 통화 보존 | **원본 ISO 4217 통화 보존** | 환율 source·기준시각·rounding 정책 없이 금액을 변환하지 않으려고 했습니다 | 통화가 다른 상품을 금액만으로 정렬하지 않습니다 |

### 현재 구현 범위

| 영역 | 상태 | 구현/설계 |
|---|---|---|
| 통합 숙박 상품 모델 | **구현** | 총액을 공통 표준으로 두고 일별 가격·세액은 제공되는 경우에만 보존 |
| Supplier ↔ 내부 숙소/객실 타입 ID | **구현** | PostgreSQL에 저장하고 재동기화 후에도 내부 ID 유지 |
| WebClient Supplier Adapter | **구현** | Supplier DTO와 실패 표현을 `supplier-client`에 격리 |
| 다중 Supplier 병렬 검색 | **구현** | Supplier별 병렬 + 최대 50개 batch + request-local concurrency |
| 연결/응답 timeout | **구현** | connect 1s / response 2s, 운영 최적값이 아닌 초기 보호값으로 명시 |
| 부분 실패 | **구현** | Supplier / batch / item 단위 격리 |
| HTTP 200 실패 응답 | **구현** | Supplier B의 body `resultCode`를 별도 검증 |
| Mock 정상/장애/무응답 | **구현** | 별도 프로세스 `:9090` |
| Retry | **설계만** | rate limit·retry budget 확보 전 자동 retry 미도입 |
| CircuitBreaker | **설계만** | 반복 장애 데이터 확보 후 도입 조건 정의 |
| 가격·재고 Cache | **설계만** | stale 허용 기준·재검증 지점 확보 후 재검토 |
| 정규화 실패 격리 | **부분 구현** | item 단위 격리 + reason metric/log, 원본 payload 보관은 미구현 |
| 중복 숙소 병합 | **정책 구현** | 공통 키가 없어 병합하지 않고 Supplier별 상품으로 노출 |
| 통화 처리 | **구현 + 설계** | 통화 보존·동일 통화 합산 강제, FX 변환은 미도입 |
| 예약 대행 | **설계만** | 멱등성·의도 선기록·reconciliation·보상 흐름을 확장 설계에 기록 |

상세한 비핵심 확장 판단은 [`docs/EXTENDED_DESIGN.md`](docs/EXTENDED_DESIGN.md)에 정리했습니다.

---

## 2. 전체 구조

```text
app              HTTP 진입점, Composition Root, 예외 핸들러, 스케줄러
application      유스케이스, Port 인터페이스
domain           표준 모델과 순수 규칙
storage          PostgreSQL adapter, Flyway
supplier-client  Supplier WebClient, DTO, Normalizer
mock-supplier    별도 프로세스(:9090)
```

의존 방향은 안쪽으로만 흐르도록 했습니다.

```text
app ──→ application, storage, supplier-client, domain
application ──→ domain, reactor-core
storage ──→ application, domain
supplier-client ──→ application, domain
domain ──→ 없음
```

`domain`은 Spring, DB, Supplier DTO를 모릅니다.
Supplier별 URI, 응답 봉투, 실패 표현, 정규화 규칙은 `supplier-client`에서 내부 계약으로 바꿉니다.

```text
Client
→ StaySearchController
→ SearchStaysService
→ CatalogSnapshot 1회 조회
→ Supplier별 50개 batch 병렬 호출
→ 정규화
→ items + partial + suppliers[].status
```

새 Supplier가 늘 때 검색 오케스트레이션과 도메인 규칙까지 함께 고치지 않는 것이 이 구조의 목적입니다.

---

## 3. 내부 모델 — 무손실 방향으로만 정규화

Supplier A는 날짜별 금액과 세액을 줍니다.
Supplier B는 요청한 숙박 기간의 총액만 줍니다.

```text
A
daily net + tax
→ 정확한 period gross 계산 가능

B
period gross
→ 정확한 daily price 복원 불가능
```

예를 들어 3박 총액 452,000원을 3으로 나눈 값은 어느 날짜의 실제 가격인지 알 수 없습니다.
그래서 총액을 표준으로 두고, 일별 정보는 존재하는 Supplier에서만 보존했습니다.

```text
totalAmount        required
currency           required
taxAmount          optional
nightlyBreakdown   optional
```

`taxAmount = null`은 `0원`과 다른 상태입니다.

```text
0       → 세금이 0원이라고 알고 있음
null    → Supplier가 별도 세액을 제공하지 않음
```

API 모델이 조금 복잡해지는 비용은 감수하고, 제공되지 않은 값을 그럴듯하게 채우지 않는 쪽을 선택했습니다.

### 통화

모든 금액은 `Money(amount, Currency)`로 표현하고 ISO 4217 통화를 함께 보존합니다.
서로 다른 통화끼리 합산하면 `CurrencyMismatchException`이 발생합니다.

현재는 환율 source, quote timestamp, rounding 정책이 정의되어 있지 않기 때문에 임의 FX 변환을 하지 않습니다.
따라서 KRW와 USD 상품이 함께 노출될 수는 있지만, 서로 다른 통화를 금액만으로 비교하거나 정렬하는 계약은 제공하지 않습니다.

---

## 4. Catalog — 정적 데이터와 실시간 데이터를 분리

숙소·객실 타입 목록과 가격·재고는 변경 주기가 다르다고 보았습니다.

```text
Catalog
숙소 / 객실 타입 / 내부 식별자 매핑
→ PostgreSQL 저장
→ 기동 직후 + fixedDelay 주기 동기화

Live
가격 / 재고
→ 검색 시 Supplier 조회
→ 별도 저장하지 않음
```

### 4.1 동기화 시점

| 방식 | 장점 | 단점 | 판단 |
|---|---|---|---|
| 검색마다 Catalog 조회 | 항상 최신 | 모든 검색이 Catalog latency와 장애에 묶임 | 제외 |
| 기동 시 한 번 | 호출량 최소 | 신규·삭제 상품이 재기동 전까지 반영되지 않음 | 제외 |
| 주기 동기화만 | 자동 반영 | 첫 검색 전에 Catalog가 없을 수 있음 | 차선 |
| 기동 직후 + 주기 동기화 | 검색 경로와 분리하면서 변경 반영 | Scheduler 운영 추가 | 채택 |

`fixedRate` 대신 `fixedDelay`를 사용합니다.
정각 실행보다 이전 동기화가 끝난 뒤 다음 실행이 시작되는 것이 현재 구조에서는 더 중요하다고 판단했습니다.

동기화 주기는 운영 최적값이라고 주장하지 않습니다.
실제 Supplier의 Catalog freshness 기준이 생기면 다시 조정할 값입니다.

### 4.2 내부 ID 안정성

전체 삭제 후 재삽입은 단순하지만 같은 Supplier 상품의 내부 UUID가 매번 바뀔 수 있습니다.
항목별 existence query는 숙소 수만큼 DB round trip을 만들 수 있습니다.

현재는 `runId + upsert` 방식으로 내부 ID를 유지하면서 이번 snapshot에서 사라진 행만 비활성화합니다.

```text
1. 동기화마다 runId 발급
2. property upsert
3. 기존 internal ID 재조회
4. room type upsert
5. last_seen_run != currentRun 인 room type 비활성화
6. last_seen_run != currentRun 인 property 비활성화
7. catalog state 갱신
```

전체 적용은 하나의 DB transaction입니다.

| 장애 시점 | 결과 |
|---|---|
| fetch / validation 중 종료 | DB 변경 없음 |
| upsert / 비활성화 / state 갱신 중 종료 | rollback |
| commit 직후 종료 | 완전한 snapshot 유지 |
| Supplier A 동기화 실패 | A는 이전 snapshot 유지, 다른 Supplier 동기화는 계속 |
| 최초 동기화 실패 | 해당 Supplier는 `CATALOG_UNAVAILABLE` |

중간 상태를 나중에 보정하는 로직보다, 부분 상태 자체가 commit되지 않게 하는 편을 선택했습니다.

### 4.3 검색 대상 조회

검색 요청마다 활성 property와 room type 매핑을 하나의 `LEFT JOIN`으로 읽습니다.

숙소를 읽은 뒤 객실 타입을 다시 조회하면 DB 왕복이 늘고,
두 SELECT 사이에 Catalog sync가 commit될 경우 서로 다른 시점의 데이터를 조합할 수도 있습니다.

한 번의 조회로 검색 요청 하나가 사용할 Catalog snapshot을 확정했습니다.

---

## 5. 검색 — 실패를 한 덩어리로 처리하지 않기

검색은 세 단계로 실패 범위를 나눴습니다.

```text
Supplier
  └─ Batch
      └─ Item
```

현재 정책은 다음과 같습니다.

```text
Supplier A 실패
→ Supplier B 결과 유지

Supplier A batch 1 실패
→ A의 다른 batch 결과 유지

정규화 item 1건 실패
→ 같은 batch의 정상 item 유지
```

transport/HTTP/역직렬화 실패는 batch 응답 자체를 신뢰하기 어려워 batch 단위로 처리합니다.
역직렬화 이후의 필드 누락, 날짜 범위 불일치, 매핑 누락은 item 단위로 격리합니다.

### 5.1 결과 0건과 실패 0건은 다르게 봄

`items=[]`만으로 성공 여부를 판단하지 않습니다.

```text
SUCCESS              정상 조회
PARTIAL              일부 batch 실패
FAILED               호출했지만 실패
NO_TARGETS           Catalog는 정상이고 대상이 0개
CATALOG_UNAVAILABLE  Catalog sync에 아직 성공하지 못함
```

전 숙소가 만실이면 검색은 성공했다고 보고 `200 + items=[] + partial=false`로 반환합니다.

반대로 한 번도 Catalog를 확보하지 못한 Supplier가 있다면 역시 결과는 0개일 수 있지만,
검색 결과가 완전하다고 말할 수는 없습니다.

### 5.2 연박 재고

3박의 일별 재고가 `3, 1, 5`라면 예약 가능한 객실 수는 1개로 봅니다.

| 방식 | 결과 | 판단 |
|---|---:|---|
| 합계 | 9 | 연속 숙박 가능 수량이 아님 |
| 평균 | 3 | 둘째 날 재고를 무시 |
| 첫날 | 3 | 이후 날짜를 반영하지 않음 |
| **최솟값** | **1** | 모든 숙박일에 공통으로 존재하는 수량 |

또한 다음 상태를 분리했습니다.

```text
0     → 정상 sold-out
누락  → 날짜 범위 계약 불일치
음수  → 유효하지 않은 inventory
```

음수를 0으로 보정하면 Supplier 계약 위반이 정상 품절처럼 보일 수 있어 별도 reject 사유로 처리합니다.

---

## 6. 자원 상한과 실패 귀속

Supplier는 한 요청에 최대 50개 숙소 코드를 받습니다.
이 값은 운영 튜닝값이 아니라 외부 계약값이므로 adapter 내부 상수로 두었습니다.

```text
2,000 properties
→ 40 HTTP requests per Supplier
```

현재 주요 상한은 다음과 같습니다.

```text
bulk size                         50   CONTRACT
request-local batch concurrency    4   EVALUATION_DEFAULT
Supplier bulkhead                 16   EVALUATION_DEFAULT
HTTP max connections              24   EVALUATION_DEFAULT
pending acquire timeout         500ms  EVALUATION_DEFAULT
response timeout                   2s  EVALUATION_DEFAULT
```

Supplier의 실제 SLO와 rate limit이 없기 때문에 개별 숫자를 운영 최적값이라고 보지는 않았습니다.
대신 값 사이의 관계를 코드에서 검사합니다.

```text
batchConcurrency <= bulkheadMaxConcurrentCalls < maxConnections
pendingAcquireTimeout < responseTimeout
```

의도는 전송 계층 pool이 예상하지 못한 첫 번째 병목이 되지 않도록 하는 것입니다.

### 6.1 우리 포화를 Supplier timeout으로 기록하지 않기

외부 연동에서 실패를 잡는 것만큼 **누구의 문제인지 정확히 기록하는 것**도 중요하다고 생각했습니다.

예를 들어 Reactor Netty의 pool acquire timeout은 `TimeoutException` 계층에 포함될 수 있습니다.
상속만 보고 timeout으로 분류하면 우리 connection pool 고갈이 Supplier 지연으로 기록될 수 있습니다.

현재 실패를 다음처럼 분류합니다.

```text
TIMEOUT            Supplier 응답 지연
UNAVAILABLE        Supplier 연결 실패 / 5xx
RATE_LIMITED       Supplier 429
INVALID_RESPONSE   Supplier 응답 계약 불일치
LOCAL_SATURATION   우리 bulkhead / connection pool 포화
INTERNAL_ERROR     우리 코드 / 요청 조립 문제
```

장애 사유에 따라 확인해야 할 대상이 달라지기 때문에 같은 `timeout` 계열이라는 이유만으로 묶지 않았습니다.

### 6.2 수평 확장 시 다시 봐야 하는 경계

현재 Bulkhead와 connection pool은 instance-local입니다.

```text
replica 1 × bulkhead 16 → Supplier당 최대 약 16 concurrent calls
replica 4 × bulkhead 16 → Supplier당 최대 약 64 concurrent calls
```

따라서 scale-out은 애플리케이션 인스턴스를 보호할 수는 있어도 Supplier의 global quota를 자동으로 보호하지는 않습니다.

다중 인스턴스에서는 다음을 다시 검토할 필요가 있다고 보았습니다.

- Supplier별 전체 QPS / concurrency budget
- replica별 budget 분배
- cluster-wide rate limiting
- Catalog scheduler 중복 실행
- distributed lock / leader election / 별도 sync worker

---

## 7. 확장 설계와 현재 미도입 항목

기술을 많이 추가하는 것보다, 현재 근거로 설명할 수 있는 복잡도까지만 구현하려고 했습니다.

| 항목 | 현재 상태 | 현재 판단 | 다시 볼 조건 |
|---|---|---|---|
| Resilience4j | **구현** | Supplier별 Bulkhead만 사용 | 반복 장애 데이터가 생기면 CB 추가 검토 |
| Retry | **설계만** | 장애 중 호출량 증폭 우려로 자동 retry 미도입 | transient failure·retry budget·quota 확보 |
| CircuitBreaker | **설계만** | threshold/open duration 근거가 없어 미도입 | 장애율·복구시간 분포 확보 |
| 가격·재고 Cache | **설계만** | stale 허용 기준과 재검증 지점이 없어 미도입 | 예약 전 price check·Supplier quota 확보 |
| 정규화 실패 격리 | **부분 구현** | item 격리 + reason metric/log | 사후 원인 분석 수요가 커지면 bounded quarantine 저장 |
| 중복 숙소 병합 | **정책 구현** | 공통 키가 없어 별도 노출 | canonical property identity 확보 |
| 통화 | **구현 + 설계** | 원본 통화 보존, FX 미변환 | 환율 source·기준시각·rounding 계약 확보 |
| 예약 대행 | **설계만** | 조회와 write workflow를 분리 | 예약 기능이 실제 범위에 들어올 때 |

상세 설계는 [`docs/EXTENDED_DESIGN.md`](docs/EXTENDED_DESIGN.md)에 적었습니다.

---

## 8. 관측 지표

| 지표 | label | 보는 것 |
|---|---|---|
| `supplier_search_total` | supplier, status, reason | Supplier별 성공률과 실패 사유 |
| `supplier_call_duration_seconds` | supplier, outcome | Supplier 호출 latency |
| `supplier_adapter_error_total` | supplier | 가용성을 위해 catch한 pipeline 오류 |
| `stay_offer_rejected_total` | supplier, reason | 정규화 거부 item 수와 원인 |
| `stay_offer_filtered_total` | reason | sold-out으로 응답에서 제외된 상품 수 |
| `catalog_sync_failure_total` | supplier | Catalog 동기화 실패 |
| `catalog_snapshot_size` | supplier | 마지막 동기화에서 적용된 활성 숙소 수 |
| `catalog_last_success_age_seconds` | supplier | 마지막 Catalog 성공 이후 지난 시간 |

성공률만 있으면 실패는 보이지만 느려지는 흐름은 늦게 보일 수 있습니다.
그래서 Supplier 호출 시간도 별도로 기록합니다.

Catalog 동기화 실패 시 이전 snapshot으로 검색을 계속할 수 있는 대신,
오래된 snapshot으로 조용히 서비스할 가능성이 있어 마지막 성공 이후 경과 시간도 확인합니다.

현재 `catalog_last_success_age_seconds`는 **현재 프로세스에서 동기화가 한 번 성공한 뒤부터** 등록됩니다.
재기동 직후 첫 sync가 실패하면 DB의 이전 snapshot으로 검색은 가능하지만 gauge는 아직 복원되지 않습니다.
운영 단계에서는 DB의 `last_success_at`을 기동 시 읽어 초기화하는 방향을 검토할 수 있습니다.

---

## 9. 신규 Supplier 추가 범위

완전한 런타임 플러그인 시스템을 만들지는 않았습니다.
현재 시스템은 코드 추가와 재배포를 전제로 한 정적 확장입니다.

Supplier C를 추가한다면 주로 다음이 추가됩니다.

```text
supplier-client/.../c/
├── C request/response DTO
├── CNormalizer
├── CSearchAdapter
└── CCatalogAdapter

SupplierIds
└── C 식별자

SupplierClientConfiguration
├── C WebClient
├── SupplierSearchPort bean
└── CatalogPort bean

application-*.yaml / SupplierProperties
└── C endpoint / credential
```

반대로 다음 영역은 Supplier C의 계약을 알지 않도록 두었습니다.

```text
domain
SearchStaysService
CatalogSyncService
JdbcCatalogSnapshotStore
기존 A/B adapter
검색 API 응답 계약
```

"새 Supplier를 추가해도 아무 파일도 수정하지 않는다" 수준의 OCP를 목표로 하지는 않았습니다.
런타임 플러그인 구조는 설정·로딩·버전 호환성이라는 별도 운영 비용이 생기기 때문에 현재 범위에서는 과하다고 판단했습니다.

---

## 10. 기술 선택

| 선택 지점 | 후보 | 선택 | 이유 | 감수한 점 |
|---|---|---|---|---|
| Language | Java / Kotlin | Java 21 | record, sealed type으로 값과 결과 상태를 충분히 표현할 수 있다고 보았습니다 | Kotlin의 null safety와 DSL 표현력 |
| Spring Boot | 3.x / 4.1.1 | Spring Boot 4.1.1 | 새 서비스라 migration 부담이 없고 Boot 4 조합을 테스트로 고정했습니다 | 3.x의 더 오래 검증된 생태계 |
| OpenAPI | springdoc 2.x / 3.x | springdoc 3.1.1 | Boot 4 호환 라인을 사용하고 실제 schema와 Swagger UI를 smoke로 확인합니다 | 최신 조합이라 계약 테스트가 더 중요합니다 |
| Web stack | MVC / Full WebFlux | MVC + `Mono` 반환 | 긴 대기는 Supplier HTTP I/O이고 해당 구간은 이미 WebClient로 non-blocking입니다 | 요청 처리 전체가 reactive stack은 아닙니다 |
| HTTP client | RestClient / WebClient | WebClient | 병렬 호출, timeout, Bulkhead, 부분 실패를 같은 흐름에 둘 수 있습니다 | 동기 코드보다 디버깅이 어렵습니다 |
| Persistence | JPA / JDBC | JdbcClient + JdbcTemplate | bulk upsert·비활성화·snapshot 조회가 중심이라 SQL을 명시하는 편이 의도를 보여주기 쉽습니다 | JPA의 변경 감지·연관 매핑 |
| Database | H2 / PostgreSQL | PostgreSQL 17.9 | `ON CONFLICT`, 부분 인덱스, rollback을 실제 DB에서 확인합니다 | 로컬·CI에서 Docker 필요 |
| Schema | Hibernate DDL / Flyway | Flyway | 앱과 테스트가 같은 schema 생성 경로를 사용합니다 | migration 관리가 필요합니다 |
| Mock | 같은 app / 별도 app | 별도 `:9090` | 외부 무응답과 우리 서버 자원 고갈을 분리해 재현하려고 했습니다 | 실행 프로세스 하나 증가 |

---

## 11. 검증

```bash
./gradlew clean check      # 163 tests
```

```text
[app]              ApiErrorContractTest 7  ArchitectureTest 8  CatalogSyncSchedulerTest 4
                   DependencyUnavailableTest 1  MicrometerSearchTelemetryTest 1
                   OpenApiDocumentTest 5  SearchEndToEndTest 7
                   SearchHttpStatusTest 10  SearchResponseAssemblerTest 7
                   SupplierPropertiesTest 3
[application]      CatalogSnapshotValidatorTest 7  CatalogSyncServiceTest 3
                   SearchStaysServiceTest 7
[domain]           InventoryRuleTest 11  MoneyTest 4  StayPeriodTest 3  StayPriceTest 3
[storage]          CatalogQueryScaleTest 2  CatalogReadFailureTest 1  JdbcCatalogQueryTest 5
                   JdbcCatalogSnapshotStoreTest 7  SchemaMigrationTest 4
[supplier-client]  ANormalizerTest 7  BNormalizerTest 7  BatchingTest 4  CatalogAdapterTest 10
                   FailureAttributionTest 3  JacksonContractTest 4
                   SupplierSearchAdapterTest 15  WebClientCodecTest 3
```

| 검증 | 확인하는 것 |
|---|---|
| Domain unit | 가격 계산, 통화 불일치, 연박 재고 `min`, 잘못된 기간 |
| Application unit | Supplier별 부분 실패, pipeline 예외 격리, Catalog validation |
| WireMock | 50개 batching, timeout, body-level failure, 포화 귀속 |
| Testcontainers PostgreSQL | `ON CONFLICT`, rollback, 내부 ID 안정성, snapshot 조회 |
| App E2E | 실제 HTTP 검색, error contract, OpenAPI schema |
| ArchUnit | domain/application 순수성, DTO 누수, 순환 참조, 검색 경로 `block()` 금지 |
| k6 | 의도한 자원 상한이 실제 구속 조건인지 확인 |

부하 측정 조건과 실행 캡처는 [`docs/MEASUREMENTS.md`](docs/MEASUREMENTS.md)에 남깁니다.

문서의 테스트 집계가 실제 결과와 달라지지 않도록 다음 스크립트를 둡니다.

```bash
scripts/check-doc-test-counts.sh
```

README에 적은 시연 절차도 실제 HTTP로 확인합니다.

```bash
scripts/verify-demo.sh
```

확인 항목에는 다음이 포함됩니다.

```text
정상 검색 429,000 / 452,000
A의 일별 분해·세액 보존
B의 unknown 값 null 유지
연박 재고 min
A 무응답 → B 결과 200 partial
B HTTP 200 + 실패 resultCode → 실패 판정
둘 다 실패 → 5xx
OpenAPI / Swagger UI
400 / 404 / 405 오류 계약
```

### 테스트가 실제 회귀를 잡는지 확인

테스트 개수만 늘리기보다 주요 규칙을 일부러 깨뜨렸을 때 관련 검증이 실패하는지도 확인했습니다.

| 일부러 바꿔 본 것 | 깨지는 검증 |
|---|---|
| 연박 재고 `min → max` | Inventory test |
| batch 안쪽 `onErrorResume` 제거 | partial failure test |
| `flatMap` 동시성 상한 제거 | concurrency test |
| `ON CONFLICT`에서 기존 ID까지 갱신 | ID stability test |
| Normalizer에 Reactor 타입 추가 | ArchUnit |
| AI Skill 한쪽만 변경 | pre-commit / CI |

---

## 12. 개발·검토 기준과 AI 활용

AI는 구현을 대신 결정하도록 두기보다, 놓칠 수 있는 대안과 실패 시나리오를 넓게 검토하는 데 사용했습니다.

### 12.1 공통 기준 — `AGENTS.md`

```text
- 다른 선택지는 무엇인가?
- "하지 않는다"도 선택지로 검토했는가?
- 선택하면 무엇을 얻고 무엇을 잃는가?
- 숫자는 CONTRACT / MEASURED / EVALUATION_DEFAULT 중 무엇인가?
- 단계 사이 process가 종료되면 무엇이 남는가?
- 문서의 주장은 코드나 테스트로 확인 가능한가?
```

### 12.2 작업별 Skill — Codex / Claude Code

```text
.codex/skills/                 .claude/skills/
├── failure/SKILL.md           ├── failure/SKILL.md
├── numbers/SKILL.md           ├── numbers/SKILL.md
├── structure/SKILL.md         ├── structure/SKILL.md
└── supplier/SKILL.md          └── supplier/SKILL.md
```

각 `SKILL.md`의 `description`에는 어떤 변경에서 해당 기준을 적용할지 적었습니다.

| Skill | 먼저 확인하는 질문 | 실제 반영 예 |
|---|---|---|
| `failure` | 실패가 누구의 문제인지, 어디까지 격리할지 | local pool 포화와 Supplier timeout 분리 |
| `numbers` | 숫자의 출처와 값 사이의 순서 | batch ≤ bulkhead < connections 검증 |
| `structure` | 타입 소유권과 의존 방향 | 패키지 순환을 ArchUnit으로 확인 |
| `supplier` | Supplier 계약 차이가 어디까지 번지는지 | DTO/Normalizer/Adapter를 adapter 계층에 격리 |

AI가 제안한 기술도 바로 채택하지 않았습니다.

```text
Retry
→ 장애 중 호출량은 얼마나 증가하는가?
→ quota와 retry budget을 알고 있는가?

CircuitBreaker
→ threshold와 open duration을 정할 데이터가 있는가?

Redis
→ stale 값을 어디에서 재검증하는가?
→ cache miss storm을 누가 감당하는가?
```

두 도구의 Skill이 따로 변하지 않도록 `scripts/check-agent-guides.sh`에서 목록, 내용 diff,
참조 존재 여부, `name`/`description` frontmatter를 확인하고 pre-commit과 CI에서 실행합니다.

과정에서 수용·수정·거부한 판단은 [`JOURNAL.md`](JOURNAL.md)에 별도로 기록했습니다.

---

## 13. 실행

### 전체를 컨테이너로 실행

JDK 없이 Docker만으로 실행합니다.

```bash
cp .env.sample .env
docker compose -f compose.full.yaml up --build
```

| 항목 | URL |
|---|---|
| Search API | `http://localhost:8080/api/v1/stays/search?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0` |
| Swagger UI | `http://localhost:8080/swagger-ui/index.html` |
| OpenAPI | `http://localhost:8080/v3/api-docs` |

### 로컬 개발

Docker와 JDK 21이 필요합니다.

```bash
docker compose up -d              # PostgreSQL (host 55432)
./gradlew :mock-supplier:bootRun  # Mock Supplier (:9090)
./gradlew :app:bootRun            # App (:8080)
```

프로파일을 주지 않으면 `local`로 실행됩니다.
`prod`에서는 DB와 Supplier 접속 정보를 명시적으로 주입하도록 했습니다.
운영 환경에서 필요한 값이 빠진 상태로 개발용 기본값이 사용되면 설정 오류를 늦게 발견할 수 있다고 생각했습니다.

### 검증 명령

```bash
./gradlew spotlessApply
./gradlew clean check
scripts/verify-demo.sh
```

---

## 14. 현재 범위의 경계

현재 구현은 Catalog 동기화와 통합 검색 흐름에 집중합니다.

```text
구현하지 않은 범위
- 인증/인가
- 결제
- 예약 생성/취소
- 정규화 실패 원본 payload 저장소
- canonical property matching
- distributed scheduler coordination
- price/inventory cache
```

이 범위를 구현하지 않았다고 해서 필요성이 없다고 보지는 않았습니다.
오히려 예약이나 다중 인스턴스 같은 전제가 추가되면 현재 선택 중 일부가 달라질 수 있다고 보고,
그 조건을 [`docs/EXTENDED_DESIGN.md`](docs/EXTENDED_DESIGN.md)에 남겼습니다.
