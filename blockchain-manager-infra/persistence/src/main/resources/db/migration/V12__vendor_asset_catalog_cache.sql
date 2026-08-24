CREATE TABLE bcm_vndr_ast_ctlg_m (
  vndr_ast_id       VARCHAR(64)  PRIMARY KEY,
  vndr_blkc_id      VARCHAR(64)  NOT NULL,
  ast_smbl          VARCHAR(64)  NOT NULL,
  dspl_nm           VARCHAR(128) NULL,
  ast_clss          VARCHAR(16)  NULL,
  dcml_cnt          INTEGER      NULL,
  cntr_addr         VARCHAR(128) NULL,
  prst_yn           VARCHAR(1)   NOT NULL,
  sync_dttm         VARCHAR(16)  NOT NULL,
  frst_reg_empno    VARCHAR(6)   NOT NULL,
  frst_reg_brcd     VARCHAR(4)   NOT NULL,
  last_chng_empno   VARCHAR(6)   NOT NULL,
  last_chng_brcd    VARCHAR(4)   NOT NULL,
  FOREIGN KEY (vndr_blkc_id) REFERENCES bcm_blkc_m (vndr_blkc_id)
);

CREATE INDEX idx_bcm_vndr_ast_ctlg_blkc
  ON bcm_vndr_ast_ctlg_m (vndr_blkc_id, prst_yn, ast_smbl);

CREATE INDEX idx_bcm_vndr_ast_ctlg_symbol
  ON bcm_vndr_ast_ctlg_m (lower(ast_smbl) text_pattern_ops)
  WHERE prst_yn = 'Y';

CREATE INDEX idx_bcm_vndr_ast_ctlg_name
  ON bcm_vndr_ast_ctlg_m (lower(dspl_nm) text_pattern_ops)
  WHERE prst_yn = 'Y';

CREATE INDEX idx_bcm_vndr_ast_ctlg_address
  ON bcm_vndr_ast_ctlg_m (lower(cntr_addr) text_pattern_ops)
  WHERE prst_yn = 'Y';

CREATE INDEX idx_bcm_vndr_ast_ctlg_search
  ON bcm_vndr_ast_ctlg_m USING GIN (
    to_tsvector('simple', coalesce(ast_smbl, '') || ' ' || coalesce(dspl_nm, ''))
  ) WHERE prst_yn = 'Y';
