#!/usr/bin/env bash
# Public repository hygiene check.
set -euo pipefail

fail=0

tracked="$(git ls-files)"

# 1) 실제 환경 파일 추적 금지.
if git ls-files --error-unmatch .env >/dev/null 2>&1; then
  echo ".env 가 추적되고 있다. .env.sample 만 커밋한다." >&2
  fail=1
fi

# 2) 배포 가능한 자체 문서가 아니라면 PDF 는 저장소에 두지 않는다.
pdfs="$(git ls-files '*.pdf' || true)"
if [ -n "$pdfs" ]; then
  echo "$pdfs" >&2
  echo "PDF가 Git에 추적되고 있다. 공개 가능한 자체 산출물인지 확인한다." >&2
  fail=1
fi

# 3) 비밀값처럼 보이는 파일명 추적 금지.
secret_paths="$(
  printf '%s\n' "$tracked" \
    | grep -Ei '(^|/)(.*secret.*|.*credential.*|.*private-key.*|.*access-token.*|.*api-key.*)$' \
    | grep -vE '(^|/)\.env\.sample$' || true
)"
if [ -n "$secret_paths" ]; then
  echo "$secret_paths" >&2
  echo "비밀값 파일로 보이는 tracked path가 있다." >&2
  fail=1
fi

# 4) 텍스트 파일 안의 대표적인 비밀값 패턴 검사.
secret_matches=$(
  git ls-files '*.java' '*.kts' '*.md' '*.yaml' '*.yml' '*.sql' '*.toml' '*.js' '*.sh' \
    | tr '\n' '\0' \
    | xargs -0 grep -nIiE '(AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9_]{30,}|xox[baprs]-[A-Za-z0-9-]+|-----BEGIN (RSA |OPENSSH |EC |DSA )?PRIVATE KEY-----)' 2>/dev/null || true
)
if [ -n "$secret_matches" ]; then
  echo "$secret_matches" >&2
  echo "비밀값으로 보이는 문자열이 포함되어 있다." >&2
  fail=1
fi

# 5) 저장소별 비공개 표현은 환경 변수로만 주입한다.
# 이 스크립트 자체가 비공개 표현의 목록이 되지 않게 하기 위해 기본값은 비워 둔다.
if [ -n "${PUBLIC_FORBIDDEN_PATTERN:-}" ]; then
  path_matches="$(printf '%s\n' "$tracked" | grep -iE "$PUBLIC_FORBIDDEN_PATTERN" || true)"
  if [ -n "$path_matches" ]; then
    echo "$path_matches" >&2
    echo "공개 저장소에 두지 않을 표현이 포함된 tracked path가 있다." >&2
    fail=1
  fi

  text_matches=$(
    git ls-files '*.java' '*.kts' '*.md' '*.yaml' '*.yml' '*.sql' '*.toml' '*.js' '*.sh' \
      | tr '\n' '\0' \
      | xargs -0 grep -nIiE "$PUBLIC_FORBIDDEN_PATTERN" 2>/dev/null || true
  )
  if [ -n "$text_matches" ]; then
    echo "$text_matches" >&2
    echo "공개 저장소에 두지 않을 표현이 포함되어 있다." >&2
    fail=1
  fi

  if git log --all --format='%s%n%b' 2>/dev/null | grep -iE "$PUBLIC_FORBIDDEN_PATTERN" >/dev/null; then
    echo "커밋 메시지에 공개 저장소에 두지 않을 표현이 있다." >&2
    fail=1
  fi
fi

exit "$fail"
