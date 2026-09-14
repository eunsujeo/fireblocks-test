# 상황별 운영 절차

BCM 개발·운영 담당자를 위한 설정과 장애 대응 안내다. 승인 조건과 계약은 각 절차가 연결하는 설계를 따른다.
[전체 문서 안내](../README.md) · [운영 로그 정책](../design/11-operational-log-policy.md)

| 상황 | 볼 문서 |
|---|---|
| 지연·적체·경보를 확인하거나 수집을 설정한다 | [모니터링: 메트릭과 경보 채널](monitoring.md) |
| 웹훅 구독이 꺼졌거나 수신 공백을 복구한다 | [웹훅 구독 복구](webhook-recovery.md) |
| 제공자 원천 등록을 준비하거나 기동 불일치를 확인한다 | [원천 등록과 기동 오류](provider-origin.md) |
| 의존성 취약점 검사에 실패했다 | [스캔·예외·대응](dependency-vulnerability-scan.md) |
| 운영 배포를 준비한다 | [배포 결정표](production-deployment-plan.md) — Phase 15 보류, 실행 절차 미확정 |

로컬 실행·로그 조회는 [프로젝트 README](../../README.md), 기능 검증은 [테스트 전략](../testing.md)을 따른다.
