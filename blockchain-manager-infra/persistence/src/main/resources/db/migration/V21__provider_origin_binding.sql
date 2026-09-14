-- 데이터셋의 원천은 DBA가 증거 확인 후 등록한다. 기존 행의 자동 원천 백필은 하지 않는다.
CREATE TABLE bcm_prvd_bndg_m (
    bndg_no          SMALLINT PRIMARY KEY CHECK (bndg_no = 1),
    orgn_id          VARCHAR(64) NOT NULL UNIQUE CHECK (orgn_id <> '' AND orgn_id !~ '^[[:space:]]|[[:space:]]$'),
    exec_mode        VARCHAR(16) NOT NULL,
    prtc_prvd        VARCHAR(16) NOT NULL,
    pltfrm_inst_id   VARCHAR(64) NOT NULL CHECK (pltfrm_inst_id <> '' AND pltfrm_inst_id !~ '^[[:space:]]|[[:space:]]$'),
    vndr_org_id      VARCHAR(64) NOT NULL CHECK (vndr_org_id <> '' AND vndr_org_id !~ '^[[:space:]]|[[:space:]]$'),
    chain_mode       VARCHAR(16) NOT NULL,
    bndg_dttm        VARCHAR(16) NOT NULL,
    frst_reg_empno   VARCHAR(6) NOT NULL,
    frst_reg_brcd    VARCHAR(4) NOT NULL,
    last_chng_empno  VARCHAR(6) NOT NULL,
    last_chng_brcd   VARCHAR(4) NOT NULL,
    CONSTRAINT ck_prvd_bndg_modes CHECK (
        (exec_mode = 'fireblocks' AND prtc_prvd = 'fireblocks' AND chain_mode IN ('TESTNET', 'MAINNET')) OR
        (exec_mode = 'dfns' AND prtc_prvd = 'dfns' AND chain_mode IN ('TESTNET', 'MAINNET')) OR
        (exec_mode = 'local' AND prtc_prvd = 'fireblocks' AND chain_mode = 'LOCAL')
    )
);
REVOKE ALL ON bcm_prvd_bndg_m FROM PUBLIC;
-- 실제 앱 역할에는 SELECT만 부여한다. 역할 이름은 배포별 DBA 절차에서 지정한다.
