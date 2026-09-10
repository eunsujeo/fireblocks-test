---
seo:
  description: Wallet Policy Engine Internal API API reference.
sidebar:
  label: Overview
title: Wallet Policy Engine Internal API
---
<ApiOverview source="reference-policy" />

## Admins

어드민 신원·역할 디렉토리.

<ApiTagOperations source="reference-policy" tag="admins" />

## Approvals

maker-checker 승인. 정족수 obligation의 이행 표면이고, 요청자와 승인자는 같을 수 없다.

<ApiTagOperations source="reference-policy" tag="approvals" />

## Admin Auth

어드민 인증. 챌린지 모델로 2FA를 수집한다.

<ApiTagOperations source="reference-policy" tag="admin-auth" />

## Bundle Serving

OPA 사이드카가 활성 번들을 당겨 가는 표면. 자격은 서비스 크리덴셜이다.

<ApiTagOperations source="reference-policy" tag="bundle-serving" />

## Callback

서명 인프라 콜백. 서명 이전의 마지막 관문이다.

<ApiTagOperations source="reference-policy" tag="callback" />

## PIP Source Kinds

PIP 소스 종류 카탈로그.

<ApiTagOperations source="reference-policy" tag="pip-source-kinds" />

## Partner

비동기 PIP 결과 제출. 아웃바운드 트리거에 대한 인증된 인바운드다.

<ApiTagOperations source="reference-policy" tag="partner" />

## Bootstrap

초기 어드민 등재. 온보딩 런북의 첫 단계다.

<ApiTagOperations source="reference-policy" tag="bootstrap" />

## Policy Bundle

정책 로직 번들 발행·활성화. 플랫폼 거버넌스가 소유한다.

<ApiTagOperations source="reference-policy" tag="policy-bundle" />

## Workspace Registration

워크스페이스 등록과 매니저 관리. platform 문 전용이다.

<ApiTagOperations source="reference-policy" tag="workspace-registration" />

## Policy Draft

룰 저작. 컴파일 검증을 지나야 발행 후보가 된다.

<ApiTagOperations source="reference-policy" tag="policy-draft" />

## Request

정책 판단 요청 접수와 조회. 202 접수 후 비동기 평가다.

<ApiTagOperations source="reference-policy" tag="request" />

## Workspace Bundle

워크스페이스 번들 발행·활성화. 고객사가 자기 정책을 저작한다.

<ApiTagOperations source="reference-policy" tag="workspace-bundle" />
