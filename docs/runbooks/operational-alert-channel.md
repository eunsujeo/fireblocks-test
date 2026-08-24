# 운영 경보 HTTP 채널

운영 경보는 고객 이벤트 토픽과 분리해 사내 운영 알림 수신기의 HTTP endpoint로 전달한다. API와 BAT가 같은 계약을 사용한다.
기본값은 비활성이므로 운영 배포 전 수신기를 준비하고 아래 환경변수를 주입해야 한다.

## 설정

- `BCM_OPERATIONAL_ALERT_ENABLED=true`
- `BCM_OPERATIONAL_ALERT_ENDPOINT=https://<approved-internal-receiver>/alerts`
- `BCM_OPERATIONAL_ALERT_BEARER_TOKEN=<secret-manager-reference>` (활성화 시 필수)
- 선택: `BCM_OPERATIONAL_ALERT_CONNECT_TIMEOUT_MILLIS`(기본 1000),
  `BCM_OPERATIONAL_ALERT_READ_TIMEOUT_MILLIS`(기본 2000)

활성화했는데 endpoint가 비어 있거나 HTTP(S) URI가 아니면 애플리케이션 시작을 거부한다. 토큰은 환경변수 또는 시크릿 매니저로만
주입하고 로그·설정 파일에 기록하지 않는다. endpoint의 인증·접근 제어와 route별 실제 메신저 연결은 운영 수신기가 담당한다.

## 전달 계약

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

## 장애 처리와 확인

운영 채널의 timeout·연결 실패·2xx 외 응답은 원래 자금 이동, 웹훅 처리, 대사 트랜잭션을 롤백하지 않는다. 대신 ERROR 로그와
`bcm.operational.alert.delivery{route,type,outcome="failed"}` counter를 남긴다. 비활성 상태에서 경보가 발생하면 outcome은
`disabled`다. 운영에서는 다음을 함께 경보한다.

1. `failed` 또는 `disabled`가 0보다 커짐
2. 수신기에서 route/type별 메시지 도착 및 downstream 전달 확인
3. API와 BAT 양쪽에서 허용된 테스트 경보를 보내 식별자·상태 외 정보가 없는지 확인

운영 채널 복구 후 원 업무를 임의 재실행하지 않는다. 각 경보의 식별자로 현재 DB·벤더 상태를 다시 조회한 뒤 해당 runbook의
재처리 절차를 따른다.
