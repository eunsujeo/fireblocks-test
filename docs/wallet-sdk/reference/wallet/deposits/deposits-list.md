---
search:
  tags:
    - Deposits
    - GET
seo:
  description: >-
    cursor로 이어 읽는 동안 status·from·to를 바꾸지 않는다. 커서는… Reference for the GET
    /api/v1/accounts/{accountId}/deposits endpoint in the Wallet API API.
sidebar:
  label: 입금 내역 목록 조회
  badge: GET
title: 입금 내역 목록 조회
type: openapi-operation
---
`cursor`로 이어 읽는 동안 `status`·`from`·`to`를 바꾸지 않는다. 커서는 정렬 키만 담으므로 조건을 바꾸면
새 조건에 맞으면서 커서보다 앞에 정렬되는 건이 그 순회에서 빠진다. 조건을 바꾸려면 `cursor` 없이 처음부터 조회한다.

<Operation source="reference-wallet" id="deposits-list" />
