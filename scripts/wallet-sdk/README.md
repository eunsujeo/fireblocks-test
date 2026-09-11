# Wallet SDK 참고 문서 빌드

`docs/wallet-sdk/`에 보존한 공개 문서의 본문으로 참고 문서 201개를 생성한다.
가이드의 참고 링크·검색 결과는 새 문서를 모달로 열며, 새 창 열기도 같은 문서를 사용한다.
공개 원문을 갱신할 때는 아래 동기화 절차를 사용한다. 재작성 가이드는 `_guide/`에서 관리하며 `build_references.py`가 참고 링크와 모달을 다시 연결한다.

## 공개 원문 갱신

현재 기준은 2026-09-11 SDK 문서 v1.1.0이다. `sync_sources.py`는 현재 공개 페이지를 수집하고
Markdown·페이지 JSON·오프라인 HTML·검색·탐색·명세를 갱신한다. `/v0/` 스냅샷은 제외한다.
출처의 본문은 보존하고 이동한 읽기 링크는 새 로컬 경로로 연결한다. 비공개·과거 버전 링크는 사용 불가로 표시한다.

```sh
python3 scripts/wallet-sdk/sync_sources.py --cache /tmp/wallet-sdk-capture-YYYYMMDD
```

캐시는 한 번의 수집에만 사용한다. 새 버전을 확인할 때는 새 캐시 경로를 사용한다.
`docs/wallet-sdk/source-update.json`에서 변경 페이지·경로·도식과 원문 해시를 확인하고,
가이드·지원 현황·DAW 비교 문서에 실제 변경과 구현 제약을 반영한 뒤 다시 생성한다.
다이어그램만 갱신하려면 렌더 명령 뒤에 `/fund-flows/vault-transfer`처럼 변경 경로를 전달한다.
원문 API 스키마는 SDK 문서의 공개 파일이며 BCM의 `docs/api` 계약과 별개다.

## 수정과 재생성

- `references.css`: 모달과 참고 문서 스타일
- `reference-controller.js`: 가이드의 모달·이력·새 창 제어
- `reference-page.js`: 참고 문서 내부 이동·도식 크기 조정
- `build_references.py`: 본문 변환, 링크 치환, HTML 내 스크립트 삽입, 공유 ZIP 생성

Python 환경에 BeautifulSoup이 필요하다. `docs/api` 생성기와는 별개다.

```sh
python3 scripts/wallet-sdk/build_references.py
```

SVG 30개는 `_reference/diagrams/`에 보관한다. 원문 도식이 변경되었을 때만
`render_diagrams.cjs`를 실행한 뒤 다시 빌드한다. API 스키마·코드 예시는 보존하며
원문 사이트의 호출 폼과 내비게이션은 새 문서에 포함하지 않는다.

## 검증

```sh
python3 scripts/wallet-sdk/verify_current.py
python3 scripts/wallet-sdk/verify_references.py
node scripts/wallet-sdk/verify_references.cjs
```

`verify_current.py`는 원문 해시·코드 예시·본문과 변경된 상태·도식의 일치를 확인한다.
`verify_references.py`는 최종 ZIP을 새 폴더에 풀어 링크와 파일 해시를 검사하고,
`/tmp/wallet-reference-test.json`에 브라우저 검증 경로를 기록한다.
`verify_references.cjs`는 참고 링크·중첩 이동·뒤로 가기·새 창·검색·모바일과 참고 문서 201개를 검사한다.
브라우저 검사에서는 HTTP/HTTPS 요청을 차단한다.

브라우저 도구에는 Playwright가 필요하다. 기존 설치를 쓸 때는 `PLAYWRIGHT_MODULE`에
모듈 경로를 지정할 수 있다. 시스템 Chrome을 쓸 경우 `CHROME_BIN`에 실행 파일 경로를 지정한다.
JS 소스는 `scripts/`에서 관리하며, 배포용 HTML·ZIP에는 인라인으로 들어간다.
