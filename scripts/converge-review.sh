#!/bin/bash
# Phase converge의 Claude Code 실행 어댑터 — 한 번에 agent 하나만 실행하고, 실패 시 같은 Claude 세션을 재개한다.
set -euo pipefail

cd "$(dirname "$0")/.."

usage() {
    echo "사용법: $0 <design-sync|code-reviewer> <base-commit>" >&2
    echo "예시: $0 design-sync d5610fc" >&2
    exit 2
}

if [ "$#" -ne 2 ]; then
    usage
fi

agent="$1"
base_ref="$2"
case "$agent" in
    design-sync | code-reviewer) ;;
    *) usage ;;
esac

base_commit="$(git rev-parse --verify "${base_ref}^{commit}")" || {
    echo "유효한 base commit이 아님: $base_ref" >&2
    exit 2
}
head_commit="$(git rev-parse --verify HEAD)"
review_range="${base_commit}..HEAD"
review_scope="커밋 범위 ${review_range}"
if [ "$base_commit" = "$head_commit" ] && [ -n "$(git status --porcelain)" ]; then
    review_scope="현재 작업 트리(HEAD ${head_commit} 대비 tracked 변경과 untracked 파일 전체)"
fi
state_dir="$(git rev-parse --git-path claude-converge)"
session_file="${state_dir}/${agent}-${base_commit}.session"
mkdir -p "$state_dir"

common_args=(
    --permission-mode dontAsk
    --strict-mcp-config
    --mcp-config '{"mcpServers":{}}'
)

if [ -s "$session_file" ]; then
    session_id="$(<"$session_file")"
    prompt="중단되었거나 이전에 끝난 ${agent} converge 검사를 이어서 수행해 주세요. "
    prompt+="리뷰 범위는 ${review_scope}, 현재 HEAD는 ${head_commit}입니다. "
    prompt+="이전 결과 이후 변경을 다시 읽고 최종 보고서를 완성하세요. 코드는 수정하지 마세요."
    exec claude -p --resume "$session_id" "${common_args[@]}" -- "$prompt"
fi

session_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"
printf '%s\n' "$session_id" > "$session_file"
prompt="blockchain-manager Phase converge ${agent} 검사를 수행해 주세요. "
prompt+="리뷰 범위는 ${review_scope}, 현재 HEAD는 ${head_commit}입니다. "
prompt+="해당 범위를 실제 git diff로 확인하고 파일·행 근거를 포함한 최종 보고서를 작성하세요. "
prompt+="코드는 수정하지 마세요."

exec claude -p \
    --session-id "$session_id" \
    --name "converge-${agent}-${base_commit:0:7}" \
    --agent "$agent" \
    "${common_args[@]}" \
    -- "$prompt"
