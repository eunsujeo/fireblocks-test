# 원장 HTML 문서 생성

`docs/ledger/index.html`을 직접 열어 사용한다. 서버·ZIP·외부 리소스 요청은 필요하지 않다.
원문 PDF 열기는 `docs/원장_v0.1.4.pdf`를 사용한다. 다른 위치로 옮길 때는 `ledger/` 폴더와
해당 PDF의 형제 관계를 유지한다.

## 편집과 생성

| 파일 | 역할 |
|---|---|
| `content.py` | 업무 설명, 정의, 원문 이미지 설명 전사, 순번별 대상·참여 시스템, 추출 겹침 보정 |
| `source.json` | PDF에서 추출한 원문·좌표·페이지별 해시. `extract.py` 생성물 |
| `build.py` | 업무·정의·원문 화면과 인라인 검색 데이터, 페이지 대응표 생성 |
| `style.css`, `reader.js` | 화면 스타일, 모달·이력·검색·원문 확대·단계 강조 |
| `extract.py` | 원문 텍스트·좌표 추출, 64쪽을 무손실 WebP로 저장 |
| `verify.py`, `verify.cjs` | 정적 검증과 브라우저 기능·전체 화면 검사 |

```sh
python3 scripts/ledger/build.py
python3 scripts/ledger/verify.py
node scripts/ledger/verify.cjs
```

생성기는 BeautifulSoup을 사용한다. 브라우저 검증은 기존 Playwright 설치를 사용하며,
필요하면 `PLAYWRIGHT_MODULE`에 모듈 경로, `CHROME_BIN`에 시스템 Chrome 경로를 지정한다.
브라우저 결과와 화면 캡처는 `/tmp/ledger-verification/`에 저장한다.
다른 위치를 검증할 때는 `verify.py --root <ledger 폴더>`, `verify.cjs <ledger 폴더>`를 사용한다.

원문을 다시 추출할 때만 아래 명령을 실행한다. 설치된 Poppler와 Pillow가 필요하다.

```sh
python3 scripts/ledger/extract.py
```

PDF를 갱신하면 페이지 매핑·업무 설명·추출 보정·이미지 설명을 원문과 다시 대조해야 한다.
추출된 텍스트 순서를 시퀀스 순서로 사용하지 않는다. 숫자 형식 경고는 `source.json`에 보존한다.
원문 이미지는 화살표·점선·순번·이미지 주석을 보존하고, 업무 화면은 원문 순번으로 다시 정렬한다.
생성된 HTML과 `manifest.json`은 직접 편집하지 않는다.

## 검증 기준

- PDF 해시, 64쪽 원문 텍스트·이미지 해시, 전체 페이지의 읽기 화면 대응.
- HTML 94개, 내부 링크·앵커 1,885개, 원문 순번 291개.
- 입금 Confirmed·Finalized의 잔고 차이, 델타정산 전체 완료 조건, 원문 대사식 보존.
- 모달 내부 이동·뒤로 가기·스크롤/포커스 복구·새 창·iframe 내 Escape.
- 원문 순번 위치 표시·확대, 이미지 주석 검색, 전체 원문 목록, JavaScript 없는 링크 대체 동작.
- 데스크톱·모바일 188개 화면: 빈 본문·이미지 오류·가로 넘침·콘솔 오류 확인.
- 브라우저에서 HTTP/HTTPS 요청을 차단하고 발생한 외부 요청도 실패로 집계.

2026-09-11 기능 19개·화면 188개 통과. 한글·공백을 포함한 별도 경로에서도 검증했다.
렌더링 PNG와 저장된 WebP 64쪽은 픽셀이 동일했다. 이 검증은 문서의 보존·탐색에 대한 것이며
설계 타당성이나 BCM 구현 완료를 승인하는 검증은 아니다.

문서 리뷰 3회와 추가 리뷰의 수정 근거는 [제작 계획의 리뷰 기록](../../docs/ledger/PLAN.md#문서-리뷰-3회--2026-09-11)에 정리했다.
