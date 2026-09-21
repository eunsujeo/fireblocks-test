# 업무별 설계 찾기

BCM을 구현하거나 연동하는 개발자를 위한 설계 정본이다. 전체를 순서대로 읽기보다 작업할 업무에서 시작한다.
[전체 문서 안내](../README.md) · [코드 모듈 구조](../standards/architecture.md) · [HTTP 계약](../api/openapi.yaml)

## 업무별 읽기 순서

| 하려는 일 | 먼저 읽기 | 필요할 때 더 읽기 |
|---|---|---|
| 시스템 파악 | [구성·보안 경계](01-infra.md) | [자산 이동 지도](09-asset-map.md) |
| Fireblocks·Dfns·로컬 호환 | [제공자 선택·공통 포트](12-provider-compatibility.md) | [Dfns 지갑·웹훅 계약](13-dfns-contracts.md) · [코드/API 인벤토리](evidence/92-provider-compatibility-inventory.md) · [전체 계획](../dfns-compatibility-plan.md) |
| 계정·주소 생성 | [계정 생성·조회](02-bcm-flow.md#계정-생성--입금-주소-발급--조회) | [자산 매핑](07-asset-master.md) · [DB](03-bcm-db.md#찾아보기) |
| 입금·출금·내부이체 | [거래·이벤트 흐름](02-bcm-flow.md) | [웹훅 부하·장애 사례](99-detection-detail.md) |
| Sweep 실행 | [Sweep 정책·실행 계약](06-sweep.md) | [채택·실측 근거](#실측과-채택-근거) |
| Admin 개발 | [권한·기능·화면](08-bcm-admin.md#찾아보기) | [승인 기준 화면](../admin-reference/README.md) |
| DB 변경 | [피처별 테이블 찾기](03-bcm-db.md#찾아보기) | [자산 카탈로그 테이블](07-asset-master.md) |
| 로컬 통합 테스트 | [Stub·Anvil 계약](10-local-fireblocks-integration.md) | [실행 명령](../../README.md) · [테스트 전략](../testing.md) |
| 운영·장애 조사 | [상황별 운영 절차](../runbooks/README.md) | [로그 정책](11-operational-log-policy.md) · [웹훅 장애 사례](99-detection-detail.md) |

## 실측과 채택 근거

벤더 필드·동작을 확인할 때 읽는다. 실측 원문과 당시 조건을 보존하며 현재 구현 계약은 위 업무별 설계를 따른다.

| 확인할 것 | 근거 |
|---|---|
| 벤더 확답·공식 자료 확인 기록 | [Fireblocks QnA](evidence/90-fireblocks-qna.md) · [Dfns 기능 요청](evidence/91-dfns-feature-requests.md) |
| 웹훅 필드·재시도·서명 | [payload 원문](evidence/96-payload-sample.md) · [Webhook PoC](evidence/97-webhook-poc-result.md) |
| 배치 항목 귀속·부분 실패 | [배치 payload](evidence/94-batch-payload-sample.md) · [부분 실패 payload](evidence/93-batch-partial-fail-sample.md) |
| approve 제출·배치 동작 | [approve + transferFrom PoC](evidence/95-approve-pull-poc-result.md) |
| Sweep 방식 선택 이유 | [대안 비교·채택 근거](evidence/98-batch-sweep.md) — 현행 출시 조건은 [Sweep 설계](06-sweep.md#출시-게이트와-확인-목록) |

## 외부 시스템 맥락

BCM의 구현 범위와 구별해서 읽는 연동 참고자료다. 외부 서비스의 현재 배포 상태를 의미하지 않는다.

- 컴플라이언스 게이트: [흐름](context/04-compliance-flow.md) · [DB](context/05-compliance-db.md).
- 서명 인프라: [Co-signer HA 구성](context/12-cosigner-ha.md).

## 변경 규칙

- `docs/design/`는 2026-09-08 사용자 결정에 따른 이 저장소의 설계 정본이다. 외부 wiki clone·동기화·byte 비교를 요구하지 않는다.
- 사용자 요청·확정 결정에 따라 수정한다. 미확정 계약을 추측하지 않고 [PLAN 미해결 표](../../PLAN.md)에 기록한다.
- 이벤트·상태·DB·정책 변경은 영향 문서와 코드·테스트·HTTP API 계약을 함께 대조한다.
- HTTP 정본 변경 후 `python3 docs/api/build.py`로 생성물을 갱신한다. 생성물을 직접 편집하지 않는다.
- 내부 링크는 상대 경로를 사용한다. 외부 출처의 원문을 확인하지 못한 내용은 추측으로 보완하지 않는다.
- 독립 설계·코드 검토는 [리뷰 절차](../ai/converge-review.md)를 따른다. 과거 동기화 기록은 현재 작업 지시가 아니다.
