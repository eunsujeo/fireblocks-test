#!/bin/bash
# PostToolUse(Edit|Write) — 편집 직후 검사. Kotlin 파일이면 ktlint 단일 파일 검사 (설치돼 있을 때만).
# exit 2 = Claude 에게 위반 내용을 피드백(수정 유도). 도구 부재 등은 조용히 통과.

INPUT=$(cat)
FILE=$(printf '%s' "$INPUT" | python3 -c "import sys,json;print(json.load(sys.stdin).get('tool_input',{}).get('file_path',''))" 2>/dev/null)

[ -z "$FILE" ] && exit 0

if [[ "$FILE" == *.kt || "$FILE" == *.kts ]] && command -v ktlint >/dev/null 2>&1; then
  if ! OUT=$(ktlint "$FILE" 2>&1); then
    echo "ktlint 위반 — 수정 후 진행: $OUT" >&2
    exit 2
  fi
fi

exit 0
