---
name: admin-feature
description: Blockchain Manager 전용 Admin 기능을 설계·OpenAPI·Backend·Frontend·테스트까지 세로줄로 구현하거나 리뷰하는 절차. 네트워크·자산·거래 조사·sweep·allowance·boost·컨트랙트·정책·밴드S·승인함·비상운영 Admin 화면/API를 추가하거나 변경할 때 사용한다.
---

# Admin Feature

Blockchain Manager Admin의 한 사용자 흐름을 계약부터 E2E까지 완성한다. 읽기 기능과 자산에 영향을 주는 변경 기능의 위험도를 먼저 가르고, 서버가 상태와 권한의 정본이 되게 한다.

## 1. 선행 계약 확인

1. `CLAUDE.md`, 현재 `PLAN.md`, `PROGRESS.md`를 읽는다.
2. `.claude/rules/admin-safety.md`, `admin-ux.md`, `policy-lifecycle.md`를 읽는다.
3. `docs/design/08-bcm-admin.md`와 기능별 정본을 읽는다.
   - 거래·웹훅·대사: `02-bcm-flow.md`, `03-bcm-db.md`
   - sweep·allowance·밴드S·컨트랙트: `06-sweep.md`, `03-bcm-db.md`
   - 네트워크·자산: `07-asset-master.md`
4. `docs/design/08-bcm-admin.md`가 없거나 필요한 계약이 미확정이면, 사용자 요청·확정 결정에 따라 이 저장소의 설계부터 정리한다. 미확정 정책은 사용자에게 확인한다.
5. HTTP 계약은 `docs/api/openapi.yaml`에서 확인한다. 생성물은 직접 고치지 않는다.

## 2. 사용자 흐름을 분류한다

다음 중 하나로 분류하고 위험 경계를 명시한다.

- 조회: 상태를 바꾸지 않는다.
- 변경 요청: 새 버전·요청만 만들고 활성 상태를 바꾸지 않는다.
- 승인·활성화: 요청자 분리, 정족수, stale 상태 재검사가 필요하다.
- 비상 중지: 신속해야 하지만 범위와 감사가 필요하다.
- 재개·상향·자금 이동: 중지보다 강한 승인과 최신 외부 상태 확인이 필요하다.

`08-bcm-admin.md`의 위험 등급을 그대로 적용한다. 일반 변경·자금 실행은 요청자 외 독립 승인자 1명,
보안 변경·재개는 요청자 외 서로 다른 승인자 2명과 그중 `BCM_SECURITY_APPROVER` 1명이 필요하다. 조회·신속 중지의
조건을 변경 작업에 재사용하지 않는다.

설계에 상태·승인·감사 규칙이 없으면 코드를 지어내지 말고 사용자 결정을 받아 `docs/design/`에 먼저 반영한다.

## 3. API와 UX를 함께 고정한다

1. 화면의 진입점, 검색·필터, 빈 상태, 권한 없음, stale data, 부분 실패, 완료 상태를 적는다.
2. 서버가 반환할 데이터와 가능한 action·금지 사유를 정의한다. 프론트가 상태 전이를 추론하지 않게 한다.
3. 목록의 페이지네이션·정렬·최대량을 정한다.
4. 변경 작업은 actor, 사유, 작업 티켓, 요청 ID, 멱등 범위를 정한다.
5. 장시간 작업은 접수와 완료를 분리하고 상태 조회 경로를 둔다.
6. private listener가 mTLS와 5분 이하 단기 JWT를 모두 검증하고, 서버 권한 판단은 검증된 역할 claim만 쓰게 한다.
7. `openapi.yaml`을 수정한 뒤 `python3 docs/api/build.py`로 생성물을 갱신한다.

## 4. 테스트를 먼저 고정한다

계약 판단은 red를 확인한 뒤 구현한다.

- Domain: 상태 전이, 요청자/승인자 분리, 위험 등급별 정확한 정족수, 상한, stale snapshot, idempotency.
- Persistence: 동시 활성화·중복 승인·중복 요청 DB 방어, append-only 이력.
- Controller: mTLS/JWT 경계, 역할 claim, validation, envelope, 오류 코드, 재요청.
- Frontend: loading·empty·error·forbidden·stale·partial·completed, URL 상태 보존.
- E2E: 조사 → 요청 → 승인 → 실행 → 재조회 → 감사 흐름.

기존 테스트를 약화하거나 구현과 같은 커밋에서 테스트를 수정하지 않는다.

## 5. 세로줄로 구현한다

1. Domain 모델과 포트에 판단을 둔다.
2. DB 스키마가 필요하면 `db-migration` skill을 적용한다.
3. Persistence에서 물리 제약과 동시성 방어를 구현한다.
4. Application은 오케스트레이션, Controller는 검증·변환만 담당한다.
5. Frontend는 OpenAPI 타입을 쓰고 BFF만 호출한다.
6. 고위험 변경은 optimistic update를 쓰지 않고 서버 재조회로 끝을 확인한다.
7. 프론트에서 금액·밴드S·정책 상한을 계산하지 않는다.
8. 밴드S snapshot·이동안은 DAW-CORE가 계산하고 BCM은 승인된 지시의 검증·멱등 실행·추적만 담당하게 한다.

## 6. 검증하고 닫는다

- 관련 모듈 테스트와 프론트 테스트를 좁게 실행한다.
- `./gradlew ktlintCheck`와 OpenAPI 생성물 신선도를 확인한다.
- UTC, 민감정보, 전체 ID 복사, URL 필터, 접근성, 중복 클릭을 확인한다.
- 구현 세션과 분리된 `design-sync` 뒤 독립 `code-reviewer`를 순차 실행한다. 실행기 교체 기준은
  `docs/ai/converge-review.md`를 따른다.
- `PROGRESS.md`를 50줄 이내로 갱신한다.

## 금지

- 브라우저의 BCM·Fireblocks·RPC 직접 호출.
- 직원 헤더를 인증으로 취급.
- mTLS나 단기 JWT 중 하나만으로 BFF→BCM 호출 허용.
- 정책·컨트랙트 활성 행 직접 수정·물리 삭제.
- 화면에 없는 서버 action을 프론트가 조립.
- raw webhook payload·시크릿·RPC 자격 노출.
- BCM에서 밴드S 금액을 다시 계산하거나 임의 cold 주소로 출금.
- 미확정 정족수·벤더 동작·온체인 필드를 추측해 구현.
