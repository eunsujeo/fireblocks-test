---
search:
  tags:
    - Accounts
    - POST
seo:
  description: >-
    계정을 활성/비활성으로 전환한다. 요청 안에서 종결되며 승인을 거치지… Reference for the POST
    /api/v1/accounts/{accountId}/status endpoint in the Wallet API API.
sidebar:
  label: 계정 상태 변경
  badge: POST
title: 계정 상태 변경
type: openapi-operation
---
계정을 활성/비활성으로 전환한다. 요청 안에서 종결되며 승인을 거치지 않는다.

이미 그 상태면 아무것도 바꾸지 않고 성공으로 종결한다.

**비활성화는 그 계정의 주소록 등록을 전부 삭제한다.** 되돌릴 수 없다 — 다시 활성화해도
주소록은 복구되지 않으므로, 최종 사용자가 지갑 소유 증명을 거쳐 다시 등록해야 한다.
비활성 계정에는 새 지갑도 새 주소록도 만들 수 없다. 이미 만들어진 지갑으로 들어오는 입금은
계속 기록되지만, 주소록 근거가 사라졌으므로 등록된 발신처로 판정되지 않는다.

Errors:
- 404 ACCOUNT_NOT_FOUND: accountId로 매칭되는 계정 없음
- 400 INVALID_REQUEST_BODY: status가 비었거나 허용 값이 아님
- 400 INVALID_PATH_VARIABLE: accountId 형식이 올바르지 않음

<Operation source="reference-wallet" id="accounts-change-status" />
