-- bcm:transaction=off
-- docs/design/03-bcm-db.md: V29 인박스 재시도 대기 시각.
-- 지금까지 인박스에는 backoff가 없었다. 워커는 가장 오래된 P 행을 주기(기본 500ms)마다 즉시 다시 집으므로
-- 실패가 반복되면 상한(기본 3회)을 **1~2초 만에** 소진하고 격리된다.
-- 일시적 사정(벤더 지연, 아직 도착하지 않은 짝 알림)으로 실패하는 건은 그 시간 안에 해소될 수 없어
-- 재시도가 사실상 무의미했다 — Dfns 발신 이동이 전송 알림보다 먼저 온 경우가 그 예다(계약13).
-- 다음 시도 시각을 두어 실패마다 대기 구간을 늘리고, 워커는 그 시각이 지난 행만 집는다.
-- NULL 허용 추가 전용이라 기존 행은 즉시 대상이다(NULL = 지금 바로).
ALTER TABLE bcm_whk_l
  ADD COLUMN next_attmpt_dttm VARCHAR(16) NULL;

-- 집기 인덱스도 대기 시각을 포함한다 — 그러지 않으면 대기 중인 행까지 매번 훑는다.
-- 일반 CREATE INDEX는 만드는 동안 쓰기를 막으므로 온라인으로 만든다(V18·V26과 같은 패턴).
DROP INDEX CONCURRENTLY IF EXISTS idx_bcm_whk_pick_next;
CREATE INDEX CONCURRENTLY idx_bcm_whk_pick_next
  ON bcm_whk_l (prcs_stcd, next_attmpt_dttm, rcv_dttm);
