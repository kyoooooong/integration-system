#!/usr/bin/env bash
#
# README 가 적어 둔 테스트 개수가 실제 실행 결과와 같은지 대조한다.
#
# 테스트를 추가하면 README 의 표는 조용히 낡는다. 실제로 두 클래스가 어긋나 있었다.
# 개수는 테스트를 실행해야 알 수 있으므로 이 검사는 CI 의 빌드 단계 뒤에서만 돈다.
set -euo pipefail

cd "$(dirname "$0")/.."

python3 <<'PY'
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET


ROOT = Path.cwd()
result_files = sorted(ROOT.glob('*/build/test-results/test/TEST-*.xml'))
if not result_files:
    print("테스트 결과가 없다. './gradlew check' 를 먼저 실행한다.", file=sys.stderr)
    sys.exit(2)

actual: dict[str, int] = {}
for path in result_files:
    suite = ET.parse(path).getroot()
    full_name = suite.attrib['name']
    class_name = full_name.rsplit('.', 1)[-1]
    actual[class_name] = actual.get(class_name, 0) + int(suite.attrib['tests'])

readme = (ROOT / 'README.md').read_text(encoding='utf-8')
declared = {
    cls: int(count)
    for cls, count in re.findall(r'([A-Za-z0-9]+Test)\s+([0-9]+)', readme)
}

failed = False
for cls, count in sorted(declared.items()):
    real = actual.get(cls)
    if real is None:
        print(f'README 가 없는 테스트 클래스를 적고 있다: {cls}')
        failed = True
    elif real != count:
        print(f'개수 불일치: {cls} - README {count}, 실제 {real}')
        failed = True

for cls, count in sorted(actual.items()):
    if cls not in declared:
        print(f'README 표에 빠진 테스트 클래스: {cls} ({count}건)')
        failed = True

declared_total = sum(declared.values())
actual_total = sum(actual.values())
if declared_total != actual_total:
    print(f'합계 불일치: README 표 {declared_total}, 실제 {actual_total}')
    failed = True

# 본문이 인용하는 총계.
total_patterns = [
    r'clean check\s+# ([0-9]+) tests',
]
totals: list[int] = []
for pattern in total_patterns:
    match = re.search(pattern, readme)
    if not match:
        print('README 에서 총계 인용을 찾지 못했다. 검사가 무력해졌으므로 패턴을 고친다.')
        print(f'  패턴: {pattern}')
        failed = True
        continue
    totals.append(int(match.group(1)))

for total in totals:
    if total != actual_total:
        print(f'본문의 총계가 다르다: {total} (실제 {actual_total})')
        failed = True

if failed:
    print()
    print('README 의 테스트 집계가 낡았다.')
    sys.exit(1)

print(f'README 의 테스트 집계가 실제와 일치한다 (클래스 {len(actual)}개 / {actual_total}건).')
PY
