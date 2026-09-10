---
search:
  tags:
    - Bundle Serving
    - GET
seo:
  description: >-
    OPA 가 폴링하는 경로. 사이드카 설정의… Reference for the GET /bundles/{bundleName}.tar.gz
    endpoint in the Wallet Policy Engine Internal API API.
sidebar:
  label: 활성 번들 tarball 내려받기
  badge: GET
title: 활성 번들 tarball 내려받기
type: openapi-operation
---
OPA 가 폴링하는 경로. 사이드카 설정의 `bundles.<name>.resource` 와 짝이다.

**ETag 가 내용 해시다.** 같은 내용이 같은 값을 갖고 다른 내용이 같은 값을 가질 수 없어
304 가 "내용이 바뀌지 않았다"는 뜻이 된다. 없으면 폴링마다 tarball 전체를 다시 내려보내고
사이드카가 매번 재활성화한다.

서버는 ETag 를 먼저 정하고 바이트는 필요할 때만 읽는다 — 304 로 끝나는 폴링에서도 번들
바이트를 끌어올리면 그것이 이 엔드포인트의 정상 부하가 된다.

Errors:
- 401 UNAUTHENTICATED: 서비스 크리덴셜이 신원을 확정하지 않는다
- 403 FORBIDDEN_ROLE: 그 크리덴셜의 역할이 이 표면을 부를 수 없다
- 503 no_active_bundle: 활성 포인터가 없다. **봉투가 다르다**

<Operation source="reference-policy" id="policy-bundle-serving-download" />
