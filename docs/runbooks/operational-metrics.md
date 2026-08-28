# 운영 메트릭 노출

`bcm-api`의 Actuator는 공개 웹훅 리스너와 분리된 관리 리스너에서만 제공한다. 기본값은
`127.0.0.1:9090`이며 운영에서는 내부 전용 서비스·망 정책을 함께 적용한다.

## 설정

- `BCM_MANAGEMENT_PORT` — 기본 `9090`
- `BCM_MANAGEMENT_ADDRESS` — 기본 `127.0.0.1`; 원격 수집이 필요하면 내부망 주소로만 변경
- `BCM_MANAGEMENT_ENDPOINTS` — 기본 `health,metrics`; `info`는 노출하지 않음
- `BCM_OPERATIONAL_METRICS_FIXED_DELAY_MILLIS` — 기본 `60000`
- `BCM_OPERATIONAL_METRICS_INITIAL_DELAY_MILLIS` — 기본 `60000`

공개 애플리케이션 포트의 `/actuator/metrics`는 404여야 한다. 배포 검증에서 공개 주소와 관리 주소를 각각 요청해 이를 확인한다.

## 수집 실패 판정

DB 신호는 인박스, outbox, Sweep 요청·결과 event·DAW 완료, 대사 중단, 미보관 원문, 작업 heartbeat 단위로 독립 조회한다. 특정 신호 조회가 실패하면 해당
숫자 gauge는 `-1`로 바뀌고 `bcm.operational.signal.refresh{signal,outcome="failed"}` counter가 증가한다. 다른 신호는 계속
갱신된다. job heartbeat 조회가 실패하면 오래된 시계열은 제거되므로 `signal="job-heartbeat"` 실패 counter와 heartbeat
시계열 부재를 함께 감시한다. `-1`, 시계열 부재 또는 실패 counter 증가는 정상값으로 취급하지 않고 DB 연결·쿼리 오류를 조사한다.

`bcm.operational.signal.refresh{outcome="success"}`가 계속 증가하는지와 각 job heartbeat가 함께 전진하는지 확인한다.

## Sweep 경보 입력

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
