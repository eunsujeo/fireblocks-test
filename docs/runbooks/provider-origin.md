# 제공자 원천 등록과 기동 오류

API·Webhook·BAT는 DB 데이터셋의 원천과 실행 설정이 일치해야 시작한다.
[원천 DB 계약](../design/03-bcm-db.md#제공자-원천-binding--후속-물리-계약) · [조립 계약](../design/12-provider-compatibility.md)

## 실행 설정

세 앱에 같은 값을 전달한다. 접속 URL이나 API key를 원천 ID로 사용하지 않는다.

| 환경변수 | 값 |
|---|---|
| `BCM_PROVIDER` | `fireblocks` 또는 `local`. Dfns는 구현 전이므로 계속 기동 차단 |
| `BCM_CHAIN_MODE` | Fireblocks는 `TESTNET`/`MAINNET`, local은 `LOCAL` |
| `BCM_ORIGIN_ID` | 등록된 데이터셋의 불변 원천 ID |
| `BCM_ORIGIN_PLATFORM_INSTANCE_ID` | 플랫폼 설치 식별자 |
| `BCM_ORIGIN_VENDOR_ORGANIZATION_ID` | 검증된 workspace/organization 식별자 |

`BCM_ORIGIN_*`는 1~64자이며 빈 값·앞뒤 공백을 허용하지 않는다. 프로토콜은 선택 구현에서 결정한다.
잘못된 설정을 DB에 맞춰 임의 수정하지 말고 실제 설치·조직·자격과 등록 증거를 확인한다.

## 기존 데이터셋 도입 순서

1. API/Webhook/BAT와 모든 writer를 중지하고 백업한다. 계정·주소·자산/회사 vault 설정·미완료 생성/제출·스윕·인박스·보관 파티션·outbox·대사 cursor의 실제 원천을 확인한다.
2. 실제 원천이 혼합됐거나 확인되지 않으면 등록하지 않는다. 계정·거래 ID와 진행 상태, 원문·서명·해시, 이벤트 ID와 cursor를 그대로 보존한다.
3. DBA가 manifest 순서에 따라 **V21__provider_origin_binding.sql**을 적용한다. 앱은 DDL을 실행하지 않으며 SQL은 원천 행을 자동 삽입하지 않는다.
4. 확인된 원천을 DBA 역할로 한 번 등록한다. SQL에 필요한 값은 실제 등록 증거와 대조한다. 아래는 `psql` 변수 기반 양식이며 자동 실행 도구가 아니다.

```sql
INSERT INTO bcm_prvd_bndg_m (
    bndg_no, orgn_id, exec_mode, prtc_prvd, pltfrm_inst_id, vndr_org_id, chain_mode, bndg_dttm,
    frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd
) VALUES (
    1, :'origin_id', :'execution_mode', :'protocol_provider', :'platform_instance_id', :'vendor_organization_id', :'chain_mode',
    to_char(CURRENT_TIMESTAMP AT TIME ZONE 'UTC', 'YYYYMMDDHH24MISS'),
    :'employee_no', :'branch_code', :'employee_no', :'branch_code'
);
```

5. 앱 역할에 binding 조회만 허용한다. 앱은 소유자·superuser 또는 소유자 역할의 구성원이 아니어야 한다.
   다른 역할에서 상속한 쓰기 권한과 컬럼별 권한도 회수한다. 일괄 테이블 GRANT 후 이 테이블의 권한을 다시 확인한다.

```sql
REVOKE ALL ON bcm_prvd_bndg_m FROM :"app_role";
GRANT SELECT ON bcm_prvd_bndg_m TO :"app_role";
SELECT has_table_privilege(:'app_role', 'bcm_prvd_bndg_m', 'SELECT') AS can_read,
       has_table_privilege(:'app_role', 'bcm_prvd_bndg_m', 'INSERT,UPDATE,DELETE,TRUNCATE') AS can_write;
```

`can_read=true`, `can_write=false`를 확인하고 앱 역할의 INSERT/UPDATE/DELETE/TRUNCATE 거절을 검증한다.
마이그레이션의 PUBLIC 권한 회수만으로 배포별 기존 역할의 권한까지 회수되지는 않는다. 기동 guard는 권한 부여를 대신하지 않는다.

6. 기대 원천 설정과 DB 행을 대조한 뒤 세 앱을 시작한다. 다른 원천의 기존 DB·진행 거래를 재사용하지 않는다.
   등록 행 삭제나 원천 덮어쓰기로 오류를 해결하지 않는다. 이전 버전은 guard가 없을 수 있으므로 자동 롤백 대상으로 취급하지 않는다.

## 기동 오류

V22는 네트워크 지갑 생성 의도·조회 증적·완료 연결용 테이블 네 개를 추가한다. V21 뒤에 manifest 순서로 적용하며,
기존 계정/주소·원천 binding을 변경하거나 자동 등록하지 않는다. 실제 적용은 DBA가 수행한다.
현재 이 원장을 호출하는 공개 API/벤더 어댑터는 없으며, Dfns 기동 차단 해제나 지갑 이전을 뜻하지 않는다.
향후 writer 연결 시 앱에는 원장 업무에 필요한 SELECT/INSERT/UPDATE만 부여하고 binding은 계속 SELECT 전용으로 유지한다.
구버전으로 롤백해도 신규 의도/관찰을 삭제하지 않는다.

V23은 기존 계정에 `acnt_mdl=VAULT`를 추가하고 vault ID의 NOT NULL을 모델별 CHECK로 대체한다.
기존 계정 ID·vault ID·유형/ref·시각과 생성 의도는 보존한다. 기존 계정이 있는 Dfns binding 데이터셋은 적용을 거절한다.
`LOGICAL`은 vault ID가 NULL이며 Dfns binding에서만 저장할 수 있고, 계정 모델 변경은 trigger가 거절한다.
V23 이후의 신규 논리 계정은 구버전 Account 모델로 읽을 수 없다. LOGICAL 데이터가 생긴 데이터셋은 구버전 바이너리로
롤백하지 않으며 검증된 호환 버전 또는 적용 전 백업 복구 절차를 사용한다. 논리 계정을 가짜 vault ID로 바꾸지 않는다.
현재 논리 계정/네트워크 지갑 서비스는 내부 조립용이고 실제 보호 원문 저장소·공개 API·Dfns 어댑터는 미연결이다.

V24는 네트워크 지갑 생성/조회 응답 원문 증적 테이블 `bcm_ntwk_wlt_evdc_l`을 추가한다. V23 뒤에 manifest 순서로 적용하며 기존 원장은 변경하지 않는다.
마이그레이션은 PUBLIC 권한을 회수하고 append-only trigger를 건다. 앱 역할에는 INSERT와 원문(`body`)을 제외한 컬럼 SELECT만 부여한다.
원문 열람은 감사 역할에만 허용하고 앱 역할의 `body` SELECT·UPDATE·DELETE 거절을 확인한다. 아래는 `psql` 변수 양식이다.

```sql
REVOKE ALL ON bcm_ntwk_wlt_evdc_l FROM :"app_role";
GRANT INSERT ON bcm_ntwk_wlt_evdc_l TO :"app_role";
GRANT SELECT (evdc_id, crtn_id, orgn_id, acnt_id, ntwk_cd, corr_id, req_hash, oprtn_dvcd, qry_crsr, vndr_wlt_id,
              body_len, body_hash, obs_dttm, frst_reg_empno, frst_reg_brcd, last_chng_empno, last_chng_brcd)
  ON bcm_ntwk_wlt_evdc_l TO :"app_role";
GRANT SELECT ON bcm_ntwk_wlt_evdc_l TO :"audit_role";
SELECT has_column_privilege(:'app_role', 'bcm_ntwk_wlt_evdc_l', 'body', 'SELECT') AS app_reads_body,
       has_table_privilege(:'app_role', 'bcm_ntwk_wlt_evdc_l', 'UPDATE,DELETE,TRUNCATE') AS app_mutates;
```

`app_reads_body=false`, `app_mutates=false`를 확인한다. 증적 행이 있어도 지갑 완료나 Dfns 수용을 뜻하지 않으며 Dfns 기동 차단은 유지된다.

| 오류 | 확인할 내용 |
|---|---|
| `Required provider origin configuration` | 세 앱의 환경변수 누락/빈 값 |
| `Provider origin binding is missing` | V21 이후 실제 원천 등록이 완료됐는지 |
| `Provider origin mismatch` | DB/schema·제공자·조직·설치·체인 환경·원천 ID의 오연결 |
| `bcm_prvd_bndg_m` 조회 실패 | V21 적용, search_path, 연결/조회 권한·DB 가용성 |

원인 예외를 보존하며 기본 제공자나 자동 등록으로 복구하지 않는다. 기대 설정과 DB 대조가 실제 벤더 조직의 인증을 대신하지는 않는다.

## 로컬 실행

새로운 Docker Stub 데이터셋은 별도 초기화 스크립트가 `local-stub/local/fireblocks/<Compose project>/local-stub/LOCAL` 원천을 등록한다.
`scripts/local.sh`가 같은 기대 설정을 전달한다. 이 경로는 **신규 Stub 전용 데이터셋 생성**에만 적용하며 기존 볼륨을 재표기하지 않는다.
기존 Stub 볼륨도 V21과 원천 확인/등록이 필요하다. 데이터를 보존해야 하면 볼륨을 삭제해 해결하지 않는다.
Fireblocks 데이터셋에는 자동 등록하지 않는다. `scripts/local.sh configure fireblocks`에서 원천 식별자를 입력하고 DBA 등록과 대조한다.
원격 파일 배포 환경도 BCM 앱용 원천 설정/DB 등록이 필요하다. Stub 자체의 환경변수만 바꿔 BCM 데이터셋을 이전하지 않는다.

운영 SQL 적용·원천 등록·역할 변경·배포는 이번 코드 검증에서 실행하지 않았다.
