---
search:
  tags:
    - Policy Draft
    - POST
seo:
  description: >-
    컴파일 검사 전종 +… Reference for the POST
    /policy-drafts/{draftId}/revisions/{revisionId}/validation endpoint in the
    Wallet Policy Engine Internal API API.
sidebar:
  label: revision 검증 실행
  badge: POST
title: revision 검증 실행
type: openapi-operation
---
컴파일 검사 전종 + 벡터 채점을 **동기로** 돌린다. 결과는 이 revision에 결속되고,
이미 있으면 다시 돌리지 않는다 — revision이 불변이라 답이 같다.

**검증 실패는 4xx가 아니다.** 검증 실행은 성공했고 판정이 FAILED인 것이므로 200에
outcome을 싣는다. 4xx로 답하면 "요청이 잘못됐다"와 "정책이 검사를 통과하지 못했다"가
같은 자리에 섞인다.

Errors:
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 DRAFT_FORBIDDEN: 발행 권한 role의 부여가 없다
- 404 DRAFT_UNKNOWN: 그런 draft가 없다 (자격 밖 draft 포함)
- 404 DRAFT_REVISION_UNKNOWN: 그런 revision이 없거나 이 draft의 칸이 아니다
- 409 DRAFT_DISCARDED: 폐기된 draft다

<Operation source="reference-policy" id="policy-draft-validation-run" />
