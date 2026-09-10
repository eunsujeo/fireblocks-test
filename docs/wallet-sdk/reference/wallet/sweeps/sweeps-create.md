---
search:
  tags:
    - Sweeps
    - POST
seo:
  description: >-
    스윕 대상 입금에서 출발 좌표(vault·토큰·금액)를 유도해 집금 vault로의 이동을 요청한다.… Reference for the
    POST /api/v1/sweeps endpoint in the Wallet API API.
sidebar:
  label: 스윕 요청 생성
  badge: POST
title: 스윕 요청 생성
type: openapi-operation
---
스윕 대상 입금에서 출발 좌표(vault·토큰·금액)를 유도해 집금 vault로의 이동을 요청한다.
요청은 depositId와 목적지만 지정한다.

Errors:
- 400 INVALID_AMOUNT: 입금 금액이 토큰 decimals 정밀도와 불일치
- 404 DEPOSIT_NOT_FOUND: depositId로 매칭되는 입금 없음 (타 고객사 소유 포함)
- 404 TOKEN_NOT_FOUND: 입금 토큰이 카탈로그에 없음

<Operation source="reference-wallet" id="sweeps-create" />
