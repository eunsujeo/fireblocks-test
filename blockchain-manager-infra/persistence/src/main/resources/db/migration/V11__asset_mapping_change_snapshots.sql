ALTER TABLE bcm_vndr_ast_m
  ADD COLUMN actv_yn VARCHAR(1) NOT NULL DEFAULT 'Y',
  ADD CONSTRAINT ck_bcm_vndr_ast_actv CHECK (actv_yn IN ('Y', 'N'));

ALTER TABLE bcm_vndr_ast_m
  DROP CONSTRAINT bcm_vndr_ast_m_vndr_ast_id_key;

CREATE UNIQUE INDEX uk_bcm_vndr_ast_active_vendor
  ON bcm_vndr_ast_m (vndr_ast_id)
  WHERE actv_yn = 'Y';

CREATE TABLE bcm_vndr_ast_chng_l (
  chng_id          VARCHAR(36)   PRIMARY KEY,
  ntwk_cd          VARCHAR(20)   NOT NULL,
  tkn_smbl         VARCHAR(16)   NOT NULL,
  actn_dvcd        VARCHAR(16)   NOT NULL,
  before_snps      JSONB         NULL,
  after_snps       JSONB         NULL,
  req_id           VARCHAR(64)   NOT NULL,
  chng_dttm        VARCHAR(16)   NOT NULL,
  frst_reg_empno   VARCHAR(6)    NOT NULL,
  frst_reg_brcd    VARCHAR(4)    NOT NULL,
  last_chng_empno  VARCHAR(6)    NOT NULL,
  last_chng_brcd   VARCHAR(4)    NOT NULL,
  CONSTRAINT ck_bcm_vndr_ast_chng_action
    CHECK (actn_dvcd IN ('REGISTER', 'DEACTIVATE', 'REACTIVATE', 'REPLACE')),
  FOREIGN KEY (ntwk_cd, tkn_smbl) REFERENCES bcm_vndr_ast_m (ntwk_cd, tkn_smbl)
);

CREATE TRIGGER trg_bcm_vndr_ast_chng_no_update
  BEFORE UPDATE ON bcm_vndr_ast_chng_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();

CREATE TRIGGER trg_bcm_vndr_ast_chng_no_delete
  BEFORE DELETE ON bcm_vndr_ast_chng_l
  FOR EACH ROW EXECUTE FUNCTION bcm_admin_reject_mutation();

CREATE OR REPLACE FUNCTION bcm_allowance_revocation_snapshot_current(execution_id VARCHAR) RETURNS boolean
LANGUAGE sql STABLE AS $$
  SELECT NOT EXISTS (
           SELECT 1
             FROM bcm_alwnc_rvok_item_l item
             LEFT JOIN bcm_acnt_m account ON account.acnt_id = item.acnt_id
             LEFT JOIN bcm_addr_m address
               ON address.acnt_id = item.acnt_id AND address.ntwk_cd = item.ntwk_cd AND address.tkn_smbl = item.tkn_smbl
             LEFT JOIN bcm_vndr_ast_m asset
               ON asset.ntwk_cd = item.ntwk_cd AND asset.tkn_smbl = item.tkn_smbl AND asset.actv_yn = 'Y'
            WHERE item.rvok_exec_id = execution_id
              AND (account.acnt_id IS NULL OR account.vndr_vlt_id <> item.src_vlt_id OR
                   address.acnt_id IS NULL OR lower(address.dpst_addr) <> lower(item.ownr_addr) OR
                   asset.ntwk_cd IS NULL OR asset.cntr_addr IS NULL OR
                   lower(asset.cntr_addr) <> lower(item.tkn_ctrt_addr))
         )
     AND NOT EXISTS (
           SELECT 1
             FROM bcm_swp_auth_m auth
             JOIN bcm_alwnc_rvok_exec_l execution ON execution.rvok_exec_id = execution_id
            WHERE auth.ntwk_cd = execution.ntwk_cd
              AND lower(auth.swp_ctrt_addr) = lower(execution.swp_ctrt_addr)
              AND auth.obs_alwnc > 0
              AND NOT EXISTS (
                SELECT 1
                  FROM bcm_alwnc_rvok_item_l item
                 WHERE item.rvok_exec_id = execution_id
                   AND item.acnt_id = auth.acnt_id
                   AND item.ntwk_cd = auth.ntwk_cd
                   AND item.tkn_smbl = auth.tkn_smbl
                   AND lower(item.swp_ctrt_addr) = lower(auth.swp_ctrt_addr)
              )
         );
$$;
