---
name: db-migration
description: bcm_ 테이블 Flyway 마이그레이션 작성·변경 절차. 새 테이블 추가, 컬럼 추가/변경, 스키마 마이그레이션 작업 시 사용 — 03-bcm-db 대조부터 검증 테스트까지의 체크리스트.
---

# db-migration — bcm_ 스키마 마이그레이션 절차

> T1.3(V1, 2026-08-05)에서 채록. 정본은 docs/design/03-bcm-db.md와 07-asset-master.md — **스키마를 지어내지 않는다.**

## 절차

1. **설계 대조가 먼저다** — docs/design/03-bcm-db.md 또는 07-asset-master.md의 해당 테이블 절을 읽고 컬럼명·타입·제약·인덱스를
   **그대로** 옮긴다. 설계에 없는 변경이 필요하면 중단 — waas-wiki 개정(사용자 승인) → 사본 동기화 후 진행.
2. **파일 위치·네이밍** — `blockchain-manager-infra/persistence/src/main/resources/db/migration/V{n}__{설명}.sql`.
   n 은 순차, 설명은 스네이크 영문. 배포·공유·보존 DB에 적용된 마이그레이션은 절대 수정하지 않는다(새 V{n+1} 로).
   영속 DB 없이 Testcontainers로만 검증하는 배포 전 V1은, 확정 설계와 맞추라는 명시 결정이 있을 때만 직접 정합시킨다.
3. **코어 규약 타입** (CLAUDE.md 3절):
   - 일시 `VARCHAR(16)` (`_dttm`) · 일자 `VARCHAR(8)` (`_dt`) — TIMESTAMP 금지
   - 불리언 `_yn VARCHAR(1)` · 금액 `NUMERIC` · payload `JSONB` (단, 원문 바이트 보존은 `TEXT` — raw_tx_l)
   - 벤더 id `VARCHAR(64)` · ext_tx_id `VARCHAR(128)` · 자산 `tkn_smbl VARCHAR(16)`
   - 컬럼 축약: `_stcd`(상태) `_dvcd`(구분) `_cnt` `_id` — 03 명명 규약 절이 정본
4. **감사 4컬럼** — 모든 테이블 끝에 `frst_reg_empno(6)·frst_reg_brcd(4)·last_chng_empno(6)·last_chng_brcd(4)`
   NOT NULL. 자동 처리 행 센티넬: `empno='SYSTEM'` · `brcd='9999'` (구현은 단일 상수).
5. **멱등은 물리 제약으로** — 앱 로직이 아니라 UNIQUE/PK 가 최종 방어다
   (`(acnt_typ_dvcd, ref)` UNIQUE · `(acnt_id, ntwk_cd, tkn_smbl)` PK · `vndr_ast_id` UNIQUE · ext_tx_id UNIQUE · noti_id PK · evnt_id PK).
6. **검증 테스트** — 통합 테스트(Testcontainers)에 스키마 검증을 추가/갱신한다:
   - 테이블 존재 (information_schema.tables)
   - 유니크/PK 제약 존재 (table_constraints + key_column_usage)
   - 새/변경 컬럼 존재 (information_schema.columns)
   실행: `./gradlew :blockchain-manager-app:bcm-api:test`
7. **파티션 테이블 주의** — `bcm_raw_tx_l` 은 PARTITION BY RANGE(base_dt) 부모만 생성 —
   파티션 생성은 보관 배치/운영 몫(Phase 8). PK 에 파티션 키 포함 필수.

## 하지 않는 것

- 설계에 없는 컬럼·타입·제약 발명 (추측 금지 — CLAUDE.md 0절)
- 적용된 V{n} 파일 수정 · 코어 규약 타입 변경 (일시를 TIMESTAMP 로 등)
- 마이그레이션과 무관한 변경을 같은 커밋에 섞기
