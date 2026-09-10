---
search:
  tags:
    - Policy Draft
    - GET
seo:
  description: >-
    주소만으로 읽는 칸. draft를 지나지 않는… Reference for the GET
    /policy-drafts/revisions/{revisionId} endpoint in the Wallet Policy Engine
    Internal API API.
sidebar:
  label: revision 조회 (주소)
  badge: GET
title: revision 조회 (주소)
type: openapi-operation
---
주소만으로 읽는 칸. **draft를 지나지 않는 것이 의도다** — 발행 승인이 든 것은 revision
주소뿐이라(어느 draft의 칸인지는 담지 않는다), 승인 화면이 diff 기준선을 찾으려면 그
주소에서 콘텐츠로 갈 수 있어야 한다.

draft 종속 경로(/policy-drafts/&#123;draftId&#125;/revisions/&#123;revisionId&#125;)는 저작 화면의 포함
관계 검사로 남는다 — 거기서는 URL의 두 id가 서로 맞는지가 뜻을 갖는다.

draft를 지나지 않아도 **스코프 판정은 지난다** — 없으면 revision id 하나로 남의
워크스페이스가 읽힌다. 자격 밖 revision은 부재와 같은 답을 받는다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없다
- 404 DRAFT_REVISION_UNKNOWN: 그런 revision이 없다 (자격 밖 revision 포함)

<Operation source="reference-policy" id="policy-draft-revision-by-address-view" />
