---
paths:
  - "blockchain-manager-admin/**/*.ts"
  - "blockchain-manager-admin/**/*.tsx"
  - "blockchain-manager-admin/**/*.css"
  - "**/admin/**/*.ts"
  - "**/admin/**/*.tsx"
  - "**/admin/**/*.css"
  - "**/Admin*.tsx"
---
# Admin UX

- 운영자의 흐름을 `이상 발견 → 검색 → 근거 조사 → diff → 승인 → 실행 → 대사 → 감사`로 잇는다.
- 목록 필터·정렬·페이지는 URL에 보존하고 상세에서 돌아와도 유지한다.
- 운영·testnet 환경을 고정 배너와 텍스트로 구분한다. 색만으로 상태를 전달하지 않는다.
- 식별자·주소는 축약 가능하지만 전체값 확인·복사를 제공한다.
- 화면 시간은 로컬 변환값과 UTC 원문을 확인할 수 있게 하고 데이터 기준·갱신·만료 시각을 표시한다.
- 고위험 변경은 이전/신규 diff, 대상, 사유, 작업 티켓을 확인한다.
- 활성화·상향·재개·자금 이동은 optimistic update를 쓰지 않고 서버 재조회로 완료를 확인한다.
- 프론트에서 금액·밴드S·정책 상한·가능한 상태 전이를 계산하지 않는다.
- loading·empty·error·forbidden·stale·partial·completed 상태를 모두 설계하고 오류에 다음 조치를 적는다.
- 비활성 action은 숨기기보다 금지 사유와 필요한 선행조건을 보여 준다.
- 키보드 탐색·포커스·스크린리더 레이블·명도 대비를 제공한다.
