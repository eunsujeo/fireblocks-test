-- bcm:transaction=off
-- docs/design/03-bcm-db.md: V30 제출 목적지 주소 보관.
-- 내부이체(rcv_dvcd = ACCOUNT)는 목적지가 계정이라 rcv_vl 에 accountId 가 들어간다.
-- 그런데 Dfns 의 회수는 벤더 조회가 아니라 **같은 본문 재제출**이고 본문의 to 는 주소다 —
-- 지금 회수는 rcv_vl 을 그대로 to 로 쓰므로 내부이체를 열면 accountId 를 주소 자리에 보내게 된다.
-- V28 canonical 이 "제출 본문을 재구성하는 재료 한 벌"인데 to 가 빠져 있었고,
-- 주소 수신자만 지원하는 동안 드러나지 않았다(계약13 "내부이체").
ALTER TABLE bcm_sbmt_l
  ADD COLUMN vndr_dst_addr VARCHAR(256) NULL;

-- 기존 Dfns 행은 모두 ADDRESS 수신자다(내부이체를 아직 열지 않았다) — rcv_vl 이 곧 보낸 주소다.
-- 백필하지 않으면 회수가 항상 이 컬럼을 읽으므로 기존 REQUESTED 행의 회수가 영영 불가능해진다.
-- 추측이 아니라 정확한 값이라 그대로 옮긴다.
UPDATE bcm_sbmt_l
   SET vndr_dst_addr = rcv_vl
 WHERE vndr_wlt_id IS NOT NULL
   AND vndr_dst_addr IS NULL
   AND rcv_dvcd = 'ADDRESS';

-- 넷만 검사하면 목적지만 빠진 불완전한 회수 snapshot 을 DB 가 계속 허용한다 — 다섯 값 all-or-none 으로 넓힌다.
ALTER TABLE bcm_sbmt_l DROP CONSTRAINT ck_bcm_sbmt_vndr_canonical;

ALTER TABLE bcm_sbmt_l
  ADD CONSTRAINT ck_bcm_sbmt_vndr_canonical CHECK (
    (vndr_wlt_id IS NULL AND vndr_ast_id IS NULL AND base_amt IS NULL AND dcml_cnt IS NULL AND vndr_dst_addr IS NULL)
    OR (vndr_wlt_id IS NOT NULL AND vndr_ast_id IS NOT NULL AND base_amt IS NOT NULL AND dcml_cnt IS NOT NULL
        AND vndr_dst_addr IS NOT NULL)
  ) NOT VALID;

ALTER TABLE bcm_sbmt_l VALIDATE CONSTRAINT ck_bcm_sbmt_vndr_canonical;

-- 내부이체의 수신측 In 사건은 "발신 주소가 우리 지갑인가"를 물어야 한다. 자산 발급 기록이 아니라 **소유권**으로 판정한다 —
-- Dfns 제출은 그 symbol 의 주소 발급을 요구하지 않으므로 발급 기록으로 물으면 우리 주소를 못 알아본다(계약13).
-- 현재 Dfns 범위는 EVM 이라 소문자 기준이다. 16진수 주소는 대소문자에 정보가 없고, 벤더가 사건과 지갑 응답에서
-- 다른 표기를 줘도 같은 주소로 찾아야 한다. base58 네트워크가 열리면 그때 다시 정한다.
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_bcm_ntwk_wlt_addr
  ON bcm_ntwk_wlt_m (orgn_id, ntwk_cd, lower(wlt_addr));
