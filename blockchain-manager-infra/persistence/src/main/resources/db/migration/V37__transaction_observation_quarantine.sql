-- docs/design/03-bcm-db.md: V37 관찰 격리 원장.
-- V32 는 "격리 대상은 거래가 아니라 그 관찰 하나"라고 정했다. 웹훅 경로는 인박스 행을 F 로 돌려 그 자리를 삼지만,
-- BAT(막힘 점검·tx 대사)에는 인박스 행이 없다. 그래서 관찰 하나를 담는 원장을 따로 둔다.
--
-- 거래 상태(bcm_tx_l)와 고객 outbox 는 **건드리지 않는다** — 마지막으로 일관됐던 행을 그대로 둔다.
CREATE TABLE bcm_tx_qrnt_l (
  qrnt_id    VARCHAR(64) PRIMARY KEY CHECK (qrnt_id <> '' AND qrnt_id !~ '^\s|\s$'),
  -- 논리 root 와 실제로 관찰된 물리 거래. RBF 계열이면 둘이 다르다.
  orig_tx_id VARCHAR(64) NOT NULL,
  obsv_tx_id VARCHAR(64) NOT NULL,
  ntwk_cd    VARCHAR(20) NOT NULL,
  -- 어느 작업이 찾았나. 같은 handler 를 두 작업이 쓰므로 유입 경로를 남긴다.
  src_dvcd   VARCHAR(16) NOT NULL CHECK (src_dvcd IN ('STALL', 'RECONCILIATION')),
  qrnt_stcd  VARCHAR(16) NOT NULL CHECK (qrnt_stcd IN ('OPEN', 'RESOLVED', 'REPLAYED')),
  -- 무엇이 어긋났나. 값은 **보호 대상**이라 경보·로그에는 항목 이름만 나간다.
  cnfl_fld   VARCHAR(32) NOT NULL CHECK (cnfl_fld <> ''),
  rcrd_vl    TEXT        NOT NULL,
  obsv_vl    TEXT        NOT NULL,
  -- 판정에 쓴 응답 원문. 목록 조회면 페이지 전체이고 evdc_idx 가 이 거래의 위치다(03 V37).
  evdc_lkup  VARCHAR(32) NOT NULL
    CHECK (evdc_lkup IN ('BY_TRANSACTION_ID', 'BY_EXTERNAL_TRANSACTION_ID', 'PAGE')),
  evdc_idx   INT          NULL CHECK (evdc_idx IS NULL OR evdc_idx >= 0),
  evdc_ctyp  VARCHAR(128) NULL,
  body       BYTEA        NOT NULL,
  body_len   INT          NOT NULL CHECK (body_len = octet_length(body)),
  body_hash  VARCHAR(64)  NOT NULL CHECK (body_hash = encode(sha256(body), 'hex')),
  -- 같은 관찰의 반복 발견을 한 행으로 합친다 — 재조회할 때마다 행이 늘면 운영이 같은 사실을 여러 번 본다.
  obsv_fngr  VARCHAR(64) NOT NULL CHECK (obsv_fngr ~ '^[0-9a-f]{64}$'),
  dtct_cnt   INT         NOT NULL DEFAULT 1 CHECK (dtct_cnt >= 1),
  frst_dtct_dttm  VARCHAR(16) NOT NULL CHECK (frst_dtct_dttm ~ '^[0-9]{14}$'),
  last_chng_dttm  VARCHAR(16) NOT NULL CHECK (last_chng_dttm ~ '^[0-9]{14}$'),
  frst_reg_empno  VARCHAR(6) NOT NULL,
  frst_reg_brcd   VARCHAR(4) NOT NULL,
  last_chng_empno VARCHAR(6) NOT NULL,
  last_chng_brcd  VARCHAR(4) NOT NULL
);

-- 열린 격리는 관찰당 하나다. 해소된 뒤 같은 관찰이 다시 오면 새 행으로 남는다 — 그건 새 사실이다.
CREATE UNIQUE INDEX uk_bcm_tx_qrnt_open ON bcm_tx_qrnt_l(obsv_fngr) WHERE qrnt_stcd = 'OPEN';
-- 운영 조회: root 별 열린 격리, 그리고 발견 순서.
CREATE INDEX ix_bcm_tx_qrnt_root ON bcm_tx_qrnt_l(orig_tx_id, frst_dtct_dttm);

-- 증적은 불변이다. 상태 전이(OPEN → RESOLVED/REPLAYED)만 허용하고 원문·양쪽 값·식별은 바꾸지 못한다.
CREATE OR REPLACE FUNCTION bcm_tx_qrnt_reject_evidence_mutation() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
  IF TG_OP = 'DELETE' THEN
    RAISE EXCEPTION 'bcm_tx_qrnt_l is append-only';
  END IF;
  IF NEW.body IS DISTINCT FROM OLD.body
     OR NEW.body_hash IS DISTINCT FROM OLD.body_hash
     OR NEW.body_len IS DISTINCT FROM OLD.body_len
     OR NEW.obsv_fngr IS DISTINCT FROM OLD.obsv_fngr
     OR NEW.cnfl_fld IS DISTINCT FROM OLD.cnfl_fld
     OR NEW.rcrd_vl IS DISTINCT FROM OLD.rcrd_vl
     OR NEW.obsv_vl IS DISTINCT FROM OLD.obsv_vl
     OR NEW.orig_tx_id IS DISTINCT FROM OLD.orig_tx_id
     OR NEW.obsv_tx_id IS DISTINCT FROM OLD.obsv_tx_id
     OR NEW.frst_dtct_dttm IS DISTINCT FROM OLD.frst_dtct_dttm THEN
    RAISE EXCEPTION 'bcm_tx_qrnt_l evidence is immutable';
  END IF;
  RETURN NEW;
END;
$$;

CREATE TRIGGER trg_bcm_tx_qrnt_evidence_immutable
  BEFORE UPDATE OR DELETE ON bcm_tx_qrnt_l
  FOR EACH ROW EXECUTE FUNCTION bcm_tx_qrnt_reject_evidence_mutation();

REVOKE ALL ON bcm_tx_qrnt_l FROM PUBLIC;
-- 앱 역할: INSERT·상태 UPDATE 와 body 를 제외한 컬럼 SELECT 만 부여한다. body·rcrd_vl·obsv_vl 열람은 감사 역할에만
-- 허용한다. 역할 이름은 배포별 DBA 절차에서 지정한다(V24 증적 보관과 같은 규율).
