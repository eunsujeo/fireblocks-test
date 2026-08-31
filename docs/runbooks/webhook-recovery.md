# 웹훅 구독 복구

Fireblocks Webhooks V2 구독이 `DISABLED` 또는 `SUSPENDED`가 되었거나 수신기가 오래 정지한 뒤 사용하는 수동 절차다.
자동 시작 호출은 하지 않는다. 운영 승인된 JMX 관리 경로에서만 상태 조회와 복구를 실행한다.

## 권한과 사전 조건

- DB와 웹훅 수신기가 먼저 정상이어야 한다. DB가 계속 실패하는 상태에서 재전송하면 다시 실패 알림을 만든다.
- `bcm-bat`에 `FIREBLOCKS_WEBHOOK_ID`를 설정한다. API key와 private key는 기존 시크릿 주입 경로만 사용한다.
- sandbox 실측과 설계 정본 동기화가 끝난 환경에서만 `BCM_WEBHOOK_RECOVERY_ENABLED=true`로 JMX 조작면을 연다.
  기본값은 `false`이며, 비활성 상태에서는 `WebhookRecovery` MBean이 등록되지 않는다.
- 상태 조회·재전송 권한과 웹훅 활성화 권한을 확인한다. 활성화 API는 Admin 수준 권한이 필요하므로 상시 런타임 키를
  임의로 승격하지 않는다. 필요하면 승인된 복구용 자격증명을 격리된 BAT 인스턴스에 일시 주입하고 작업 후 제거한다.
- JMX 원격 접속의 인증·암호화·네트워크 제한은 배포 환경에서 제공해야 한다. 공개 포트로 노출하지 않는다.
- 구독 조회의 경로·상태 값은 Fireblocks [Get webhook by id](https://developers.fireblocks.com/api-reference/webhooks-v2/get-webhook-by-id),
  `enabled=true` 활성화 본문과 Admin 권한은 [Update webhook](https://developers.fireblocks.com/reference/updatewebhook) reference를 기준으로 한다.

## 절차

1. JMX 클라이언트에서 `org.springframework.boot:type=Endpoint,name=WebhookRecovery` MBean을 연다.
2. `status`를 실행한다. `missingRequiredEvents`가 비어 있어야 한다. 값이 있으면 이벤트 구독 범위를 자동 변경하지 말고
   Fireblocks Console의 승인된 변경 절차로 다음 네 이벤트를 복구한 뒤 다시 확인한다.
   - `transaction.created`
   - `transaction.status.updated`
   - `transaction.approval_status.updated`
   - `transaction.network_records.processing_completed`
3. DB와 수신기 정상화를 다시 확인한 뒤 `recover`를 한 번 실행한다.
   - 현재 상태가 `DISABLED` 또는 `SUSPENDED`면 `enabled=true`로 활성화한다.
   - 활성 응답이 `ENABLED`가 아니거나 구독 ID·필수 이벤트가 다르면 재전송하지 않고 실패한다.
   - 활성 상태가 확인된 뒤 `POST /v1/webhooks/{webhookId}/notifications/resend_failed`를 접수한다.
4. 결과의 `scheduledNotificationCount`는 호출 시점에 재전송 대상으로 예약된 실패 알림 수다. 수신 완료 건수가 아니다.
   PoC에서는 다음 분 단위 배차에 도착했지만 정확한 도착 시각을 전제로 삼지 않는다.
5. `bcm.webhook.last.received.timestamp.seconds`, `bcm.webhook.inbox.pending`, 처리 오류와 tx 대사 누락 신호를 관찰한다.
   재수신은 기존 `noti_id` 멱등과 상태 전이 규칙을 그대로 거치므로 중복 알림을 직접 삭제하지 않는다.

## 재실행과 복구 범위

- 같은 이벤트 재전송은 5분 내 반복하지 않는다. 앞선 요청의 지연 도착을 먼저 관찰한다.
- `scheduledNotificationCount=0`은 현재 `resend_failed` 범위에 실패 상태 알림이 없다는 뜻이지, 장애 구간 전체의 무결성을
  증명하지 않는다.
- 2026-08-17 확인한 Fireblocks [Resend failed notifications](https://developers.fireblocks.com/api-reference/webhooks-v2/resend-failed-notifications)
  reference에서 `resend_failed`는 최근 24시간 실패 알림 대상이다. Webhooks V2 일반 안내의
  “최대 30일 재전송”은 `resourceId` 지정 방식이고, query 방식은 최근 72시간 안에서 요청 창 최대 24시간이다. 이 러너는
  범위를 추측해 넓히지 않으며 24시간보다 오래된 공백은 기존 tx 대사로 복구한다. 계약 근거는
  [Fireblocks QnA](../design/90-fireblocks-qna.md)와 [해결 이력 #40](../history/resolved-design-items.md)에 보존한다.
- 활성화까지 성공하고 재전송이 실패한 경우 다시 `status`를 확인한 뒤 `recover`를 재실행할 수 있다. 응답을 복구 완료로
  간주하지 말고 실제 수신·처리 지표와 대사 결과로 종료를 판단한다.

## 종료 확인

- 구독 상태 `ENABLED`, 필수 이벤트 누락 없음
- 재전송 대상이 있으면 마지막 정상 수신 시각 전진 확인. 대상이 0이면 tx 대사 결과로 장애 구간 무결성 확인
- 인박스 적체가 정상 범위로 감소하고 poison 증가 없음
- 다음 tx 대사에서 누락 복구 건수가 정상 범위이며 성공 heartbeat가 전진
