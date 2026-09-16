-- docs/design/03-bcm-db.md: V26 논리 거래의 온체인 hash 조회 인덱스.
-- Dfns 입금에는 벤더 거래 ID가 없어 BCM이 결정적 ID를 만든다. 그 값으로는 벤더 콘솔·체인 탐색기를 검색할 수 없으므로
-- 운영 조사는 실제 hash로 한다. 같은 인덱스를 출금의 온체인 이동 사건을 기존 거래에 붙이는 대조에도 쓴다.
-- 컬럼·PK·제약을 바꾸지 않는 추가 전용 마이그레이션이다. hash는 유일하지 않을 수 있어(RBF 계열·재관찰) UNIQUE로 두지 않는다.
CREATE INDEX idx_bcm_tx_hash ON bcm_tx_l (tx_hash)
  WHERE tx_hash IS NOT NULL;
