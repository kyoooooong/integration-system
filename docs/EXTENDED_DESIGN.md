# Extended Design Notes

현재 구현 범위를 넘어갈 때 다시 검토해야 할 설계를 정리합니다.
이 문서는 "미구현 목록"이 아니라 **어떤 전제가 추가되면 현재 선택을 어떻게 바꿀지**를 기록하기 위한 문서입니다.

---

## 1. Retry

현재 검색 경로에는 자동 Retry를 넣지 않았습니다.

### 이유

조회는 GET이므로 재시도 자체는 가능하지만 장애 상황에서 호출량이 증가합니다.

```text
1회 호출 + retry 0회 → 최대 1회 upstream request
1회 호출 + retry 1회 → 최대 2회 upstream request
```

동시 검색이 많을수록 장애 중인 Supplier에 추가 부하를 만들 수 있습니다.

### 도입 조건

다음 정보가 생기면 재검토합니다.

- transient failure 비율
- Supplier별 rate limit
- `Retry-After` 또는 동등한 quota 신호
- 전체 request deadline
- retry budget

### 도입한다면

HTTP status가 아니라 내부 `FailureType`을 기준으로 대상을 제한하는 편을 우선 검토합니다.

```text
후보
TIMEOUT 일부
UNAVAILABLE 일부

원칙적으로 제외
INTERNAL_ERROR
INVALID_RESPONSE
LOCAL_SATURATION
```

429는 무조건 retry하지 않고 Supplier가 retry timing을 제공하는지 먼저 확인합니다.

횟수 제한 + exponential backoff + jitter를 사용하고 전체 request deadline을 넘기지 않도록 합니다.

---

## 2. CircuitBreaker

CircuitBreaker는 timeout이나 Bulkhead의 대체가 아닙니다.

```text
Timeout        → 단일 호출 대기 상한
Bulkhead       → 동시 호출 상한
CircuitBreaker → 반복 실패 Supplier 호출 자체를 잠시 중단
```

### 현재 미도입 이유

정책을 정하려면 다음 값이 필요합니다.

- sliding window
- minimum calls
- failure threshold
- slow-call threshold
- open duration
- half-open calls

현재 실제 Supplier 장애율과 복구 시간 분포가 없어 기본값을 그대로 쓰는 근거가 약하다고 보았습니다.

### 도입 조건

동일 Supplier의 `TIMEOUT` / `UNAVAILABLE`이 반복되고,
매 검색마다 같은 실패 비용을 계속 지불하는 것이 지표로 확인될 때 검토합니다.

### 실패 집계

CB를 추가한다면 우리 자원 문제를 upstream 장애로 세지 않도록 합니다.

```text
CB failure 후보
TIMEOUT
UNAVAILABLE

별도 취급
RATE_LIMITED
INVALID_RESPONSE

CB failure에서 제외
LOCAL_SATURATION
INTERNAL_ERROR
```

---

## 3. 가격·재고 Cache

현재 정적 Catalog만 저장하고 가격·재고는 live 조회합니다.

### 도입 전 필요한 기준

```text
stale price 허용 시간
stale inventory 허용 시간
예약 직전 재검증 여부
cache key cardinality
Supplier quota
cache miss 폭주 제어
```

### Cache key

가격과 재고가 검색 조건에 종속적이라면 단순 `propertyId` cache로는 부족합니다.

예시:

```text
supplier
property set / property
checkIn
checkOut
adults
children
```

키 cardinality가 커질 수 있으므로 실제 query repetition과 hit ratio를 먼저 확인해야 합니다.

### 장애 시

Redis 장애 시 무조건 fail-open하면 다음 문제가 생길 수 있습니다.

```text
Redis down
→ all instances miss
→ Supplier direct calls spike
→ upstream quota 초과
```

따라서 cache를 추가한다면 함께 검토합니다.

- single-flight
- admission control
- stale-while-revalidate 가능 여부
- per-Supplier rate limit
- bounded fail-open

---

## 4. 정규화 실패 Quarantine

현재 구현:

```text
item 단위 reject
RejectReason 분류
supplier/reason metric
배치 단위 집계 log
```

현재 미구현:

```text
원본 payload 영속 보관
```

### 왜 바로 저장하지 않았는가

원본 payload를 저장하면 다음 운영 기준이 필요합니다.

- 민감정보 redaction
- retention
- 저장 용량
- 중복 제거
- 접근 권한
- quarantine 저장 실패 처리

### 도입한다면

동기 검색 경로를 막지 않는 bounded async sink를 우선 검토합니다.

예시 metadata:

```text
supplier
receivedAt
external property/room key
rejectReason
detail
payloadHash
sanitizedPayload(optional)
```

