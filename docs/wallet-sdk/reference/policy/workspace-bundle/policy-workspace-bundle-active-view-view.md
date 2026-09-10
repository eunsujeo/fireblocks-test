---
search:
  tags:
    - Workspace Bundle
    - GET
seo:
  description: >-
    요청자 워크스페이스의 지금 활성 번들. 승인 화면의… Reference for the GET
    /workspace-bundles/activation endpoint in the Wallet Policy Engine Internal
    API API.
sidebar:
  label: 활성 워크스페이스 번들 조회
  badge: GET
title: 활성 워크스페이스 번들 조회
type: openapi-operation
---
요청자 워크스페이스의 지금 활성 번들. 승인 화면의 diff 기준선이다.

**경로 변수도 쿼리도 없는 것이 계약이다** — 대상 워크스페이스는 세션의 발행 부여가
정한다. 로직 번들 활성 조회(`/policy-bundles/activations/{bundleName}`)에 얹지 않는
이유가 같은 자리다: 그쪽 포인터는 이름으로 서고 이쪽은 workspaceId 로 서므로, 한 경로에
담으면 경로 변수의 의미가 표면 안에서 갈린다.

활성 번들이 없으면 404 다. 빈 봉투를 200 으로 주면 "기준선이 없다" 와 "기준선을 못
읽었다" 가 같은 자리에 섞인다 — 편집기 base 조회(draft.tsp)가 부재를 200 에 싣는 것과
갈리는 지점이고, 근거도 그쪽 doc 이 적은 것과 같다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 워크스페이스 발행 권한이 없다 (platform 부여도 여기서 끝난다)
- 404 WORKSPACE_BUNDLE_ACTIVE_ABSENT: 이 워크스페이스에 활성 번들이 없다

<Operation source="reference-policy" id="policy-workspace-bundle-active-view-view" />
