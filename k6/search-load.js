// 통합 검색 부하 프로파일.
//
// ── 무엇을 측정할 수 있고 무엇을 측정할 수 없는가 ──────────────────────
//
// 상류는 우리가 만든 Mock 이다. 따라서 "공급사가 실제로 얼마나 빠른가" 는 여기서 알 수 없고,
// 그것으로 타임아웃 값을 정하면 숫자에 가짜 출처를 붙이는 일이 된다.
// 측정 대상은 우리 쪽이다.
//
//   1. 동시 검색이 늘 때 우리 동시성 상한이 실제로 걸리는가
//   2. 포화가 시작되는 지점이 설정값으로 설명되는가
//   3. 포화 상황에서 결과가 오염되지 않고 degrade 되는가
//
// ── 이론값 ────────────────────────────────────────────────────────────
//
//   공급사당 처리량 상한 ≈ bulkhead-max-concurrent-calls / 공급사 응답 시간
//                        = 16 / 0.2s = 80 req/s
//
// 이 근처에서 503(LOCAL_SATURATION)이 나타나기 시작해야 한다.
// 훨씬 일찍 나타나면 다른 곳이 먼저 구속된 것이고, 훨씬 늦게 나타나면 상한이
// 실제로는 걸리지 않고 있는 것이다. 둘 다 설정이 의미대로 동작하지 않는다는 뜻이다.
//
// ── executor 선택 ─────────────────────────────────────────────────────
//
// ramping-vus 를 쓰지 않는다. VU 를 늘리면 "최대한 빨리" 보내게 되어 도착률이
// 시스템 응답 속도에 종속된다. 포화가 시작되면 응답이 빨라지므로(503 은 즉시 반환)
// 도착률이 오히려 올라가고, 그러면 부하 생성기의 임시 포트가 먼저 고갈된다.
// 실제로 그렇게 만들었다가 errno 49 를 만나 이 구조로 바꿨다.
// arrival-rate 는 도착률을 우리가 정하므로 이론값과 대조할 수 있다.
//
// ── 프로파일이 둘인 이유 ──────────────────────────────────────────────
//
// 두 질문의 성격이 다르다.
//
//   steps  "상한이 이론값과 맞는가" — 도착률을 계단으로 고정해야 단계별로 비교할 수 있다.
//          단계마다 성공/포화/p95 를 따로 세므로 측정 기록과 같은 판단 항목이
//          다시 나온다. 절대 개수는 로컬 상태에 따라 조금 흔들릴 수 있다.
//   ramp   "포화에서 회복되는가"   — 연속적으로 올렸다 내려야 회복을 볼 수 있다.
//          계단으로는 "내려온 뒤 포화가 사라지는가" 를 관찰하기 어렵다.
//
// 하나로 합치면 둘 중 하나를 못 본다. 그래서 나눴고, 기본값은 표를 만드는 steps 다.
//
// 실행:
//   k6 run k6/search-load.js                      # steps — 측정 캡처
//   k6 run -e PROFILE=ramp k6/search-load.js      # ramp  — 포화와 회복
//   k6 run -e BASE_URL=http://localhost:8080 -e PEAK_RPS=200 -e PROFILE=ramp k6/search-load.js
//
// 전제: Mock 의 공급사 응답 지연이 설정되어 있어야 한다.
//   curl -X POST 'localhost:9090/control/a/delay?millis=200'
//   curl -X POST 'localhost:9090/control/b/delay?millis=200'
import http from 'k6/http';
import exec from 'k6/execution';
import { check } from 'k6';
import { Counter, Rate, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const PEAK_RPS = Number(__ENV.PEAK_RPS || 200);
const PROFILE = __ENV.PROFILE || 'steps';

const saturated = new Counter('search_saturated');       // 503 — 우리 상한이 걸렸다
const partial = new Counter('search_partial');           // 200 이지만 일부 공급사 실패
const priceCorrect = new Rate('search_price_correct');   // 부하와 무관하게 값이 정확한가

// 계단 프로파일의 목표 도착률. 이론 상한(80)을 사이에 두고 아래위로 벌린다.
// 70 과 90 을 모두 넣는 이유는 "포화가 어디서 시작되는가" 가 이 측정의 핵심이기 때문이다.
const STEPS = [30, 50, 70, 90, 120, 160];
const STEP_SECONDS = 12;

// 단계별 집계. k6 는 태그만으로는 요약에 나눠 주지 않으므로 단계마다 지표를 따로 만든다.
//
// 200 을 하나로 세면 안 된다. 우리 API 는 공급사 하나만 쓸 만해도 200 을 돌려주므로,
// 한쪽이 포화된 뒤에도 200 은 계속 늘어난다. 그래서 "완전 응답(full)" 과
// "부분 응답(part)" 을 나눠 센다. 이론 상한 80 req/s 와 대조해야 하는 것은 full 이다.
const stepFull = {};
const stepPartial = {};
const stepSaturated = {};
const stepDuration = {};
for (const rps of STEPS) {
  stepFull[rps] = new Counter(`full_${rps}`);
  stepPartial[rps] = new Counter(`part_${rps}`);
  stepSaturated[rps] = new Counter(`sat_${rps}`);
  stepDuration[rps] = new Trend(`dur_${rps}`, true);
}

const stepScenarios = {};
STEPS.forEach((rps, i) => {
  stepScenarios[`s${rps}`] = {
    executor: 'constant-arrival-rate',
    rate: rps,
    timeUnit: '1s',
    duration: `${STEP_SECONDS}s`,
    startTime: `${i * STEP_SECONDS}s`,
    // 한 단계가 다음 단계로 흘러넘치면 도착률이 섞여 표가 의미를 잃는다.
    gracefulStop: '1s',
    // 최악(160 rps × 0.21s ≈ 34) 의 2배. 부족하면 k6 가 도착률을 못 지켜 측정이 무효가 된다.
    preAllocatedVUs: 70,
    maxVUs: 200,
  };
});

const rampScenario = {
  ramp: {
    executor: 'ramping-arrival-rate',
    startRate: 20,
    timeUnit: '1s',
    preAllocatedVUs: 50,
    maxVUs: 300,
    stages: [
      { duration: '10s', target: 40 },        // 이론 상한 아래. 포화가 없어야 한다
      { duration: '15s', target: PEAK_RPS },  // 상한을 넘긴다
      { duration: '15s', target: PEAK_RPS },
      { duration: '5s', target: 20 },         // 회복되는가
    ],
  },
};

export const options = {
  scenarios: PROFILE === 'ramp' ? rampScenario : stepScenarios,
  thresholds: {
    // 포화는 정해진 방식으로 응답해야 한다. 500 이나 연결 오류는 degrade 가 아니라 붕괴다.
    http_req_failed: ['rate<0.01'],
    // 포화는 결과를 줄일 수는 있어도 오염시킬 수는 없다. 하나라도 틀리면 실패다.
    search_price_correct: ['rate==1.0'],
  },
};

const QUERY = '?checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0';

export default function () {
  // 503/504 도 우리가 의도한 응답이다. k6 가 실패로 세지 않도록 명시한다.
  const res = http.get(`${BASE_URL}/api/v1/stays/search${QUERY}`, {
    responseCallback: http.expectedStatuses(200, 503, 504),
  });

  check(res, { 'degrades, not collapses': (r) => [200, 503, 504].includes(r.status) });
  if (res.status === 503 || res.status === 504) saturated.add(1);

  // 시나리오 이름이 곧 그 단계의 목표 도착률이다 (s90 → 90).
  const rps = Number(exec.scenario.name.slice(1));
  const stepped = Boolean(stepDuration[rps]);
  if (stepped) {
    stepDuration[rps].add(res.timings.duration);
    if (res.status !== 200) stepSaturated[rps].add(1);
  }

  let body;
  try {
    body = res.json();
  } catch (e) {
    return;
  }
  if (!body || !body.suppliers) return;
  if (body.partial) partial.add(1);

  if (stepped && res.status === 200) {
    (body.partial ? stepPartial[rps] : stepFull[rps]).add(1);
  }

  if (res.status === 200 && body.items && body.items.length > 0) {
    // 기준 예시 데이터 기준. 부하 하에서 값이 흔들리면 안 된다.
    priceCorrect.add(body.items.every((i) => i.price.totalAmount === 429000 || i.price.totalAmount === 452000));
  }
}
