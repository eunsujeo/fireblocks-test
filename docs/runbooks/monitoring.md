# 모니터링: 메트릭과 경보

개발·운영 담당자가 BCM의 적체·수집 실패·경보 전달 상태를 확인하는 절차다.
[메트릭 수집](#메트릭-수집) · [경보 채널](#경보-채널) · [웹훅 복구](webhook-recovery.md)
로그 기록·보존 계약은 [운영 로그 정책](../design/11-operational-log-policy.md)을 따른다.

## 메트릭 수집

`bcm-api`의 Actuator는 업무 API 리스너와 분리된 관리 리스너에서만 제공한다. 기본값은
`127.0.0.1:9090`이며 운영에서는 내부 전용 서비스·망 정책을 함께 적용한다.

### 설정

- `BCM_MANAGEMENT_PORT` — 기본 `9090`
- `BCM_MANAGEMENT_ADDRESS` — 기본 `127.0.0.1`; 원격 수집이 필요하면 내부망 주소로만 변경
- `BCM_MANAGEMENT_ENDPOINTS` — 기본 `health,metrics`; `info`는 노출하지 않음
- `BCM_OPERATIONAL_METRICS_FIXED_DELAY_MILLIS` — 기본 `60000`
- `BCM_OPERATIONAL_METRICS_INITIAL_DELAY_MILLIS` — 기본 `60000`

공개 애플리케이션 포트의 `/actuator/metrics`는 404여야 한다. 배포 검증에서 공개 주소와 관리 주소를 각각 요청해 이를 확인한다.

### 수집 실패 판정

DB 신호는 인박스, outbox, Sweep 요청·결과 event·DAW 완료, 대사 중단, 미보관 원문, 작업 heartbeat 단위로 독립 조회한다. 특정 신호 조회가 실패하면 해당
숫자 gauge는 `-1`로 바뀌고 `bcm.operational.signal.refresh{signal,outcome="failed"}` counter가 증가한다. 다른 신호는 계속
갱신된다. job heartbeat 조회가 실패하면 오래된 시계열은 제거되므로 `signal="job-heartbeat"` 실패 counter와 heartbeat
시계열 부재를 함께 감시한다. `-1`, 시계열 부재 또는 실패 counter 증가는 정상값으로 취급하지 않고 DB 연결·쿼리 오류를 조사한다.

`bcm.operational.signal.refresh{outcome="success"}`가 계속 증가하는지와 각 job heartbeat가 함께 전진하는지 확인한다.

### Sweep 경보 입력

| metric | 의미 | 운영 판단 |
|---|---|---|
| `bcm.sweep.request.pending` | 미종결 요청 수 | 지속 증가하면 BAT claim·gate 상태 확인 |
| `bcm.sweep.request.oldest.age.seconds` | 가장 오래된 미종결 요청 나이 | 정책상 최대 대기 시간을 넘으면 요청 ID로 Admin 조사 |
| `bcm.sweep.request.blocked` | gate 차단 요청 수 | gate 재개 승인 원장 확인. Admin에서 임의 완료하지 않음 |
| `bcm.sweep.request.failed` | 종결 실패 요청 수 | 실패 코드·policy/contract snapshot 확인 |
| `bcm.sweep.target.repeated.failure` | 제출 시도 횟수가 `bcm.sweep.repeated-failure-alert-threshold` 이상인 target 수 | 실행 gate 중지 후 벤더·컨트랙트·allowance 원인 조사. 자동 성공·실패 종결하지 않음 |
| `bcm.sweep.event.pending` | 아직 relay되지 않은 `sweep-events` 수 | outbox relay·Kafka 상태 확인 |
| `bcm.sweep.event.failed` | 발행 실패로 격리된 `sweep-events` 수 | eventId로 실패 원인 확인 후 복구 절차 적용 |
| `bcm.sweep.completion.waiting` | 발행 성공 후 DAW 완료 미확인 수 | DAW consumer와 완료 API 호출 여부 확인 |
| `bcm.sweep.completion.oldest.age.seconds` | 가장 오래된 DAW 완료 대기 나이 | 소비 offset과 eventId 업무 반영 원장 대조 |

임계값과 severity는 배포 환경의 외부 모니터링이 정한다. `event pending/failed`와 `completion waiting`은 서로 다른 장애이므로
하나의 합계로 경보하지 않는다.

## 경보 채널

운영 경보는 고객 이벤트 토픽과 분리해 사내 운영 알림 수신기의 HTTP endpoint로 전달한다. API·Webhook·BAT가 같은 계약을 사용한다.
기본값은 비활성이므로 운영 배포 전 수신기를 준비하고 아래 환경변수를 주입해야 한다.

### 설정

- `BCM_OPERATIONAL_ALERT_ENABLED=true`
- `BCM_OPERATIONAL_ALERT_ENDPOINT=https://<approved-internal-receiver>/alerts`
- `BCM_OPERATIONAL_ALERT_BEARER_TOKEN=<secret-manager-reference>` (활성화 시 필수)
- 선택: `BCM_OPERATIONAL_ALERT_CONNECT_TIMEOUT_MILLIS`(기본 1000),
  `BCM_OPERATIONAL_ALERT_READ_TIMEOUT_MILLIS`(기본 2000)

활성화했는데 endpoint가 비어 있거나 HTTP(S) URI가 아니면 애플리케이션 시작을 거부한다. 토큰은 환경변수 또는 시크릿 매니저로만
주입하고 로그·설정 파일에 기록하지 않는다. endpoint의 인증·접근 제어와 route별 실제 메신저 연결은 운영 수신기가 담당한다.

### 전달 계약

`POST` JSON 본문은 `schemaVersion`, `route`, `type`, UTC `occurredAt`, `identifiers`, `context`로 구성한다.
경보 등급과 호출 정책은 이 데이터를 받는 외부 모니터링이 판단한다.
활성화된 채널은 항상 `Authorization: Bearer ...`를 붙인다. 주요 route와 type은 다음과 같다.

| route | type 예시 | 대상 |
|---|---|---|
| `webhook` | `webhook.alert.poison`, `webhook.alert.unattributed-deposit` | poison·귀속 불명·미등록 vault 발신 |
| `event-delivery` | `event-delivery.alert.poison` | 격리된 outbox |
| `transaction` | `transaction.stall.detected`, `transaction.alert.submission-conflict` | 막힘·제출 결과 기록 충돌 |
| `sweep` | `sweep.alert.reconciliation-failed` | 선별·제출·대사 단계 예외 |
| `reconciliation` | `reconciliation.alert.missing-webhook`, `reconciliation.alert.tracking-stopped` | 웹훅 누락 복구·자동 추적 중단 |
| `asset` | `asset.alert.unmapped-vendor-asset`, `asset.alert.chain-id-changed` | 자산/체인 카탈로그 설정 이상 |

본문에는 업무 식별자, enum 상태, 건수, 예외 클래스명만 넣는다. 주소·금액·웹훅 원문·서명·시크릿·예외 메시지는 보내지 않는다.

### 장애 처리와 확인

운영 채널의 timeout·연결 실패·2xx 외 응답은 원래 자금 이동, 웹훅 처리, 대사 트랜잭션을 롤백하지 않는다. 대신 ERROR 로그와
`bcm.operational.alert.delivery{route,type,outcome="failed"}` counter를 남긴다. 비활성 상태에서 경보가 발생하면 outcome은
`disabled`다. 운영에서는 다음을 함께 경보한다.

1. `failed` 또는 `disabled`가 0보다 커짐
2. 수신기에서 route/type별 메시지 도착 및 downstream 전달 확인
3. API·Webhook·BAT 각각에서 허용된 테스트 경보를 보내 식별자·상태 외 정보가 없는지 확인

운영 채널 복구 후 원 업무를 임의 재실행하지 않는다. 각 경보의 식별자로 현재 DB·벤더 상태를 다시 조회한 뒤 해당 runbook의
재처리 절차를 따른다.
