---
search:
  tags:
    - Workspace Bundle
    - POST
seo:
  description: >-
    활성 포인터 전이. 발행된 번들만 대상이 되고,… Reference for the POST
    /workspace-bundles/activations endpoint in the Wallet Policy Engine Internal
    API API.
sidebar:
  label: 워크스페이스 번들 활성화
  badge: POST
title: 워크스페이스 번들 활성화
type: openapi-operation
---
활성 포인터 전이. 발행된 번들만 대상이 되고, 되돌리기는 옛 번호를 지목한다.

인증: 어드민 세션. 인가: 발행 권한 role 의 `workspace:{workspaceId}` 부여이고, **그
부여가 대상 워크스페이스를 정한다** — 활성화에는 스코프를 정해 줄 승인 참조가 없다.

이미 활성인 번호를 다시 세우면 `activationSeq` 를 올리지 않고 현재 상태를 돌려준다 —
재시도가 이력을 부풀리면 "몇 번 활성화됐는가" 가 감사 신호로서 의미를 잃는다.

Errors:
- 400 INVALID_REQUEST: bundleVersion 이 양의 정수가 아니거나 digest 가 소문자 hex 64자가 아니다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 워크스페이스 발행 권한이 없다 (platform 부여도 여기서 끝난다)
- 404 WORKSPACE_BUNDLE_UNKNOWN: 그 번호의 번들이 이 워크스페이스에 없다
- 409 WORKSPACE_BUNDLE_DIGEST_MISMATCH: 그 번호의 다이제스트가 요청과 다르다
- 409 WORKSPACE_BUNDLE_ACTIVATION_CONFLICT: 다른 활성화가 먼저 지나갔다

<Operation source="reference-policy" id="policy-workspace-bundle-activation-activate" />
