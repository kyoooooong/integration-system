# JOURNAL — Decision & Progress Record

이 문서는 구현 과정에서 **어떤 선택지를 검토했고, 어떤 근거로 현재 구조를 선택했는지**를 정리한 기록입니다.

각 단계에서 처음 고려한 방법과 변경한 이유, 감수한 비용, 이후 다시 검토할 조건을 함께 남겼습니다.

구현 과정에서는 다음 질문을 반복해서 확인했습니다.

1. 기술을 추가하기 전에 현재 문제를 더 단순하게 풀 수 있는가?
2. 선택했을 때 얻는 것뿐 아니라 잃는 것도 설명할 수 있는가?
3. 장애는 어느 범위까지 전파되고 누구의 문제로 기록되는가?
4. 숫자는 계약값인가, 측정값인가, 현재 환경의 초기 보호값인가?
5. 현재 선택은 어떤 조건에서 다시 바뀌어야 하는가?

---

## 진행 흐름

| 단계 | 작업 | 이 단계에서 달라진 판단 |
|---|---|---|
| 1 | Supplier 계약 비교와 표준 모델 설계 | 모든 필드를 같게 만드는 것보다 무손실 변환 방향을 우선 |
| 2 | Catalog 매핑 모델 설계 | 전체 delete/insert보다 stable internal ID를 유지하는 upsert 방식 선택 |
| 3 | Catalog lifecycle 구현 | 검색마다 목록 조회하지 않고 기동 직후 + 주기 sync로 분리 |
| 4 | WebClient Adapter 구현 | Supplier DTO·응답 봉투·실패 표현을 adapter 안에서 소거 |
| 5 | 병렬 검색 구현 | 단순 병렬 호출에서 50개 batching + request-local concurrency로 제한 |
| 6 | 부분 실패 검증 | Supplier / batch / item 세 단계로 격리 범위를 세분화 |
| 7 | 자원 포화 검증 | Supplier timeout과 우리 local saturation을 따로 분류 |
| 8 | 확장 기술 검토 | Retry/CB/Redis/lock을 후보로 검토했지만 근거 없는 정책값은 미도입 |
| 9 | 검증 강화 | 실제 PostgreSQL·WireMock·E2E·ArchUnit·k6로 주요 가정 확인 |
| 10 | AI 검토 체계 정리 | 공통 기준과 작업별 Skill을 저장소에 두고 두 도구 간 drift를 자동 검사 |

---

# 1. 가격 모델 — 필드를 맞추는 것보다 정보 손실을 먼저 봄

## 처음 본 문제

두 Supplier의 가격 응답은 단순히 필드명이 다른 것이 아니었습니다.

```text
Supplier A
날짜별 1박 금액 + 세액

Supplier B
숙박 기간 전체 총액, 세금 포함
```

처음에는 공통 DTO의 필드를 최대한 동일하게 채우는 방식도 생각했습니다.
하지만 B의 총액을 숙박일 수로 나누면 실제로 존재하지 않는 일별 가격을 만들게 됩니다.

## 검토한 선택지

| 선택지 | 장점 | 단점 |
|---|---|---|
| 일별 가격으로 통일 | 응답 모델이 단순 | B에 없는 일별 가격을 만들어야 함 |
| 총액만 유지 | 모든 Supplier를 정확히 표현 | A의 일별 가격·세액을 버림 |
| 총액 필수 + 상세 optional | 공통 정보와 Supplier별 추가 정보 보존 | optional 처리가 필요 |

## 선택

`총액 필수 + 상세 optional`을 선택했습니다.

```text
totalAmount        required
currency           required
taxAmount          optional
nightlyBreakdown   optional
```

`taxAmount = null`은 `0원`이 아니라 "알 수 없음"으로 유지했습니다.

## 사고가 확장된 지점

이 원칙을 정규화 전반으로 확장했습니다.

```text
Supplier가 0이라고 줌      → 0
Supplier가 값을 안 줌      → unknown
계약상 불가능한 값을 줌     → reject
```

형식을 예쁘게 맞추는 것보다 원본 의미를 보존하는 편을 우선했습니다.

---

# 2. 통화 — 지원과 변환을 같은 문제로 보지 않음

금액 모델에 ISO 4217 `Currency`를 함께 보존했습니다.

처음에는 예시가 KRW이므로 통화를 문자열 필드 정도로 둘 수도 있었습니다.
하지만 서로 다른 통화가 들어왔을 때 합산이 조용히 일어나면 금액 오류가 더 위험하다고 생각했습니다.

그래서 `Money.plus()`는 같은 통화끼리만 허용하고 통화가 다르면 실패하도록 했습니다.

