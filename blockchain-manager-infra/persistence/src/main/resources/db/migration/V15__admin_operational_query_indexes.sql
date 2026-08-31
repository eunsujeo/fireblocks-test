-- 운영/Admin 식별자 검색과 backlog 집계가 원장 전체 scan으로 퇴행하지 않도록 조회 축을 고정한다.
CREATE INDEX idx_bcm_whk_vendor_time
  ON bcm_whk_l (vndr_tx_id, rcv_dttm, noti_id)
  WHERE vndr_tx_id IS NOT NULL;

CREATE INDEX idx_bcm_outbox_vendor_event
  ON bcm_outbox_l (vndr_tx_id, evnt_id);

CREATE INDEX idx_bcm_outbox_sweep_item_event
  ON bcm_outbox_l ((payload ->> 'sweepItemId'), evnt_id)
  WHERE topic = 'sweep-events';

CREATE INDEX idx_bcm_outbox_sweep_completion_wait
  ON bcm_outbox_l (pub_dttm, evnt_id)
  WHERE topic = 'sweep-events' AND evnt_stcd = 'S';

CREATE INDEX idx_bcm_swp_exec_tx_hash
  ON bcm_swp_exec_l (tx_hash)
  WHERE tx_hash IS NOT NULL;

CREATE INDEX idx_bcm_sbmt_sweep_execution
  ON bcm_sbmt_l (swp_exec_id)
  WHERE swp_exec_id IS NOT NULL;

CREATE INDEX idx_bcm_swp_target_attempt
  ON bcm_swp_trgt (try_cnt);
