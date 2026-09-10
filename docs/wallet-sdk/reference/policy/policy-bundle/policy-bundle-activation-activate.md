---
search:
  tags:
    - Policy Bundle
    - POST
seo:
  description: >-
    활성 포인터 전이. 발행된 번들만 대상이 된다. Reference for the POST
    /platform/policy-bundles/activations endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: 번들 활성화
  badge: POST
title: 번들 활성화
type: openapi-operation
---
활성 포인터 전이. 발행된 번들만 대상이 된다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 bundleName·contentHash가 형태를 벗어났다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 발행 권한 role의 platform 부여가 없다
- 404 PUBLICATION_BUNDLE_UNKNOWN: 그 내용 해시로 발행된 번들이 없다

<Operation source="reference-policy" id="policy-bundle-activation-activate" />