반대로 FX 변환까지 넣지는 않았습니다.

FX를 하려면 최소한 다음 기준이 필요하기 때문입니다.

```text
환율 source
quote timestamp
base / quote currency
rounding rule
환율 실패 시 정책
```

이 정보 없이 "현재 환율"을 임의로 적용하는 것은 가격 정확성 측면에서 더 위험하다고 판단했습니다.

---

# 3. 정적 Catalog와 실시간 가격·재고를 분리

## 처음 생각한 방식

가장 단순한 검색은 매 요청마다 다음을 모두 호출하는 방식입니다.

```text
Catalog 조회
→ 가격·재고 조회
→ 병합
```

하지만 숙소 목록과 가격·재고는 변경 주기가 다릅니다.

```text
숙소 / 객실 타입   → 비교적 느리게 변경
가격 / 재고        → 검색 시점과 조건에 따라 변경
```

## 선택지

| 방식 | 장점 | 단점 |
|---|---|---|
| 검색마다 Catalog 조회 | 항상 최신 | 검색 latency와 Catalog 장애가 결합 |
| 기동 시 한 번 | 호출량 최소 | 신규 상품이 재기동 전까지 미반영 |
| 주기 sync | 검색과 분리 | 첫 sync 전에는 대상 없음 |
| 기동 직후 + 주기 sync | 초기 확보 + 지속 갱신 | Scheduler 운영 필요 |

`기동 직후 + fixedDelay 주기 sync`를 선택했습니다.

검색의 critical path에서 정적 API를 제외하면서도 변경을 계속 반영할 수 있다고 보았습니다.

---

# 4. Catalog 갱신 — stable ID와 쓰기 비용을 함께 봄

## 문제

Catalog를 전체 삭제 후 다시 저장하면 구현은 단순합니다.
하지만 같은 Supplier 상품의 내부 UUID가 바뀔 수 있고 전체 행을 다시 쓰게 됩니다.

## 선택지

| 방식 | 장점 | 단점 |
|---|---|---|
| delete + insert | 단순 | ID 안정성·쓰기량 문제 |
| 항목별 SELECT + update/insert | 직관적 | 숙소 수만큼 DB 왕복 |
| `NOT IN (전체 코드)` | set 기반 | 코드 수만큼 bind parameter 증가 |
| `runId + upsert` | ID 유지 + set 기반 비활성화 | run 개념 추가 |

`runId + upsert`를 선택했습니다.

```text
1. runId 발급
2. property upsert
3. 기존 internal ID 유지
4. room type upsert
5. 이번 run에서 못 본 room type 비활성화
6. 이번 run에서 못 본 property 비활성화
7. catalog state 갱신
```

## 장애 순간까지 확인

```text
fetch 중 종료
→ DB 변경 없음

upsert 중 종료
→ rollback

비활성화 중 종료
→ rollback

state 갱신 중 종료
→ rollback

commit 직후 종료
→ 완전한 snapshot
```

부분 적용 후 정리하는 보정 코드보다, 부분 상태 자체가 commit되지 않게 하는 편이 현재 규모에서는 더 단순하다고 판단했습니다.

---

# 5. 연박 재고 — 숫자 하나로 표현할 때 무엇이 의미를 보존하는가

예를 들어 3박의 일별 재고가 다음과 같다고 했습니다.

```text
3, 1, 5
```

| 방식 | 결과 | 문제 |
|---|---:|---|
| 합계 | 9 | 연속 숙박 가능한 수량이 아님 |
| 평균 | 3 | 둘째 날 재고를 무시 |
| 첫날 | 3 | 이후 날짜를 반영하지 않음 |
| **최솟값** | **1** | 모든 숙박일에 공통으로 존재 |

그래서 `min(remainingRooms)`을 선택했습니다.

또한 세 상태를 분리했습니다.

```text
0     → 정상 sold-out
누락  → 날짜 coverage 불일치
음수  → invalid inventory
```

음수를 0으로 보정하면 계약 위반이 정상 품절처럼 보이기 때문에 별도 reject 사유로 남겼습니다.

`min → max`로 일부러 변경했을 때 관련 테스트가 실패하는지도 확인했습니다.

---

# 6. 실패를 Supplier / Batch / Item으로 나눔

병렬 호출을 구현했다고 부분 실패가 자동으로 해결되지는 않았습니다.
특히 `onErrorResume`의 위치에 따라 batch 하나의 실패가 다른 batch를 취소할 수 있었습니다.

```text
Supplier
  └─ Batch
      └─ Item
```

현재 정책은 다음과 같습니다.

