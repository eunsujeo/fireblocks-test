-- bcm:transaction=off
-- docs/design/03-bcm-db.md: V28 제출 시점의 벤더 canonical 값 보관.
-- Dfns의 회수는 조회가 아니라 "같은 본문 재제출"이라, 재구성한 본문이 최초 제출과 같아야 안전하다(계약13).
-- 그런데 기존 원장은 계정·network·symbol·사람 단위 금액만 남기고 req_hash 도 그 7값만 덮는다.
-- 그 사이 자산 매핑이나 정밀도가 교체되면 같은 키·같은 해시로 **다른 벤더 자산·다른 최소 단위 금액**이 나갈 수 있다.
-- 그래서 제출 시점에 확정한 벤더 지갑·자산 키·최소 단위 금액·적용 정밀도를 함께 적는다.
-- Fireblocks·로컬 경로는 벤더 조회로 회수하므로 이 값들을 쓰지 않는다 — NULL 허용 추가 전용이고 기존 행은 그대로 둔다.
--
-- 제약은 NOT VALID 로 먼저 걸고 따로 VALIDATE 한다. 즉시 검증하는 ADD CONSTRAINT 는 기존 행 전체를 훑는 동안
-- 강한 테이블 락을 잡아 Fireblocks 제출 경로까지 멈춘다. VALIDATE 는 약한 락이라 제출을 막지 않는다.
-- 그래서 이 파일은 트랜잭션 밖에서 실행한다(V18·V26과 같은 온라인 적용 패턴).
ALTER TABLE bcm_sbmt_l
  ADD COLUMN vndr_wlt_id VARCHAR(64)  NULL,
  ADD COLUMN vndr_ast_id VARCHAR(128) NULL,
  ADD COLUMN base_amt    VARCHAR(320) NULL,
  ADD COLUMN dcml_cnt    SMALLINT     NULL;

-- 넷은 한 벌이다 — 일부만 있으면 본문을 재구성할 수 없으므로 전부 있거나 전부 없어야 한다.
ALTER TABLE bcm_sbmt_l
  ADD CONSTRAINT ck_bcm_sbmt_vndr_canonical CHECK (
    (vndr_wlt_id IS NULL AND vndr_ast_id IS NULL AND base_amt IS NULL AND dcml_cnt IS NULL)
    OR (vndr_wlt_id IS NOT NULL AND vndr_ast_id IS NOT NULL AND base_amt IS NOT NULL AND dcml_cnt IS NOT NULL)
  ) NOT VALID;

-- 최소 단위는 선행 0 없는 정수여야 같은 금액이 한 표기로 고정된다(계약13).
-- 폭 320은 공개 금액의 정수부 18자리 + 정밀도 상한 255자리에 여유를 둔 값이다 — 유효한 자산·금액이 INSERT 에서 막히면 안 된다.
ALTER TABLE bcm_sbmt_l
  ADD CONSTRAINT ck_bcm_sbmt_base_amt CHECK (base_amt IS NULL OR base_amt ~ '^(0|[1-9][0-9]*)$') NOT VALID;

ALTER TABLE bcm_sbmt_l
  ADD CONSTRAINT ck_bcm_sbmt_dcml CHECK (dcml_cnt IS NULL OR dcml_cnt BETWEEN 0 AND 255) NOT VALID;

ALTER TABLE bcm_sbmt_l VALIDATE CONSTRAINT ck_bcm_sbmt_vndr_canonical;
ALTER TABLE bcm_sbmt_l VALIDATE CONSTRAINT ck_bcm_sbmt_base_amt;
ALTER TABLE bcm_sbmt_l VALIDATE CONSTRAINT ck_bcm_sbmt_dcml;
