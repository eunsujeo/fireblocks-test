---
search:
  tags:
    - Workspace Registration
    - GET
seo:
  description: >-
    등록된 워크스페이스 목록. 쓰기가 아니라 스텝업 없이 부여만… Reference for the GET
    /platform/workspaces endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 워크스페이스 목록 조회
  badge: GET
title: 워크스페이스 목록 조회
type: openapi-operation
---
등록된 워크스페이스 목록. **쓰기가 아니라 스텝업 없이 부여만 본다.**

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 WORKSPACE_ADMIN_FORBIDDEN: 초대 권한의 platform 부여가 없다

<Operation source="reference-policy" id="policy-workspace-registration-list" />
