---
search:
  tags:
    - Workspace Registration
    - GET
seo:
  description: >-
    등록 상세와 매니저 목록. Reference for the GET /platform/workspaces/{workspaceId}
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 워크스페이스 상세 조회
  badge: GET
title: 워크스페이스 상세 조회
type: openapi-operation
---
등록 상세와 매니저 목록.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 WORKSPACE_ADMIN_FORBIDDEN: 초대 권한의 platform 부여가 없다
- 404 WORKSPACE_TARGET_UNKNOWN: 등록되지 않은 좌표다

<Operation source="reference-policy" id="policy-workspace-registration-view" />
