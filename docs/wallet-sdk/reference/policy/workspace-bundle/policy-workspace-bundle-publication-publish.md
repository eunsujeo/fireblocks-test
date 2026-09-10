---
search:
  tags:
    - Workspace Bundle
    - POST
seo:
  description: >-
    워크스페이스 번들 발행(스테이징 + 검증).… Reference for the POST
    /workspace-bundles/publications endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: 워크스페이스 번들 발행
  badge: POST
title: 워크스페이스 번들 발행
type: openapi-operation
---
워크스페이스 번들 발행(스테이징 + 검증). **활성화하지 않는다** — 응답의
`bundleVersion`·`digest` 를 들고 활성화를 따로 부른다.

인증: 어드민 세션. 인가: 발행 권한 role 의 `workspace:{workspaceId}` 부여이고, 그
판정 축은 `approvalRef` 가 가리키는 승인 레코드의 스코프다.

관문 순서는 승인 대조 → 도메인 축 확정 → 스냅샷 산출 → 컴파일 검사 → 승인 payload
결속 → 구획 산출 → 채번이고, **위반은 전부 발행 이전에 접힌다.** 컴파일 검사를 승인
결속보다 앞에 두는 것이 의도다 — 위반한 스냅샷은 승인이 무엇이든 발행되지 않아야 하고,
실패 사유가 "승인 불일치" 로 바뀌면 저작자가 원인을 잘못 읽는다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 필수 필드가 비었거나 approvalRef 가 레코드 형태가 아니다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 그 승인 스코프의 발행 권한이 없다
- 404 PUBLICATION_APPROVAL_UNKNOWN: 발행 승인 레코드가 없다 (자격 밖 참조 포함)
- 404 DRAFT_REVISION_UNKNOWN: 그런 revision 이 없다
- 409 PUBLICATION_QUORUM_NOT_MET: 정족수가 채워지지 않았다
- 409 PUBLICATION_PAYLOAD_NOT_APPROVED: 승인된 대상과 다르다 (platform 스코프 승인 포함)
- 409 PUBLICATION_APPROVAL_ALREADY_USED: 이미 다른 다이제스트의 번들에 묶인 승인이다
- 409 DRAFT_DISCARDED: 폐기된 draft 다
- 422 DRAFT_REVISION_NOT_VALIDATED: 검증을 통과한 revision 이 아니다
- 422 PUBLICATION_COMPILE_CHECK_FAILED: 스냅샷이 컴파일 검사를 통과하지 못했다
- 422 WORKSPACE_BUNDLE_INVALID: 산출된 구획이 선언과 어긋난다

<Operation source="reference-policy" id="policy-workspace-bundle-publication-publish" />
