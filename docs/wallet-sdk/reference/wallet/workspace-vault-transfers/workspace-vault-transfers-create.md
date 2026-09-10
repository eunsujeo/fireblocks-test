---
search:
  tags:
    - Workspace Vault Transfers
    - POST
seo:
  description: >-
    WorkspaceWallet 간 자산 이동을 요청한다. 출발 vault의 가용… Reference for the POST
    /api/v1/workspace-vault-transfers endpoint in the Wallet API API.
sidebar:
  label: 워크스페이스 Vault 이체 요청 생성
  badge: POST
title: 워크스페이스 Vault 이체 요청 생성
type: openapi-operation
---
WorkspaceWallet 간 자산 이동을 요청한다.
출발 vault의 가용 잔액(settled − in-flight)을 서명 이전에 검증해, 부족하면 400으로 즉시 거절한다.
커스터디 실행 결과로 부족이 확인되면 FAILED (INSUFFICIENT_FUNDS) subStatus로 마킹된다.

Errors:
- 400 WORKSPACE_VAULT_INSUFFICIENT_BALANCE: 출발 vault의 가용 잔액이 요청 금액에 미치지 못함 (서명 이전 차단)
- 400 WORKSPACE_VAULT_TRANSFER_SOURCE_DESTINATION_SAME: 출발과 도착 vault가 같음
- 400 WORKSPACE_VAULT_TRANSFER_INVALID_AMOUNT: 금액 해석 불가·0 이하·token 정밀도 초과
- 400 WORKSPACE_VAULT_TRANSFER_REFERENCE_CONFLICT: 같은 고객사가 동일 referenceId로 이미 이체를 생성
- 400 WORKSPACE_VAULT_TRANSFER_POLICY_DENIED: 정책이 이체를 거부 (행은 FAILED로 남음)
- 400 WORKSPACE_VAULT_INACTIVE: 비활성 vault
- 404 TOKEN_NOT_FOUND / WORKSPACE_VAULT_NOT_FOUND: 대상 미존재 — 타 workspace 자원도 같은 응답 (존재 오라클 차단)
- 404 WORKSPACE_WALLET_NOT_FOUND: 어느 한쪽 vault에 대상 token의 지갑이 없음
- 503 WORKSPACE_VAULT_TRANSFER_PROVIDER_UNAVAILABLE: 제출 결과 불명 — 행은 PENDING으로 남아 대사가 정리

<Operation source="reference-wallet" id="workspace-vault-transfers-create" />
