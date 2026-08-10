#!/bin/bash
# PreToolUse(Edit|Write) — 실행 "전" 차단. exit 2 = tool call 자체를 막고 사유를 Claude 에게 전달.
# docs/design/ 은 read-only 설계 사본 — permissions.deny 와 이중 방어.

INPUT=$(cat)
FILE=$(printf '%s' "$INPUT" | python3 -c "import sys,json;print(json.load(sys.stdin).get('tool_input',{}).get('file_path',''))" 2>/dev/null)

case "$FILE" in
  *docs/design/*)
    echo "docs/design/ 은 read-only 설계 사본이다 — 수정 금지. 설계 변경은 waas-wiki(정본)에서 하고 사본을 복사해 온다 (docs/design/README.md)." >&2
    exit 2
    ;;
esac

exit 0
