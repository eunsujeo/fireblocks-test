-- bcm:transaction=off
-- docs/design/03-bcm-db.md: V30 제출 목적지 주소 보관 — 지갑 주소 조회 인덱스.
-- 내부이체의 수신측 In 사건은 "발신 주소가 우리 지갑인가"를 물어야 한다. 자산 발급 기록이 아니라 **소유권**으로 판정한다 —
-- Dfns 제출은 그 symbol 의 주소 발급을 요구하지 않으므로 발급 기록으로 물으면 우리 주소를 못 알아본다(계약13).
-- 현재 Dfns 범위는 EVM 이라 소문자 기준이다. 16진수 주소는 대소문자에 정보가 없고, 벤더가 사건과 지갑 응답에서
-- 다른 표기를 줘도 같은 주소로 찾아야 한다. base58 네트워크가 열리면 그때 다시 정한다.
--
-- 온라인 생성이라 트랜잭션을 쓸 수 없어 V30 과 파일을 나눴다.
-- 실패 후 재실행하면 invalid index 도 먼저 제거하고 다시 만든다 — IF NOT EXISTS 는 invalid index 를
-- "있음"으로 보고 건너뛰어, 마이그레이션은 성공했는데 쓰이지 않는 인덱스가 남는다(V26 과 같은 패턴).
DROP INDEX CONCURRENTLY IF EXISTS idx_bcm_ntwk_wlt_addr;
CREATE INDEX CONCURRENTLY idx_bcm_ntwk_wlt_addr
  ON bcm_ntwk_wlt_m (orgn_id, ntwk_cd, lower(wlt_addr));
