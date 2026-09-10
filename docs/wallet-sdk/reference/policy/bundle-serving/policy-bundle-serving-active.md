---
search:
  tags:
    - Bundle Serving
    - GET
seo:
  description: >-
    활성 revision 조회. PDP 에 무엇이 활성인지 보는 운영… Reference for the GET
    /bundles/{bundleName}/active endpoint in the Wallet Policy Engine Internal
    API API.
sidebar:
  label: 활성 번들 조회 (서빙)
  badge: GET
title: 활성 번들 조회 (서빙)
type: openapi-operation
---
활성 revision 조회. PDP 에 무엇이 활성인지 보는 운영 관측 지점이다.

레플리카들이 서로 다른 값을 주장하는 것이 결함이고, 사이드카 폴링 시차로 OPA 가 이보다
뒤의 revision 을 쓰는 것은 정상이다.

Errors:
- 401 UNAUTHENTICATED: 서비스 크리덴셜이 신원을 확정하지 않는다
- 403 FORBIDDEN_ROLE: 그 크리덴셜의 역할이 이 표면을 부를 수 없다
- 503 no_active_bundle: 활성 포인터가 없다. **봉투가 다르다**

<Operation source="reference-policy" id="policy-bundle-serving-active" />
