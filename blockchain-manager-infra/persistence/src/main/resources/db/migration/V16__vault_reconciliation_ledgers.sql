-- Fireblocks workspace 전체 vault 대사를 HTTP 요청 수명과 분리하고 결과를 bounded page로 조회한다.
CREATE TABLE bcm_vlt_rcnc_l (
  vlt_rcnc_id      VARCHAR(36)  PRIMARY KEY,
  qry_vl           VARCHAR(128) NULL,
  vlt_rcnc_stcd    VARCHAR(16)  NOT NULL,
  vndr_crsr        TEXT         NULL,
  vndr_done_yn     VARCHAR(1)   NOT NULL,
  wrkr_clm_id      VARCHAR(36)  NULL,
  clm_expires_dttm VARCHAR(16)  NULL,
  vndr_page_cnt    INT          NOT NULL,
  vndr_vlt_cnt     BIGINT       NOT NULL,
  rslt_cnt         BIGINT       NOT NULL,
  fail_cd          VARCHAR(64)  NULL,
  strt_dttm        VARCHAR(16)  NULL,
  fnsh_dttm        VARCHAR(16)  NULL,
  reg_dttm         VARCHAR(16)  NOT NULL,
  last_chng_dttm   VARCHAR(16)  NOT NULL,
  frst_reg_empno   VARCHAR(6)   NOT NULL,
  frst_reg_brcd    VARCHAR(4)   NOT NULL,
  last_chng_empno  VARCHAR(6)   NOT NULL,
  last_chng_brcd   VARCHAR(4)   NOT NULL,
  CHECK (vlt_rcnc_stcd IN ('ACCEPTED', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED')),
  CHECK (vndr_done_yn IN ('Y', 'N')),
  CHECK ((wrkr_clm_id IS NULL) = (clm_expires_dttm IS NULL)),
  CHECK (vndr_page_cnt >= 0 AND vndr_vlt_cnt >= 0 AND rslt_cnt >= 0)
);
CREATE UNIQUE INDEX ux_bcm_vlt_rcnc_active ON bcm_vlt_rcnc_l ((1))
  WHERE vlt_rcnc_stcd IN ('ACCEPTED', 'RUNNING');

CREATE TABLE bcm_vlt_rcnc_item_l (
  vlt_rcnc_id      VARCHAR(36)  NOT NULL,
  item_key         VARCHAR(129) NOT NULL,
  item_seq         BIGINT       NULL,
  rcnc_stcd        VARCHAR(32)  NOT NULL,
  acnt_id          VARCHAR(64)  NULL,
  acnt_typ_dvcd    VARCHAR(2)   NULL,
  ref              VARCHAR(64)  NULL,
  vndr_vlt_id      VARCHAR(64)  NOT NULL,
  vndr_vlt_nm      TEXT         NULL,
  wllt_cnt         INT          NULL,
  acnt_reg_dttm    VARCHAR(16)  NULL,
  reg_dttm         VARCHAR(16)  NOT NULL,
  last_chng_dttm   VARCHAR(16)  NOT NULL,
  frst_reg_empno   VARCHAR(6)   NOT NULL,
  frst_reg_brcd    VARCHAR(4)   NOT NULL,
  last_chng_empno  VARCHAR(6)   NOT NULL,
  last_chng_brcd   VARCHAR(4)   NOT NULL,
  PRIMARY KEY (vlt_rcnc_id, item_key),
  FOREIGN KEY (vlt_rcnc_id) REFERENCES bcm_vlt_rcnc_l (vlt_rcnc_id),
  CHECK (rcnc_stcd IN ('PENDING', 'MANAGED', 'UNMANAGED', 'MISSING_IN_FIREBLOCKS')),
  CHECK (item_seq IS NULL OR item_seq > 0),
  CHECK (wllt_cnt IS NULL OR wllt_cnt >= 0)
);
CREATE UNIQUE INDEX ux_bcm_vlt_rcnc_item_seq ON bcm_vlt_rcnc_item_l (vlt_rcnc_id, item_seq)
  WHERE item_seq IS NOT NULL;
CREATE INDEX idx_bcm_vlt_rcnc_item_vault ON bcm_vlt_rcnc_item_l (vlt_rcnc_id, vndr_vlt_id);
