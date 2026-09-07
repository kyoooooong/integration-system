#!/usr/bin/env bash
#
# AI 도구 지침이 도구별로 갈라지지 않았는지 확인한다.
#
# AGENTS.md 는 "두 디렉토리의 내용은 동일하게 유지한다" 고 적어 두었지만,
# 지키는 것은 사람의 성실함이었다. 한쪽만 고치면 도구에 따라 다른 기준이 적용되고,
# 그 사실을 알아채는 시점은 이미 두 기준으로 짠 코드가 섞인 뒤다.
#
# 확인하는 것
#   1. .claude/skills 와 .codex/skills 가 완전히 같은가
#   2. AGENTS.md 가 가리키는 skill 파일이 실재하는가
#   3. 각 skill 에 frontmatter(name, description)가 있는가 — 없으면 자동 로딩이 안 된다
set -euo pipefail

cd "$(dirname "$0")/.."

fail=0

# 1. 두 디렉토리가 같은가
claude=$(cd .claude/skills && find . -name 'SKILL.md' | sort)
codex=$(cd .codex/skills && find . -name 'SKILL.md' | sort)
if [ "$claude" != "$codex" ]; then
    echo "skill 목록이 다르다:"
    diff <(printf '%s\n' "$claude") <(printf '%s\n' "$codex") || true
    fail=1
fi

for rel in $claude; do
    if ! diff -q ".claude/skills/$rel" ".codex/skills/$rel" > /dev/null 2>&1; then
        echo "내용이 갈라졌다: $rel"
        diff ".claude/skills/$rel" ".codex/skills/$rel" | head -20 || true
        fail=1
    fi
done

# 2. AGENTS.md 가 가리키는 파일이 실재하는가
while read -r path; do
    [ -z "$path" ] && continue
    if [ ! -f "$path" ]; then
        echo "AGENTS.md 가 없는 파일을 가리킨다: $path"
        fail=1
    fi
done < <(grep -oE '\.(claude|codex)/skills/[a-z-]+/SKILL\.md' AGENTS.md | sort -u)

# 3. frontmatter — 없으면 도구가 skill 을 자동으로 못 고른다
for rel in $claude; do
    f=".claude/skills/$rel"
    head -1 "$f" | grep -qx -- '---' || { echo "frontmatter 없음: $f"; fail=1; continue; }
    grep -qE '^name: [a-z-]+$' "$f" || { echo "name 없음: $f"; fail=1; }
    grep -qE '^description: .+' "$f" || { echo "description 없음: $f"; fail=1; }
done

if [ "$fail" -ne 0 ]; then
    echo
    echo "AI 지침이 도구별로 갈라졌다. 한쪽을 고쳤으면 다른 쪽도 같이 고친다."
    exit 1
fi

echo "AI 지침 $(printf '%s\n' "$claude" | wc -l | tr -d ' ')종이 두 도구에서 동일하다."
