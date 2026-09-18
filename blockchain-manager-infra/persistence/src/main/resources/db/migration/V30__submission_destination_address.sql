-- bcm:transaction=off
-- docs/design/03-bcm-db.md: V30 제출 목적지 주소 보관.
-- 내부이체(rcv_dvcd = ACCOUNT)는 목적지가 계정이라 rcv_vl 에 accountId 가 들어간다.
-- 그런데 Dfns 의 회수는 벤더 조회가 아니라 **같은 본문 재제출**이고 본문의 to 는 주소다 —
-- 지금 회수는 rcv_vl 을 그대로 to 로 쓰므로 내부이체를 열면 accountId 를 주소 자리에 보내게 된다.
-- V28 canonical 이 "제출 본문을 재구성하는 재료 한 벌"인데 to 가 빠져 있었고,
-- 주소 수신자만 지원하는 동안 드러나지 않았다(계약13 "내부이체").
--
-- NOT VALID 뒤 VALIDATE 로 전체 검사 동안 쓰기를 막지 않으려면 트랜잭션 밖이어야 한다.
-- 그래서 **문장마다 커밋된다** — 아래 순서는 중간에 죽어도 안전하고 다시 돌려도 안전하게 짰다.
-- 온라인 인덱스 생성은 V31 로 나눴다.
ALTER TABLE bcm_sbmt_l
  ADD COLUMN IF NOT EXISTS vndr_dst_addr VARCHAR(256) NULL;

-- 기존 Dfns 행은 모두 ADDRESS 수신자다(내부이체를 아직 열지 않았다) — rcv_vl 이 곧 보낸 주소다.
-- 백필하지 않으면 회수가 항상 이 컬럼을 읽으므로 기존 REQUESTED 행의 회수가 영영 불가능해진다.
-- 추측이 아니라 정확한 값이라 그대로 옮긴다.
UPDATE bcm_sbmt_l
   SET vndr_dst_addr = rcv_vl
 WHERE vndr_wlt_id IS NOT NULL
   AND vndr_dst_addr IS NULL
   AND rcv_dvcd = 'ADDRESS';

-- 넷만 검사하면 목적지만 빠진 불완전한 회수 snapshot 을 DB 가 계속 허용한다 — 다섯 값 all-or-none 으로 넓힌다.
-- **옛 제약을 끝까지 남겨 둔 채 새 이름으로 만든다.** 지우고 만들면 그 사이에 죽었을 때 canonical 제약이
-- 아예 없는 상태가 남고, 재실행은 이미 사라진 제약을 지우려다 또 죽는다.
-- 앞선 실행이 남긴 미검증 제약이 있을 수 있어 먼저 지운다 — 이 시점엔 옛 제약이 여전히 지키고 있다.
ALTER TABLE bcm_sbmt_l DROP CONSTRAINT IF EXISTS ck_bcm_sbmt_vndr_canonical_v30;

ALTER TABLE bcm_sbmt_l
  ADD CONSTRAINT ck_bcm_sbmt_vndr_canonical_v30 CHECK (
    (vndr_wlt_id IS NULL AND vndr_ast_id IS NULL AND base_amt IS NULL AND dcml_cnt IS NULL AND vndr_dst_addr IS NULL)
    OR (vndr_wlt_id IS NOT NULL AND vndr_ast_id IS NOT NULL AND base_amt IS NOT NULL AND dcml_cnt IS NOT NULL
        AND vndr_dst_addr IS NOT NULL)
  ) NOT VALID;

-- 검증이 끝나야 옛 제약을 놓는다. 이미 검증된 제약을 다시 검증하는 것은 무해하다.
ALTER TABLE bcm_sbmt_l VALIDATE CONSTRAINT ck_bcm_sbmt_vndr_canonical_v30;

ALTER TABLE bcm_sbmt_l DROP CONSTRAINT IF EXISTS ck_bcm_sbmt_vndr_canonical;
