# Production 배포 준비 결정표

> 상태: 보류 · 배포 실행 금지 · 2026-08-21 사용자 결정

이 문서는 향후 Phase 15에서 운영 배포 방식을 결정하기 위한 질문과 결정 로그다. 운영 논의가 재개되기 전에는 서버 설치,
DNS·방화벽 변경, DB migration, Kafka topic 생성, Fireblocks 호출을 실행하지 않는다. 현재 답변은 전제로만 보존하고,
미정 항목을 담당자가 확정한 뒤에만 산출물·배포 순서·runbook을 작성한다.

## 현재 코드가 확정한 경계

| 항목 | 현재 계약 |
|---|---|
| 실행 제공자 | 세 프로세스에 동일한 `BCM_PROVIDER=fireblocks`를 명시. 현재 `dfns`는 미구현으로 기동 거절. 상세는 [기동 계약](../design/12-provider-compatibility.md) 참조 |
| 운영 프로세스 | `bcm-api`, `bcm-webhook`, `bcm-bat` 독립 BootJar. 기동·health·장애 단위가 분리됨 |
| Admin | 현재 `FUNCTION_TEST+loopback` 전용. 환경별로 배포 여부를 선택하되, 공유/운영 환경은 mTLS와 5분 이하 JWT 구현 전 공개 금지 |
| 항상 제외할 산출물 | `blockchain-manager-test-support`, Anvil, Fireblocks Stub, 로컬 system-test 실행기 |
| 데이터 | 외부 PostgreSQL·Kafka 사용. 애플리케이션 산출물에 포함하거나 초기화하지 않음 |
| 외부 연결 | Fireblocks API, Fireblocks JWKS, 운영 경보 수신기. Webhook은 Fireblocks에서 공개 HTTPS ingress로 수신 |
| 관리면 | API `9090`, Webhook `9091` 기본값. 공개 listener와 분리하고 외부 공개 금지 |
| 안전 기본값 | sweep·boost·recovery·archive 등 자금/배치 실행은 기본 비활성 또는 hard ceiling 0 |
| DB DDL | Git 관리 SQL을 DBA가 애플리케이션보다 먼저 적용. 운영 BootJar는 Flyway를 포함하지 않고 DDL을 실행하지 않음 |

## T15.0 결정 로그

`제안`은 검토 출발점이며 확정값이 아니다.

| ID | 결정 항목 | 제안 | 확인할 담당자 | 상태 |
|---|---|---|---|---|
| D01 | 운영 OS·CPU | Linux. 배포판·버전과 `x86_64/aarch64`는 재개 시 확정 | 인프라 | 일부 확정 |
| D02 | 프로세스 관리자 | 일반 Linux 서버의 `systemd` 사용 | 인프라 | 확정 |
| D03 | Java runtime | JDK/JRE 25 패치 버전을 고정하고 폐쇄망 반입·보안 업데이트 절차 정의 | 인프라·보안 | 미정 |
| D04 | 인스턴스 수 | 초기 검증은 API 1, Webhook 1, BAT 1. 검증 결과 뒤 운영 수량 재산정 | 아키텍처·운영 | 임시 확정 |
| D05 | 배포 망 구역 | API는 DAW-CORE 전용 내부망, Webhook은 공개 ingress 뒤 private app, 관리면은 관리망으로 분리 | 네트워크·보안 | 미정 |
| D06 | DNS·TLS 종료점 | 아직 없음. 운영 논의 재개 시 공개 Webhook FQDN·TLS 종료점·인증서 소유자를 확정 | 네트워크·보안 | 보류 |
| D07 | Fireblocks outbound | 공식 API/JWKS 목적지의 DNS·443 allowlist, proxy 사용 여부, timeout 정책 확정 | 네트워크·보안 | 미정 |
| D08 | PostgreSQL | 아직 미정. 버전·HA endpoint·DB/schema·접속 한도·TLS·백업·PITR·역할 분리는 재개 시 확정 | DBA | 보류 |
| D09 | Kafka | 아직 미정. bootstrap/TLS·인증, 4개 topic의 파티션·복제·보관·ACL은 재개 시 확정 | Kafka 운영 | 보류 |
| D10 | Secret 전달 | 아직 없음. API private key·API key·DB/Kafka credential 전달 수단은 재개 시 확정 | 보안·인프라 | 보류 |
| D11 | 관측 연동 | 로그 수집기, metrics scrape 경로, 경보 수신 endpoint와 on-call 소유자 확정 | 운영 | 미정 |
| D12 | 배포 승인 | 변경 요청자·승인자·실행자·검증자 분리, 긴급 중단 권한과 증거 보관 위치 확정 | 운영·보안 | 미정 |
| D13 | RTO/RPO | DB·Kafka·서비스별 업무 RTO/RPO와 허용 가능한 Webhook/Kafka 지연을 업무 기준으로 확정 | 업무·운영 | 미정 |
| D14 | 릴리스 전략 | 불변 release ID, checksum/SBOM/서명, canary 또는 점진 전환, rollback 발동 조건 확정 | 개발·운영 | 미정 |
| D15 | Admin 운영 범위 | 환경에 따라 배포하거나 제외. 공유/운영 배포 시 private listener+mTLS+단기 JWT 구현을 선행 | 보안·업무 | 일부 확정 |

## Phase 15 재개 뒤 이어지는 계획

1. D01~D15를 확정하고 이 표에 결정일·결정자·근거 티켓을 기록한다.
2. 확정값으로 BootJar·설정·SBOM·checksum·서명의 산출물 계약을 작성한다(T15.1).
3. DB migration과 Kafka 준비를 애플리케이션 기동보다 앞선 별도 승인 단계로 작성한다(T15.2).
4. API/Webhook/BAT의 인스턴스·포트·health·종료·singleton 경계를 토폴로지로 고정한다(T15.3).
5. 보안, 관측, 복구, 스테이징·실 Fireblocks 수용, rollout·rollback 계획을 순서대로 검토한다(T15.4~T15.7).

실제 배포 명령·자동화·서버 변경은 T15.7과 운영·보안 사람 리뷰가 끝난 뒤 별도 Phase에서만 수행한다.
