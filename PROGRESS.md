# PROGRESS — 세션 핸드오프

> 50줄 이내. 다음 세션의 AI가 읽는 전제. 결정은 CLAUDE.md·PLAN.md·docs 정본에 둔다.

## 현재 위치

- **Phase 5 T5.0~T5.5 구현·E2E·OpenAPI v0.6.0 대조·converge 완료**. 다음 구현은 Phase 6이다.
- C1: 벤더 400 전부를 externalTxId 조회해 있음→SUBMITTED, 없음→FAILED, 조회 실패→REQUESTED로 판정한다.
  409·422만 즉시 확정 거절이고 근거 없던 오류 코드 1438 분기는 제거했다.
- 제출은 V1 `bcm_sbmt_l` 원장 선커밋 + claim CAS다. 후발은 503/Retry-After, 만료 탈취는 조회 우선이다.
  늦은 벤더 응답·회수 거래 내용 불일치는 성공으로 숨기지 않고 식별자 전용 경보를 발생시킨다.
- Fireblocks 전용 JDK 풀링 HTTP 클라이언트에 connect/read timeout을 적용했다. 기본 claim TTL 120초는
  재시도·백오프와 400 후속 조회를 포함한 보수적 최장 98초보다 길도록 조립 설정에서 검증한다.
- 거래 목록은 최초 조건과 위치를 담은 매니저 자체 커서다. 벤더 커서는 요청 내부에서만 최대 5페이지 쓰며,
  동일 밀리초 후발 txId·필터/정렬 유지·마지막 커서·asc 증분·미매핑 경보 1회성을 회귀 검증한다.
- 상태 번역은 domain 포트 뒤로 분리했고, 체인 등장 전 destinationAddress null을 조회·웹훅 모두 허용한다.
  원장과 다른 vendor txId 웹훅은 재시도 없이 즉시 격리하고 충돌 식별자를 err_msg에 남긴다.
- Claude code-reviewer 최종 판정: **커밋 가능, 신규 Critical 없음**. 직전 Major 2건도 후속 재리뷰에서 해소 확인.
- Claude design-sync 최종 판정: 최신 waas-wiki 02·03 기준 **구현 재작업 없음**. OpenAPI 생성물도 신선하다.
- waas-wiki 최신 02·03을 `docs/design/`에 정상 동기화했고 SHA-256 byte-동일을 확인했다.
- base package와 167개 Kotlin 소스의 물리 경로를 각각 `com.whatto.bcm`, `com/whatto/bcm`으로 이전했다.
  관리 대상 전체 검색에서 이전 조직명은 0건이다.
- 강제 재실행 `./gradlew clean check ktlintCheck --no-daemon --no-build-cache --rerun-tasks` 성공:
  **278 tests, failures/errors/skipped=0**, 101 tasks 전부 실행.

## 남은 것

- **사용자 지시: 지금부터 git push 금지. 사용자가 명시적으로 다시 허용하기 전에는 push하지 않는다.**
- 새 원격 `fireblocks-test`는 빈 저장소다. 새 로컬 root 기준선은 만들었고 push는 별도 허용 전까지 하지 않는다.
- 이전 Git 메타데이터는 `../blockchain-manager-svc.git-backup-20260810-before-reinit`에 복구용으로 보관했다.
  사용자 확인 없이 이 백업을 삭제하지 않는다.
- 기존 CI/CD 초안 4개와 사내 의존성 저장소 분기를 전부 제거했다. 배포 방식은 미정이며 Gradle은
  Gradle Plugin Portal과 Maven Central만 사용한다.
- 이전 V1을 적용한 로컬·공용 개발 DB가 있다면 Flyway repair 대신 DB를 드롭 후 재생성한다.
- `TXRJ`는 코어 회신 후 단일 enum 상수만 교체한다(PLAN #15).
- 실연동 전 남은 외부 결정/실측: #34 벤더 내부 페이징 조합, #35 제출 직후 amountInfo,
  #36 INTERNAL 대납, #37 출금 본문 상한. 운영 경보 채널은 #13이다.