```text
Supplier A 실패
→ B 결과 유지

A의 batch 하나 실패
→ A의 다른 batch 결과 유지

정규화 item 하나 실패
→ 같은 batch 정상 item 유지
```

## 사고가 확장된 지점

처음에는 `offers.isEmpty()`를 검색 성공 판단에 사용할 수도 있었습니다.
하지만 전 숙소가 정상적으로 sold-out이어도 결과는 0개입니다.

그래서 "상품 개수"와 "Supplier 호출 상태"를 분리했습니다.

```text
전부 sold-out
→ 성공 / items=[]

Catalog 미확보
→ 결과는 없지만 완전한 검색이 아님

Supplier 호출 실패
→ FAILED/PARTIAL
```

---

# 7. 정규화 실패를 어디까지 격리할지

정규화에 실패한 item 하나 때문에 batch 전체를 버리지 않도록 했습니다.

현재 구현은 다음 수준입니다.

```text
item 단위 격리
+ RejectReason 분류
+ supplier/reason metric
+ 배치 단위 집계 로그
```

원본 Supplier payload를 별도 저장소에 보관하지는 않았습니다.

원본 보관을 추가하면 다음 문제도 함께 생긴다고 보았습니다.

```text
민감정보 가능성
보존 기간
저장 용량
redaction
중복 payload
장애 시 quarantine 저장 자체의 실패
```

사후 분석 수요가 커지면 bounded async sink와 TTL이 있는 diagnostic 저장소를 별도 도입하는 편이 낫다고 판단했습니다.

---

# 8. Supplier 장애와 우리 자원 포화를 분리

처음에는 timeout 계열 예외를 하나로 분류하는 방식이 단순해 보였습니다.

하지만 connection pool acquire timeout 역시 `TimeoutException` 계층일 수 있습니다.

```text
우리 HTTP pool 고갈
→ TimeoutException
→ Supplier TIMEOUT으로 잘못 집계
```

이렇게 되면 지표는 Supplier를 가리키지만 실제로는 우리 자원 설정을 확인해야 합니다.

현재는 다음처럼 나눕니다.

```text
TIMEOUT
UNAVAILABLE
RATE_LIMITED
INVALID_RESPONSE
LOCAL_SATURATION
INTERNAL_ERROR
```

한 계층에서 이 문제를 발견한 뒤 Hikari connection wait와 async timeout에서도 같은 패턴이 있는지 확인했습니다.

한 예외 타입만 고치는 것보다 **같은 원인 구조가 다른 계층에서도 반복되는지**를 확인하려고 했습니다.

---

# 9. 숫자를 최적값처럼 보이지 않게 분류

외부 연동에는 여러 숫자가 필요했습니다.

```text
batch size
batch concurrency
bulkhead
connection pool
pending acquire timeout
response timeout
catalog sync interval
```

Supplier의 실제 SLO와 quota는 주어지지 않았기 때문에 숫자를 세 종류로 나눴습니다.

### CONTRACT

외부 계약이 정한 값.

```text
batch size = 50
```

### DESIGN_INVARIANT

값 자체보다 관계가 중요한 것.

```text
batchConcurrency <= bulkhead < maxConnections
pendingAcquireTimeout < responseTimeout
```

### EVALUATION_DEFAULT

운영 최적값이 아니라 현재 환경의 초기 보호값.

```text
response timeout
bulkhead size
connection pool size
catalog sync interval
```

관계가 깨지면 기동 시점에 실패하도록 설정 검증도 추가했습니다.

부하 테스트는 "최대 몇 RPS"를 주장하기보다 의도한 Bulkhead가 실제 첫 번째 구속 조건으로 동작하는지 확인하는 데 사용했습니다.

---

# 10. Redis 가격·재고 Cache를 넣지 않은 과정

Redis는 반복 검색 latency와 Supplier 호출량을 줄일 수 있기 때문에 후보로 검토했습니다.

| 방식 | 장점 | 단점 |
|---|---|---|
| live 조회 | 최신 값에 가까움 | upstream 비용 |
| Redis | 반복 검색 빠름 | stale, TTL, stampede |
| DB 저장 | 조회 단순 | 최신성 관리 비용 |

현재 범위에는 예약 전 price revalidation이 없습니다.

```text
12:00 cache 100,000
12:03 Supplier 120,000
12:04 검색에서 100,000 노출
```

보정 지점이 없으므로 `TTL 10초 / 1분 / 5분` 중 무엇이 허용되는지 설명할 기준이 부족하다고 판단했습니다.

또 Redis 장애를 단순 miss로만 보지 않았습니다.

