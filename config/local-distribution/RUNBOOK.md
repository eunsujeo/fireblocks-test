# Blockchain Manager 로컬 통합환경 폐쇄망 배포

이 패키지는 일반 Linux 서버에 Anvil과 Fireblocks Stub만 설치한다. 기존 BCM의 PostgreSQL과 Kafka는 변경하지 않는다.
Docker를 요구하거나 두 인프라를 포함·설치·초기화·reset하지 않는다. 서버의 기존 BCM은 loopback Stub URL을 사용하도록
별도 배포 설정에서 연결한다.

## 반입 전

연결 환경에서 서버 CPU와 같은 `linux-x86_64` 또는 `linux-aarch64` tar.gz를 만든다. 아카이브와 별도로 전달받은 해시를
대조한 뒤 폐쇄망으로 반입한다. 압축 해제 후 다음 명령은 내부 `SHA256SUMS`, Anvil 1.7.1, JRE 25와 CPU 아키텍처를 다시
검증하며 네트워크 다운로드를 수행하지 않는다.

```sh
./install.sh verify
```

연결 환경의 CI는 smoke 이미지를 미리 받은 뒤 `scripts/internal/local-distribution-smoke.sh <tar.gz>`를 실행한다. 이 검사는
컨테이너 네트워크를 `none`으로 고정한 채 chain·Stub 기동, reset, 종료와 재기동 및 결정적 manifest를 검증한다.

## 설치와 확인

```sh
sudo ./install.sh install
sudo systemctl status bcm-local-anvil bcm-local-stub
sudo /opt/blockchain-manager-local/current/bin/bcm-local health-chain
sudo /opt/blockchain-manager-local/current/bin/bcm-local health-stub
```

최초 설치는 `/etc/blockchain-manager-local/stub.env`를 `0600`으로 만들고 런타임 seed·EVM 키는
`/var/lib/blockchain-manager-local` 아래에서 `bcm-local` 사용자만 읽게 생성한다. Stub과 제어 endpoint는 loopback에만
bind한다. 실 Fireblocks API key·private key·외부 RPC URL을 이 설정에 넣지 않는다.

기존 BCM은 Stub과 JWKS가 준비된 뒤 다음 주소를 사용한다.

- Fireblocks Base URL: `http://127.0.0.1:18080`
- Webhook JWKS URL: `http://127.0.0.1:18080/.well-known/jwks.json`
- 로컬 EVM RPC: `http://127.0.0.1:8545`

## reset과 재기동

활성 BCM 제출·batch 작업을 먼저 중지하고 진행 중 Stub 거래가 없음을 확인한다.

```sh
sudo /opt/blockchain-manager-local/current/bin/bcm-local reset
sudo /opt/blockchain-manager-local/current/bin/bcm-local restart
```

reset은 Stub·Anvil만 기준 상태로 복원한다. BCM PostgreSQL과 Kafka 데이터는 그대로 남으므로 전체 E2E 데이터 초기화는
전용 테스트 DB·topic·run id를 소유하는 별도 BCM 절차로 수행한다.

## 롤백

설치기는 직전 release를 `previous` symlink로 보존한다. 새 release 기동이 실패하면 자동으로 직전 release를 복원한다.
수동 롤백은 다음과 같다.

```sh
sudo /opt/blockchain-manager-local/current/install.sh rollback
# 또는 releases 아래의 명시적인 디렉터리 이름 지정
sudo /opt/blockchain-manager-local/current/install.sh rollback blockchain-manager-local-<version>-linux-x86_64-<manifest-sha256>
```

로그는 `bcm-local logs` 또는 `journalctl -u bcm-local-anvil -u bcm-local-stub`으로 확인한다.
