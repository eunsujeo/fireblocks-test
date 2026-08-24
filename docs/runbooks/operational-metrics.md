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

DB 신호는 인박스, outbox, 대사 중단, 미보관 원문, 작업 heartbeat 단위로 독립 조회한다. 특정 신호 조회가 실패하면 해당
숫자 gauge는 `-1`로 바뀌고 `bcm.operational.signal.refresh{signal,outcome="failed"}` counter가 증가한다. 다른 신호는 계속
갱신된다. job heartbeat 조회가 실패하면 오래된 시계열은 제거되므로 `signal="job-heartbeat"` 실패 counter와 heartbeat
시계열 부재를 함께 감시한다. `-1`, 시계열 부재 또는 실패 counter 증가는 정상값으로 취급하지 않고 DB 연결·쿼리 오류를 조사한다.

`bcm.operational.signal.refresh{outcome="success"}`가 계속 증가하는지와 각 job heartbeat가 함께 전진하는지 확인한다.