```text
Redis 장애
→ 여러 instance 동시 miss
→ Supplier 직접 조회 급증
→ upstream quota 초과 가능
```

도입한다면 TTL, stale 허용 기준, revalidation, single-flight, miss admission control을 함께 설계해야 한다고 보았습니다.

---

# 11. Retry와 CircuitBreaker를 넣지 않은 과정

## Retry

GET 조회이므로 재시도 자체는 가능합니다.
하지만 장애 중 retry는 upstream 요청을 더 늘립니다.

```text
retry 없음 → batch 1회
1회 retry  → 같은 batch 최대 2회
```

현재는 Supplier quota, `Retry-After`, transient failure 비율, 전체 request deadline이 없습니다.

따라서 한 Supplier를 더 기다리기보다 다른 Supplier 결과를 부분 응답으로 반환하는 편을 선택했습니다.

다시 볼 조건:

- transient failure가 실제로 자주 발생
- Supplier quota/retry 정책을 알고 있음
- request deadline 안에 retry budget이 있음

## CircuitBreaker

CircuitBreaker를 timeout/Bulkhead와 같은 기능으로 보지는 않았습니다.

| 장치 | 역할 |
|---|---|
| Timeout | 한 호출의 대기 상한 |
| Bulkhead | 동시 호출 상한 |
| Partial response | 장애 전파 범위 |
| CircuitBreaker | 반복 실패 Supplier 호출 자체를 중단 |

다만 CB에는 threshold, sliding window, minimum calls, open duration, half-open calls 같은 정책값이 필요합니다.

실제 Supplier 장애율과 복구 시간 데이터가 없는 상태에서는 대부분 임의값이 되기 때문에 현재는 미도입했습니다.

---

# 12. 중복 숙소 병합을 하지 않은 이유

Supplier A/B가 같은 숙소를 판매하더라도 공통 canonical key는 없습니다.

이름 기반 병합은 구현은 쉽지만 다음 문제가 있습니다.

```text
동명이인 숙소
표기 변형
같은 숙소지만 객실 타입명이 다름
조식·취소 조건이 다른 offer
```

현재는 오탐으로 다른 상품을 하나로 합치는 비용이 더 크다고 판단해 Supplier별 내부 ID를 유지하고 별도 상품으로 노출합니다.

향후 canonical property identity나 신뢰할 수 있는 mapping source가 생기면 property merge와 offer merge를 분리해서 다시 검토할 수 있습니다.

---

# 13. 수평 확장을 무조건적인 해결책으로 보지 않음

현재 Bulkhead는 instance-local입니다.

```text
1 replica  → Supplier당 약 16 concurrent
4 replicas → Supplier당 약 64 concurrent
```

그래서 replica를 늘리면 애플리케이션 capacity는 늘어도 Supplier global quota를 자동으로 보호하지는 못합니다.

다중 인스턴스에서는 다음을 다시 봐야 합니다.

```text
Supplier global QPS/concurrency
replica별 budget
cluster-wide rate limiting
Catalog scheduler coordination
distributed lock / leader election
```

무엇이 증가하는지에 따라 병목도 달라진다고 정리했습니다.

```text
Supplier 수 증가  → fan-out / upstream quota
숙소 수 증가      → snapshot 조회 / batch 수
동시 검색 증가    → local bulkhead / global quota
replica 증가      → global quota / scheduler coordination
```

---

# 14. 예약 기능이 들어오면 현재 설계가 어떻게 달라지는가

현재 검색은 외부 write와 내부 write가 없는 읽기 흐름이라 중간 종료 시 보상할 상태가 없습니다.

예약은 다릅니다.

```text
Supplier 예약 성공
→ 우리 DB 반영 전 process 종료
```

가 발생하면 Supplier에는 예약이 있지만 우리 시스템은 모르는 상태가 됩니다.

따라서 예약 기능을 추가한다면 다음 흐름을 먼저 검토하려고 합니다.

```text
1. idempotency key 발급
2. local reservation intent(PENDING) 선기록
3. Supplier 예약 호출
4. 성공 시 CONFIRMED 전환
5. timeout/unknown 결과는 무조건 재시도하지 않고 reconciliation 대상으로 전환
6. Supplier 성공 + local commit 실패도 reconciliation으로 복구
7. 취소는 별도 idempotent 상태 전이와 compensation 정책 적용
```

이 흐름이 생기면 탐색 단계 가격 Cache도 다시 검토할 수 있습니다.
예약 직전 price revalidation이라는 보정 지점이 생기기 때문입니다.

---

# 15. 신규 Supplier 추가 비용을 adapter에 가두기

