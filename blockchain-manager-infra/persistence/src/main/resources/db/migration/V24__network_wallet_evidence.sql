-- docs/design/03-bcm-db.md: V24 네트워크 지갑 응답 증적 보관. 실제 응답 바이트를 append-only로 보존하고 해시·길이를 DB가 계산해 강제한다.
-- 증적에 API key·인증 헤더·서명 secret을 넣지 않는다. 응답 본문만 보관한다.
CREATE TABLE bcm_ntwk_wlt_evdc_l (
  evdc_id VARCHAR(64) PRIMARY KEY CHECK (evdc_id <> '' AND evdc_id !~ '^\s|\s$'),
  crtn_id VARCHAR(64) NOT NULL,
  orgn_id VARCHAR(64) NOT NULL REFERENCES bcm_prvd_bndg_m(orgn_id),
  acnt_id VARCHAR(64) NOT NULL,
  ntwk_cd VARCHAR(20) NOT NULL,
  corr_id VARCHAR(64) NOT NULL CHECK (corr_id <> '' AND corr_id !~ '^\s|\s$'),
  req_hash VARCHAR(64) NOT NULL CHECK (req_hash ~ '^[0-9a-f]{64}$'),
  oprtn_dvcd VARCHAR(16) NOT NULL CHECK (oprtn_dvcd IN ('CREATE', 'READ', 'DISCOVER')),
  qry_crsr TEXT NULL CHECK (qry_crsr <> '' AND qry_crsr !~ '^\s|\s$'),
  vndr_wlt_id VARCHAR(64) NULL CHECK (vndr_wlt_id <> '' AND vndr_wlt_id !~ '^\s|\s$'),
  body BYTEA NOT NULL,
  body_len INT NOT NULL CHECK (body_len = octet_length(body)),
  body_hash VARCHAR(64) NOT NULL CHECK (body_hash = encode(sha256(body), 'hex')),
  obs_dttm VARCHAR(16) NOT NULL CHECK (obs_dttm ~ '^[0-9]{14}$'),
  frst_reg_empno VARCHAR(6) NOT NULL,
  frst_reg_brcd VARCHAR(4) NOT NULL,
  last_chng_empno VARCHAR(6) NOT NULL,
  last_chng_brcd VARCHAR(4) NOT NULL,
  FOREIGN KEY (crtn_id, orgn_id, acnt_id, ntwk_cd) REFERENCES bcm_ntwk_wlt_crtn_l(crtn_id, orgn_id, acnt_id, ntwk_cd)
);

CREATE INDEX ix_bcm_ntwk_wlt_evdc_intent ON bcm_ntwk_wlt_evdc_l(crtn_id, obs_dttm);

CREATE TRIGGER trg_bcm_ntwk_wlt_evdc_append_only
  BEFORE UPDATE OR DELETE ON bcm_ntwk_wlt_evdc_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();

REVOKE ALL ON bcm_ntwk_wlt_evdc_l FROM PUBLIC;
-- 앱 역할: INSERT와 body를 제외한 컬럼 SELECT만 부여한다. body 열람은 감사 역할에만 허용한다. 역할 이름은 배포별 DBA 절차에서 지정한다.
