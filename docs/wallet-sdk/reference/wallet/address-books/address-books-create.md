---
search:
  tags:
    - Address Books
    - POST
seo:
  description: >-
    발급받은 nonce의 message에 대한 지갑 서명을 검증해 등재한다.… Reference for the POST
    /api/v1/accounts/{accountId}/address-books endpoint in the Wallet API API.
sidebar:
  label: 주소록 등록 (지갑 소유 증명 nonce 서명 포함)
  badge: POST
title: 주소록 등록 (지갑 소유 증명 nonce 서명 포함)
type: openapi-operation
---
발급받은 nonce의 `message`에 대한 지갑 서명을 검증해 등재한다. 등록 자체가 소유 증명
게이트라 별도 승인 절차가 없다.

Errors:
- 400 OWNERSHIP_PROOF_REJECTED: nonce가 없거나 만료·소비됐거나 서명이 대상 주소의 것이 아니다
- 400 ADDRESS_ALREADY_REGISTERED: 같은 체인 좌표에 이미 등재된 주소다

<Operation source="reference-wallet" id="address-books-create" />
