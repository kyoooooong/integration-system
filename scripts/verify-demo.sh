#!/usr/bin/env bash
#
# README 의 시연 절차를 문서에 적힌 그대로 실행하고, 문서의 주장과 대조한다.
#
# 이 스크립트가 있는 이유는 시연 절차가 가장 빨리 낡는 문서이기 때문이다.
# 패키지를 옮기거나 응답 필드 하나를 고쳐도 README 의 시연 절차는 그대로 남아 있고,
# 리뷰어는 그 문서를 그대로 따라 하다가 어긋난 것을 처음 발견하게 된다.
#
# 여기서 확인하는 것은 "빌드가 통과하는가" 가 아니라 "문서대로 쏘면 문서대로 나오는가" 다.
# 단위 테스트가 도는 것과 절차대로 실행했을 때 같은 값이 나오는 것은 다르다.
#
# 동기화 주기가 프로파일마다 다르므로(local 10s, prod 1h) 재동기화를 기다리는 항목은
# SYNC_WAIT 로 조절한다. 0 이면 그 항목을 건너뛴다.
#
# 실행 전제 (README 의 로컬 개발 방법):
#   docker compose up -d
#   ./gradlew :mock-supplier:bootRun
#   SPRING_PROFILES_ACTIVE=local ./gradlew :app:bootRun
#
# 사용:
#   scripts/verify-demo.sh
#
# 실패한 항목 수를 종료 코드로 돌려준다. CI 의 smoke 단계가 이 스크립트를 그대로 쓴다.
Q='checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0'
SYNC_WAIT=${SYNC_WAIT:-12}
API_HOST=${API_HOST:-127.0.0.1}
MOCK_HOST=${MOCK_HOST:-127.0.0.1}
API="http://$API_HOST:8080/api/v1/stays/search"
MOCK="http://$MOCK_HOST:9090"
pass=0; fail=0
ok(){ printf "  ✓ %s\n" "$1"; pass=$((pass+1)); }
no(){ printf "  ✗ %s  — %s\n" "$1" "$2"; fail=$((fail+1)); }
reset(){ curl -s -X POST "$MOCK/control/a/mode?value=normal" -o /dev/null
         curl -s -X POST "$MOCK/control/b/mode?value=normal" -o /dev/null
         curl -s -X POST "$MOCK/control/a/delay?millis=0" -o /dev/null
         curl -s -X POST "$MOCK/control/b/delay?millis=0" -o /dev/null; }
J(){ python3 -c "import sys,json;d=json.load(sys.stdin);$1"; }

reset
echo "정상 검색 — 429,000 / 452,000"
b=$(curl -s "$API?$Q")
[ "$(echo "$b" | J 'print(sorted(i["price"]["totalAmount"] for i in d["items"]))')" = "[429000, 452000]" ] \
  && ok "금액" || no "금액" "$(echo "$b" | head -c 120)"
[ "$(echo "$b" | J 'print(d["partial"])')" = "False" ] && ok "partial=false" || no "partial" ""
# 항목 순서는 보장하지 않는다. 공급사로 찾는다.
[ "$(echo "$b" | J 'a=[i for i in d["items"] if i["supplier"]=="A"][0]["price"];print(a["nightlyBreakdown"] is not None and a["taxAmount"] is not None)')" = "True" ] \
  && ok "A 는 일별 분해와 세액을 보존" || no "A 일별 분해" ""
[ "$(echo "$b" | J 'b=[i for i in d["items"] if i["supplier"]=="B"][0]["price"];print(b["nightlyBreakdown"] is None and b["taxAmount"] is None)')" = "True" ] \
  && ok "B 는 모르는 값을 null 로 (0 이 아님)" || no "B null" ""

echo "연박 재고 = min"
[ "$(echo "$b" | J 'print(sorted(i["availableRooms"] for i in d["items"]))')" = "[1, 1]" ] \
  && ok "availableRooms 최솟값" || no "availableRooms" ""