Supplier가 늘 때 `SearchStaysService`에 Supplier별 분기가 계속 추가되면 Supplier 수가 곧 핵심 로직 복잡도가 됩니다.

그래서 차이는 `supplier-client` 내부에 가두었습니다.

```text
supplier-client/
├── a/ DTO + Normalizer + Adapter
├── b/ DTO + Normalizer + Adapter
└── c/ DTO + Normalizer + Adapter
```

신규 Supplier 추가 시 수정되는 것은 주로:

```text
DTO
Normalizer
Search/Catalog Adapter
Composition Root
설정
```

반대로 다음은 Supplier별 분기가 늘지 않게 두었습니다.

```text
domain
SearchStaysService
CatalogSyncService
storage
검색 API contract
```

현재 구조가 모든 확장을 자동으로 흡수한다고 보지는 않았습니다.
새 Supplier는 여전히 코드 추가와 재배포가 필요한 정적 확장입니다.

---

# 16. AI 활용을 단발성 프롬프트가 아니라 반복 검토 기준으로 사용

AI는 기술 선택을 대신하도록 두기보다 **대안과 장애 순간을 넓게 찾는 검토 도구**로 사용했습니다.

`AGENTS.md`에는 공통 기준을 두었습니다.

```text
다른 선택지는 무엇인가?
무엇을 얻고 무엇을 잃는가?
하지 않는 선택도 검토했는가?
숫자의 출처는 무엇인가?
단계 사이 process 종료 시 무엇이 남는가?
주장을 코드/테스트로 확인할 수 있는가?
```

Claude Code와 Codex에는 작업별 Skill을 같은 구조로 나눴습니다.

```text
failure
numbers
structure
supplier
```

각 Skill의 `description`에 어떤 변경에서 사용할지 적었습니다.

AI 제안을 그대로 적용하지 않은 사례도 남겼습니다.

| 후보 | 다시 확인한 질문 | 결과 |
|---|---|---|
| Redis | stale 값을 어디에서 검증하는가? | 현재 미도입 |
| Retry | 장애 중 호출량은 얼마나 증가하는가? | 현재 미도입 |
| CircuitBreaker | 정책값 근거가 있는가? | 현재 미도입 |
| Advisory lock | 실제 동기화 경합이 있는가? | 단일 instance에서는 미도입 |
| Full WebFlux | 현재 병목이 전체 servlet stack인가? | MVC + WebClient 유지 |
| Virtual Thread | 이미 non-blocking인 외부 I/O에서 무엇이 단순해지는가? | 미도입 |

두 도구의 Skill이 따로 변하지 않도록 `check-agent-guides.sh`와 pre-commit/CI로 drift를 검사합니다.

---

# 17. 테스트가 실제로 회귀를 잡는지도 확인

테스트 개수만 늘리기보다 주요 규칙을 일부러 깨뜨렸을 때 관련 검증이 실패하는지도 확인했습니다.

| 변경 | 확인한 검증 |
|---|---|
| `min → max` | Inventory test |
| batch 내부 `onErrorResume` 제거 | partial failure test |
| `flatMap` concurrency 제거 | batching/concurrency test |
| `ON CONFLICT`에서 ID까지 갱신 | ID stability test |
| Normalizer에 Reactor 의존 추가 | ArchUnit |
| AI Skill 한쪽만 수정 | sync check |

반대로 라이브러리가 이미 보장하는 동작을 모두 다시 테스트하지는 않았습니다.
테스트도 유지 비용이 있기 때문에 이 코드베이스의 판단이 깨지는 지점에 집중했습니다.

---

# 18. 최종적으로 남긴 것과 남기지 않은 것

최종 구현에 남긴 것은 다음과 같습니다.

```text
무손실 통합 모델
정적/동적 lifecycle 분리
runId + upsert Catalog 동기화
Supplier / Batch / Item 실패 격리
transaction rollback
유계 동시성 + Bulkhead
실패 ownership 분리
관측 지표
재현 가능한 테스트·smoke
```

다음은 현재 구현하지 않았습니다.

```text
자동 Retry
CircuitBreaker
가격·재고 Cache
원본 payload quarantine storage
canonical property matching
distributed scheduler lock
예약 write workflow
```

이 항목들도 운영 조건이 달라지면 필요해질 수 있다고 보았습니다.
현재는 도입 정책의 근거가 부족하거나, 함께 설계해야 하는 운영 비용이 현재 이득보다 크다고 판단했습니다.

각 항목이 어떤 조건에서 다시 검토되어야 하는지는 README와 `docs/EXTENDED_DESIGN.md`에 남겼습니다.
