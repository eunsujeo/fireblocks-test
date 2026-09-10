---
seo:
  description: >-
    travel-rule-gateway HTTP API. Gateway가 연동하고 있는 VV의 Enclave는 별도 MYSQL을 운영해야하며
    이 DB에서는 통신한 데이터 전문을 기록하고 있다. 해당 전문은…
sidebar:
  label: Overview
title: Travel Rule Gateway API
---
travel-rule-gateway HTTP API.
Gateway가 연동하고 있는 VV의 Enclave는 별도 MYSQL을 운영해야하며 이 DB에서는 통신한 데이터 전문을 기록하고 있다.
해당 전문은 모두 암호화되고 있으며 암호화의 키는 사용측에서 발행하고 관리한다. (API 구현을 통해 암호화키 전달)
따라서 암호화 되어있다고는 하지만 개인정보에 해당하는 데이터이므로 적격기관에서 운용하고 관리해야한다.
이 Gateway는 트래블룰 처리를 위한 인터페이스 통합역할을 할수있지만 데이터 보관권한은 없으므로 DB와 Enclave는 보유대상이 아닌 연동대상이다.

<ApiOverview source="reference-travel-rule" />

## Service API

트래블룰 게이트웨이 서비스 표면.

<ApiTagOperations source="reference-travel-rule" tag="service-api" />

## VerifyVASP Inbound

VerifyVASP 인바운드 수신.

<ApiTagOperations source="reference-travel-rule" tag="verifyvasp-inbound" />