raw payload 보존이 꼭 필요한지 먼저 확인하고,
가능하면 최소 diagnostic 정보만 남기는 편을 우선합니다.

---

## 5. 중복 숙소 병합

현재 Supplier별 상품을 따로 노출합니다.

### 현재 병합하지 않는 이유

공통 canonical key가 없습니다.

이름 기반 매칭은 다음 오류를 만들 수 있습니다.

- 동명이인 숙소
- 표기 차이
- 언어 차이
- 객실 타입명 차이
- 같은 숙소지만 다른 판매 조건

property가 같더라도 offer까지 같은 것은 아닙니다.

### 향후 병합한다면

두 단계를 분리합니다.

```text
Property identity
→ 같은 숙소인가?

Offer identity
→ 조식 / 취소 / 객실 타입 / 조건까지 같은 상품인가?
```

canonical property source나 검증 가능한 mapping이 확보되기 전까지는 자동 병합을 기본 동작으로 두지 않습니다.

---

## 6. Currency / FX

현재 각 상품의 ISO 4217 통화를 그대로 보존하고, 서로 다른 통화끼리 합산하지 않습니다.

### FX 변환을 하지 않는 이유

환율 변환에는 숫자 외에도 다음 계약이 필요합니다.

```text
FX source
quote timestamp
base/quote currency
rounding
conversion fee 포함 여부
FX source 장애 시 정책
```

### 향후 표시 통화를 통일한다면

원본 금액은 보존하고 변환값을 별도 필드로 추가하는 방향을 우선 검토합니다.

```text
originalAmount
originalCurrency
displayAmount
displayCurrency
fxRate
fxQuotedAt
```

원본 금액을 덮어쓰지 않습니다.

---

## 7. Reservation Workflow

현재 검색은 읽기 흐름이므로 외부 write와 내부 write 사이의 dual-write 문제가 없습니다.

예약이 들어오면 다음 실패가 생깁니다.

```text
Supplier 예약 성공
→ local DB commit 전 process 종료
```

### 기본 흐름 후보

```text
1. client idempotency key 수신/발급
2. reservation intent를 PENDING으로 local DB에 선기록
3. 예약 직전 price/inventory revalidation
4. Supplier create reservation 호출
5. 성공 시 Supplier reservation id 저장 + CONFIRMED
6. timeout/unknown 결과는 UNKNOWN/PENDING 상태로 두고 reconciliation
7. 명확한 실패는 FAILED
```

### 왜 blind retry를 피하는가

예약 API timeout은 "실패"가 아니라 "결과를 모름"일 수 있습니다.

```text
Supplier는 예약 성공
응답만 유실
→ 같은 요청을 무조건 재시도
→ 중복 예약 가능
```

따라서 idempotency key나 Supplier-side lookup이 없는 write를 timeout만 보고 재시도하지 않습니다.

### Reconciliation

주기적으로 PENDING/UNKNOWN 상태를 확인합니다.

```text
local intent
→ Supplier reservation lookup
→ 존재하면 CONFIRMED
→ 명확히 없고 retry 가능한 경우 정책에 따라 재시도
→ 장시간 미확정은 운영 확인
```

### Cancel / Compensation

취소 역시 별도 상태 전이로 다룹니다.

```text
CONFIRMED
→ CANCEL_REQUESTED
→ Supplier cancel
→ CANCELLED
```

Supplier 취소 성공 후 local commit이 실패할 수 있으므로 reconciliation 대상에 포함합니다.

---

## 8. 다중 인스턴스

현재 Supplier Bulkhead와 connection pool은 instance-local입니다.

### Scale-out 시 달라지는 것

```text
replicas 증가
→ Supplier global call budget 증가
→ Catalog scheduler도 replica 수만큼 실행
```

### 추가 설계 후보

Supplier 호출:

- replica별 budget 배분
- cluster-wide rate limiter
- Supplier별 quota config

Catalog:

- leader election
- distributed lock
- 별도 sync worker

단순히 replica를 늘리는 것을 전체 문제의 해결책으로 보지 않습니다.

---

## 9. Full WebFlux / Virtual Thread

현재 MVC controller가 `Mono`를 반환하고 Supplier I/O는 WebClient로 non-blocking 처리합니다.

### Full WebFlux 재검토 조건

- servlet thread가 실제 병목으로 관측됨
- JDBC를 포함한 blocking boundary를 명확히 offload하거나 R2DBC로 전환할 이유가 생김
- 전체 운영 모델을 reactive로 통일할 이득이 커짐

### Virtual Thread 재검토 조건

- blocking Supplier SDK나 blocking 외부 연동이 늘어남
- reactive pipeline보다 동기 코드 단순성이 더 큰 이득이 됨

Java 21을 사용한다는 이유만으로 Virtual Thread를 추가하지는 않습니다.
