# docs/design — 설계 문서 사본 (스냅샷)

**정본은 waas-wiki** (`../waas-wiki/blockchain-manager/docs/BC/`). 이 폴더는 waas-wiki 가 없는 머신에서도
구현·리뷰·설계 대조가 되도록 계약 문서를 **byte-동일하게 복사**해 둔 것이다.

## 규칙

- **여기서 직접 수정하지 않는다.** 설계 변경은 waas-wiki 에서 하고, 바뀐 파일을 다시 복사해 온다.
- byte-동일을 유지하므로 waas-wiki 가 있는 머신에서는 `diff` 로 사본이 뒤처졌는지 즉시 확인할 수 있다
  (design-sync agent 가 이 검사를 포함한다).
- `90-fireblocks-qna.md` 만 예외적으로 이름을 바꿨다 — 원본은 `BC/Fireblocks QnA/01-qna.md`.

## 대응 표

| 사본 | waas-wiki 원본 |
|---|---|
| 01-infra.md ~ 99-detection-detail.md (`09-asset-map.md` 포함) | `blockchain-manager/docs/BC/설계/` 동일 파일명 |
| 90-fireblocks-qna.md | `blockchain-manager/docs/BC/Fireblocks QnA/01-qna.md` |

## 동기화 (waas-wiki 있는 머신에서)

```bash
cd ../waas-wiki/blockchain-manager/docs/BC
cp 설계/{01-infra,02-bcm-flow,03-bcm-db,06-sweep,07-asset-master,08-bcm-admin,09-asset-map,96-payload-sample,97-webhook-poc-result,98-batch-sweep,99-detection-detail}.md \
   ../../../../blockchain-manager-svc/docs/design/
cp "Fireblocks QnA/01-qna.md" ../../../../blockchain-manager-svc/docs/design/90-fireblocks-qna.md
```
