# docs/design — 설계 정본

이 폴더는 blockchain-manager의 설계 정본이다. 설계 수정·구현·리뷰는 이 저장소에서 수행한다.
2026-09-08 사용자 결정으로 기존 외부 wiki 사본 관리에서 전환했다. 전환 기준은 이 저장소에 커밋된 문서이며,
별도 저장소의 clone·경로·동기화·byte 비교를 요구하지 않는다.

## 변경 규칙

- 사용자 요청·확정 결정에 따라 해당 설계 문서를 직접 수정한다. 미확정 계약을 추측해 추가하지 않는다.
- 이벤트·상태·DB·정책 변경은 영향 문서를 먼저 대조하고 코드·테스트·HTTP API 계약을 함께 검토한다.
- HTTP API 정본은 [openapi.yaml](../api/openapi.yaml)이다. 변경하면 `python3 docs/api/build.py`로 생성물을 갱신한다.
- `design-sync`는 이 저장소의 설계와 코드·테스트·OpenAPI의 정합을 점검한다. 독립 리뷰 순서는
  [converge-review.md](../ai/converge-review.md)를 따른다.
- 내부 링크는 이 저장소에서 열리는 상대 경로를 사용한다. 외부 참고자료는 출처임을 구분하고,
  구현에 필요한 확정 계약은 본문에 기록한다. 출처의 원문을 확인하지 못한 내용은 추측으로 보완하지 않는다.
- 과거 계획의 사본 동기화·외부 commit 기록은 당시 이력이며 현재 작업 지시가 아니다.

## 문서 인덱스

| 문서 | 범위 |
|---|---|
| [01-infra.md](01-infra.md) | 구성 요소·큐·보안 경계 |
| [02-bcm-flow.md](02-bcm-flow.md) | 계정·이벤트·웹훅·출금·대사 흐름 |
| [03-bcm-db.md](03-bcm-db.md) | BCM 스키마·물리 제약 |
| [04-compliance-flow.md](04-compliance-flow.md) · [05-compliance-db.md](05-compliance-db.md) | 외부 컴플라이언스 서비스의 연동 맥락 |
| [06-sweep.md](06-sweep.md) | Sweep·밴드S 정책과 실행 계약 |
| [07-asset-master.md](07-asset-master.md) | 네트워크·자산·벤더 매핑 |
| [08-bcm-admin.md](08-bcm-admin.md) | Admin 소유권·정책·승인·UI 경계 |
| [09-asset-map.md](09-asset-map.md) | 시나리오별 자산 이동 |
| [10-local-fireblocks-integration.md](10-local-fireblocks-integration.md) | Stub·Anvil 통합환경 |
| [11-operational-log-policy.md](11-operational-log-policy.md) | 운영 로그·수집·보존 |
| [12-cosigner-ha.md](12-cosigner-ha.md) | Co-signer HA 구성 |
| [90-fireblocks-qna.md](90-fireblocks-qna.md) | 벤더 확답·공식 자료 확인 기록 |
| [93-batch-partial-fail-sample.md](93-batch-partial-fail-sample.md) · [94-batch-payload-sample.md](94-batch-payload-sample.md) | 배치 실측 payload |
| [95-approve-pull-poc-result.md](95-approve-pull-poc-result.md) | approve + transferFrom PoC |
| [96-payload-sample.md](96-payload-sample.md) · [97-webhook-poc-result.md](97-webhook-poc-result.md) | 웹훅 실물·실측 동작 |
| [98-batch-sweep.md](98-batch-sweep.md) · [99-detection-detail.md](99-detection-detail.md) | 배치 채택 근거·감지 상세 |
