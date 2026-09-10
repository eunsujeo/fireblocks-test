---
search:
  tags:
    - Policy Bundle
    - POST
seo:
  description: >-
    번들 발행(스테이징 + 검증). 활성화하지 않는다. Reference for the POST
    /policy-bundles/publications endpoint in the Wallet Policy Engine Internal
    API API.
sidebar:
  label: 번들 발행
  badge: POST
title: 번들 발행
type: openapi-operation
---
번들 발행(스테이징 + 검증). **활성화하지 않는다.**

승인 레코드 실재·정족수 충족·대상 바이트 결속을 전부 발행 이전에 확인한다.

Errors:
- 400 INVALID_REQUEST: 바디가 없거나 필수 필드가 비었거나 approvalRef가 레코드 형태가 아니다
- 401 AUTHENTICATION_FAILED: 어드민 세션이 확정되지 않았다
- 403 PUBLICATION_FORBIDDEN: 발행 권한 role의 platform 부여가 없다
- 404 PUBLICATION_APPROVAL_UNKNOWN: 발행 승인 레코드가 없다 (자격 밖 참조 포함)
- 404 DRAFT_REVISION_UNKNOWN: 그런 revision이 없다
- 409 PUBLICATION_QUORUM_NOT_MET: 정족수가 채워지지 않았다
- 409 PUBLICATION_PAYLOAD_NOT_APPROVED: 승인된 대상과 다르다
- 409 PUBLICATION_APPROVAL_ALREADY_USED: 이미 발행에 쓰인 승인 참조다
- 409 DRAFT_DISCARDED: 폐기된 draft다
- 422 DRAFT_REVISION_NOT_VALIDATED: 검증을 통과한 revision이 아니다
- 422 PUBLICATION_COMPILE_CHECK_FAILED: 스냅샷이 컴파일 검사를 통과하지 못했다

<Operation source="reference-policy" id="policy-bundle-publication-publish" />
