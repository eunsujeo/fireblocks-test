# Blockchain Manager API 문서

백엔드 개발자와 **API 스펙을 주고받는** 문서. 우리가 설계한 블록체인 매니저 내용만 담고, 부가 기능·브랜딩은 두지 않는다. **API 문서 라이브러리(Scalar·Redoc 등)를 쓰지 않고** 직접 만든 뷰어(다크/라이트 테마)를 쓴다.

## 파일

| 파일 | 역할 |
|---|---|
| `openapi.yaml` | **수정은 여기서** — OpenAPI 3.1 스펙. 엔드포인트·스키마·에러 + 공통 규약·이벤트 계약(info.description)까지 이 한 파일에 다 있다. |
| `spec.js` | `openapi.yaml` 을 JSON 으로 감싼 것(`window.OPENAPI`). 뷰어가 라이브러리 없이 읽는다. **생성물** — 직접 고치지 말 것. |
| `index.html` | 직접 만든 개발자 포털(HTML/CSS/JS, 라이브러리 0). 카테고리별 API·타입·실행 패널, 다크/라이트 토글. |
| `try-it.js` | Base URL 추론·named example 선택·안전한 path/query 조립 helper. Node 계약 테스트 대상. |
| `api.md` | **마크다운 export** — GitHub 등 어디서나 열리는 단일 문서. **생성물**. |
| `api.html` | **단일 HTML export** — spec.js 를 인라인해 파일 하나로 뷰어 그대로 열린다. **생성물**. |
| `build.py` | `openapi.yaml` → `spec.js` + `api.md` + `api.html` 재생성. |
| `redocly.yaml` | (선택) 외부 lint 도구 설정. |

## 목적

1. **스펙 커뮤니케이션** — 백엔드 개발자와 API 스펙을 맞추는 용도. 스펙에 있는 내용과 실제 요청 실행만 제공한다.
2. **스펙 준수** — 뷰어가 `openapi.yaml`(→`spec.js`)에서 직접 그리므로 스펙과 문서가 항상 일치.
3. **yaml 추출 / 삽입** — `openapi.yaml` 은 외부 `$ref` 없는 단일 표준 파일이라 어디든 그대로 드롭인된다(Redoc·Swagger UI·Scalar·Postman·codegen). 문서 좌상단 **OpenAPI ↓**로 받는다.
4. **HTML export** — `api.html` 은 spec.js 를 인라인한 단일 파일이라, 메신저·메일로 파일 하나만 보내도 받는 쪽에서 더블클릭으로 뷰어 그대로 열린다. 문서 좌상단 **HTML ↓** 로 받는다.
5. **실행 가능한 예시** — named OpenAPI example을 우선 채우고 Base URL·path/query·JSON body를 수정해 요청한다. 응답을 받으면 HTTP 상태·노출된 헤더·본문 원문을 그대로 표시한다. 브라우저가 응답을 받지 못하면 서버 처리 여부를 단정하지 않고 오류 원문과 재현용 curl을 표시한다.

뷰어는 `계정·주소`, `잔액`, `거래`, `관리자`, `데이터 타입`을 각각 별도 hash URL로 렌더링한다. 선택하지 않은
카테고리와 긴 `info.description`은 본문에 펼치지 않으며, 화면에는 해당 카테고리의 OpenAPI operation과 schema만 표시한다.

## 보기

`spec.js` 를 `<script>` 로 읽으므로 **`index.html` 을 그냥 열어도**(더블클릭 · `file://`) 된다. 정적 서버로 열어도 된다.

```sh
cd docs/api
python3 -m http.server 4000   # http://localhost:4000
```

BCM을 빌드하거나 `./scripts/local.sh up stub`으로 실행하면 같은 문서가 BootJar에 포함된다.

```text
http://127.0.0.1:38080/api-docs/
```

이 주소로 열면 Base URL이 현재 BCM으로 자동 설정된다. 파일로 연 경우 문서는 그대로 보이지만 실제 요청을 하려면 Base URL에
`http://127.0.0.1:38080`처럼 HTTP(S) 주소를 직접 입력한다. API key·private key는 문서에 입력하지 않는다.

## 고치는 법

1. `openapi.yaml` 을 편집한다(스펙 기준).
2. `python3 build.py` 로 `spec.js`(뷰어)·`api.md`(마크다운)·`api.html`(단일 HTML)을 다시 만든다.
3. 브라우저 새로고침.
4. `node --test try-it.test.mjs`로 실행 URL·예시 선택 계약을 확인한다. 전체 CI가 생성물 freshness와 이 테스트를 함께 실행한다.

## 마크다운 export

`api.md` 는 `python3 build.py` 가 `openapi.yaml` 에서 생성한다 — GitHub·위키·PR 어디든 붙여도 열린다. 이식성을 위해:

- 이벤트 시퀀스(`seq`)는 **mermaid `sequenceDiagram`** 으로 변환 → GitHub/GitLab 에서 렌더.
- enum 값·설명, 요청/응답 JSON 예시, 파라미터 표를 모두 펼쳐 담는다.
- 타입 링크(`#schema-…`)는 마크다운 heading 앵커로 바꾼다.

주의: mermaid 는 GitHub/GitLab 은 렌더하지만 Notion·Confluence·Word 는 안 될 수 있다(그 경우 코드블록으로 보임). `api.md` 는 **생성물**이라 직접 고치지 말고 `openapi.yaml` 을 고친다.

## 다른 곳에 삽입

`openapi.yaml` 을 그대로 가져다 쓰면 된다.

```sh
npx @redocly/cli lint openapi.yaml          # 유효성 검사
npx @redocly/cli bundle openapi.yaml -o bundled.yaml   # 단일 파일 추출(검증·정규화)
```

## 배포 (선택)

정적 파일(`index.html`·`spec.js`·`openapi.yaml`)이라 아무 정적 호스트에 올리면 된다(예: Cloudflare Pages, S3, nginx).
