# Wallet SDK 참고 문서 빌드

`docs/wallet-sdk/`에 보존한 공개 문서의 본문으로 참고 문서 198개를 생성한다.
가이드의 참고 링크·검색 결과는 새 문서를 모달로 열며, 새 창 열기도 같은 문서를 사용한다.
기존 공개 문서 파일은 수정하지 않는다.

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
python3 scripts/wallet-sdk/verify_references.py
node scripts/wallet-sdk/verify_references.cjs
```

첫 명령은 최종 ZIP을 새 폴더에 풀어 링크와 파일 해시를 검사하고,
`/tmp/wallet-reference-test.json`에 브라우저 검증 경로를 기록한다.
두 번째 명령은 참고 링크·중첩 이동·뒤로 가기·새 창·검색·모바일과 참고 문서 198개를 검사한다.
브라우저 검사에서는 HTTP/HTTPS 요청을 차단한다.

브라우저 도구에는 Playwright가 필요하다. 기존 설치를 쓸 때는 `PLAYWRIGHT_MODULE`에
모듈 경로를 지정할 수 있다. 시스템 Chrome을 쓸 경우 `CHROME_BIN`에 실행 파일 경로를 지정한다.
JS 소스는 `scripts/`에서 관리하며, 배포용 HTML·ZIP에는 인라인으로 들어간다.