# 하루라도 재고가 0이면 예약 불가이므로 items 에 없어야 한다.
echo "$b" | grep -q 'Namsan' && no "만실 상품 노출" "Namsan 이 items 에 있다" || ok "만실 상품은 items 에서 빠짐"
[ "$(echo "$b" | J 'print(all(i["availableRooms"]>0 for i in d["items"]))')" = "True" ] \
  && ok "재고 0 상품 없음" || no "재고 0" ""

echo "인원 조건은 공급사가 판정"
n=$(curl -s "$API?checkIn=2026-09-01&checkOut=2026-09-04&adults=5&children=0" | J 'print(len(d["items"]))')
[ "$n" = "0" ] && ok "5인 검색 → 0건" || no "5인 검색" "items=$n"

echo "내부 식별자 안정성 (동기화 반복 후 동일)"
if [ "$SYNC_WAIT" -gt 0 ]; then
  id1=$(curl -s "$API?$Q" | J 'print(sorted(i["propertyId"] for i in d["items"]))')
  sleep "$SYNC_WAIT"
  id2=$(curl -s "$API?$Q" | J 'print(sorted(i["propertyId"] for i in d["items"]))')
  [ "$id1" = "$id2" ] && ok "재동기화 후에도 propertyId 유지" || no "propertyId" "$id1 vs $id2"
else
  echo "  - 건너뜀 (SYNC_WAIT=0). 식별자 안정성은 JdbcCatalogSnapshotStoreTest 가 덮는다"
fi

echo "A 무응답 → B 결과로 부분 응답"
curl -s -X POST "$MOCK/control/a/mode?value=no-response" -o /dev/null
curl -s -X POST "$MOCK/control/a/delay?millis=5000" -o /dev/null
c=$(curl -s -o /tmp/d.json -w '%{http_code}' -m 20 "$API?$Q")
[ "$c" = "200" ] && ok "HTTP 200" || no "HTTP" "$c"
[ "$(J 'print(d["partial"])' < /tmp/d.json)" = "True" ] && ok "partial=true" || no "partial" ""
[ "$(J 'print([s["reason"] for s in d["suppliers"] if s["supplier"]=="A"][0])' < /tmp/d.json)" = "TIMEOUT" ] \
  && ok "A reason=TIMEOUT" || no "A reason" ""
[ "$(J 'print([i["price"]["totalAmount"] for i in d["items"]])' < /tmp/d.json)" = "[452000]" ] \
  && ok "B 결과만 남음" || no "B 결과" ""
reset

echo "B는 HTTP 200으로 실패한다"
curl -s -X POST "$MOCK/control/b/mode?value=error" -o /dev/null
raw=$(curl -s -i "$MOCK/b/api/search?propertyIds=B77120&checkIn=2026-09-01&checkOut=2026-09-04&adults=2&children=0" | head -1)
echo "$raw" | grep -q '200' && ok "공급사는 200 반환" || no "공급사 상태" "$raw"
r=$(curl -s "$API?$Q" | J 'print([s["reason"] for s in d["suppliers"] if s["supplier"]=="B"][0])')
[ "$r" = "UNAVAILABLE" ] && ok "우리는 UNAVAILABLE 로 판정" || no "판정" "$r"
reset

