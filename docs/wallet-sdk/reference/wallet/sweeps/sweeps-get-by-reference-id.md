---
search:
  tags:
    - Sweeps
    - GET
seo:
  description: >-
    referenceId로 스윕을 상세 조회한다 — 생성 응답을 받지 못했을 때(타임아웃 등) 스윕 id 없이… Reference for
    the GET /api/v1/sweeps endpoint in the Wallet API API.
sidebar:
  label: 스윕 referenceId 조회
  badge: GET
title: 스윕 referenceId 조회
type: openapi-operation
---
referenceId로 스윕을 상세 조회한다 — 생성 응답을 받지 못했을 때(타임아웃 등) 스윕 id 없이
상태를 확인하는 복구 경로다. referenceId는 tenant 안에서 유일하다.
referenceId 쿼리 파라미터가 있을 때만 이 핸들러로 라우팅된다.

<Operation source="reference-wallet" id="sweeps-get-by-reference-id" />
