---
search:
  tags:
    - Policy Bundle
    - GET
seo:
  description: >-
    지금 활성인 번들. 승인 화면의… Reference for the GET
    /platform/policy-bundles/activations/{bundleName} endpoint in the Wallet
    Policy Engine Internal API API.
sidebar:
  label: 활성 번들 조회
  badge: GET
title: 활성 번들 조회
type: openapi-operation
---
지금 활성인 번들. 승인 화면의 diff 기준선이다 — 서빙 표면(/bundles/&#123;name&#125;/active)이
같은 사실을 주지만 그쪽 자격은 서비스 크리덴셜이라 어드민 세션으로는 닿지 않는다.

최초 발행 이전에는 404 PUBLICATION_ACTIVE_ABSENT다. 빈 봉투를 200으로 주면
"기준선이 없다"와 "기준선을 못 읽었다"가 같은 자리에 섞인다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 발행 권한 role의 platform 부여가 없다
- 404 PUBLICATION_ACTIVE_ABSENT: 그 이름으로 활성인 번들이 없다

<Operation source="reference-policy" id="policy-bundle-activation-view" />