echo "공급사 간 격리 — 도착 시각"
curl -s -X POST "$MOCK/control/a/mode?value=no-response" -o /dev/null
curl -s -X POST "$MOCK/control/a/delay?millis=5000" -o /dev/null
curl -s -X POST "$MOCK/control/requests/reset" -o /dev/null
curl -s -m 20 "$API?$Q" -o /dev/null
gap=$(curl -s "$MOCK/control/requests" | python3 -c "
import sys,json,datetime
rows=json.load(sys.stdin)
if len(rows)<2: print('rows',len(rows)); raise SystemExit
t=[datetime.datetime.fromisoformat(r['arrivedAt']) for r in rows]
print(int(abs((max(t)-min(t)).total_seconds()*1000)))")
[ "$gap" -le 100 ] 2>/dev/null && ok "A·B 도착 간격 ${gap}ms (≤100ms)" || no "도착 간격" "$gap"
reset

echo "둘 다 실패 — 동질/혼합 구분"
curl -s -X POST "$MOCK/control/a/mode?value=error" -o /dev/null
curl -s -X POST "$MOCK/control/b/mode?value=error" -o /dev/null
c=$(curl -s -o /tmp/e.json -w '%{http_code}' -m 20 "$API?$Q")
[ "$c" = "503" ] && ok "동질 UNAVAILABLE → 503" || no "503" "$c"
[ "$(J 'print(d["items"])' < /tmp/e.json)" = "[]" ] && ok "빈 배열이지만 200이 아님" || no "items" ""
curl -s -X POST "$MOCK/control/a/mode?value=no-response" -o /dev/null
curl -s -X POST "$MOCK/control/b/mode?value=no-response" -o /dev/null
curl -s -X POST "$MOCK/control/a/delay?millis=5000" -o /dev/null
curl -s -X POST "$MOCK/control/b/delay?millis=5000" -o /dev/null
c=$(curl -s -o /dev/null -w '%{http_code}' -m 20 "$API?$Q")
[ "$c" = "504" ] && ok "동질 TIMEOUT → 504" || no "504" "$c"
curl -s -X POST "$MOCK/control/a/mode?value=error" -o /dev/null
curl -s -X POST "$MOCK/control/a/delay?millis=0" -o /dev/null
c=$(curl -s -o /dev/null -w '%{http_code}' -m 20 "$API?$Q")
[ "$c" = "503" ] && ok "혼합(UNAVAILABLE+TIMEOUT) → 503" || no "혼합" "$c"
reset

echo "API 문서"
d=$(curl -s localhost:8080/v3/api-docs)
echo "$d" | grep -q '"/api/v1/stays/search"' && ok "엔드포인트가 문서에 있음" || no "엔드포인트" ""
echo "$d" | grep -q '"name":"checkIn","in":"query"' && ok "쿼리 파라미터 문서화" || no "파라미터" ""
# Boot 4 + springdoc 3.x 조합에서도 문서가 실제로 노출되는지 끝까지 확인한다.
[ "$(curl -sL -o /dev/null -w '%{http_code}' "http://$API_HOST:8080/swagger-ui.html")" = "200" ] \
  && ok "Swagger UI 가 실제로 뜬다" || no "swagger-ui" ""
echo "$d" | python3 -c "
import sys,json;d=json.load(sys.stdin)
r=d['paths']['/api/v1/stays/search']['get']['responses']
import sys as s
ok = r['504']['content']['*/*']['schema']['\$ref'].endswith('SearchResponse')
print('OK' if ok else 'NG')" | grep -q OK && ok "504 본문 스키마 = SearchResponse" || no "504 스키마" ""

echo "오류 계약"
for spec in "checkIn=2026-09-04&checkOut=2026-09-01&adults=2&children=0:400" \
            "checkIn=2026-09-01&checkOut=2026-09-04&adults=0&children=0:400" \
            "checkIn=nope&checkOut=2026-09-04&adults=2&children=0:400" \
            "checkOut=2026-09-04&adults=2&children=0:400"; do
  q=${spec%:*}; want=${spec##*:}
  got=$(curl -s -o /dev/null -w '%{http_code}' "$API?$q")
  [ "$got" = "$want" ] && ok "$want ← $q" || no "$q" "got=$got"
done
got=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$API?$Q"); [ "$got" = "405" ] && ok "405" || no "405" "$got"
got=$(curl -s -o /dev/null -w '%{http_code}' "http://$API_HOST:8080/api/v1/nope"); [ "$got" = "404" ] && ok "404" || no "404" "$got"

echo
echo "통과 $pass / 실패 $fail"
exit $fail
