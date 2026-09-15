"""Build the local DAWBC / Wallet SDK flow comparison. Edit this source, not HTML."""
from pathlib import Path
import html
import re
from sequences import SEQUENCES, svg, BC, SDK
from evidence import stage_references
from differences import DIFFERENCES
from delivery import requirement as delivery_requirement
from outbox import detail as outbox_detail

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / 'docs/flow-comparison/index.html'
E = lambda value: html.escape(str(value), quote=True)

# Cells: route, explanation, state. Right cells describe the replacement scenario,
# not a claim that DAW-CORE has already integrated the SDK.
def cell(route, text, state=''):
    return dict(route=route, text=text, state=state)
def row(stage, left, right, same=False):
    return dict(stage=stage, left=left, right=right, same=same)
def sdk(key, label):
    return ('../wallet-sdk/_reference/'+key+'.html', label)
def daw(page, label):
    return ('../ledger/_reference/page-%02d.html'%page, label)

CASES = [
 dict(id='normal', title='정상 입금', subtitle='입금 확정과 고객 잔고 인정, 집금 허용을 나누어 읽는다.',
      difference='입금 확정은 자금이 온체인에서 도착했다는 뜻이다. SDK의 이동 허용과 CORE의 고객 사용 허용은 각각 판단해야 한다.',
      rows=[
        row('입금 발생',
            cell('블록체인 → 고객 받는주소','외부에서 고객의 받는주소로 자산이 들어온다.'),
            cell('블록체인 → AccountWallet','외부에서 고객의 입금 지갑으로 자산이 들어온다.'),True),
        row('감지',
            cell('블록체인 → DAWBC → 큐 → CORE','Confirmed를 전달한다. CORE는 자산별 입금중을 늘리고 트래블룰 확인 후 고객 입금중을 늘린다. 이어 DAWBC에 반영을 요청한다. DAWBC는 온체인잔고·입금중을 갱신한다.','CORE 입금중 증가'),
            cell('블록체인 → SDK → CORE','DEPOSIT_DETECTED를 통지한다. 이 통지를 받으면 DAW의 Confirmed 때처럼 CORE의 입금중을 늘려도 되는지 확인해야 한다.','입금중 반영 시점 확인')),
        row('확정·잔고 반영',
            cell('블록체인 → DAWBC → CORE → DAWBC → CORE → 서비스','CORE는 고객 SC통화·자산별 입금중을 줄이고 잔고를 늘린다. DAWBC는 반영 요청으로 주소별 입금중을 줄인다. 반영 완료 후 CORE가 서비스에 입금완료를 알린다.','고객 잔고 증가'),
            cell('블록체인 → SDK → CORE','DEPOSIT_FINALIZED는 온체인에서 입금이 확정됐다는 통지다. SDK는 자기 DB에 해당 입금 건을 이동 보류(FROZEN)로 기록하고, 해제 절차를 거쳐 이동을 허용한다. 블록체인에서 자산 자체를 동결한다는 뜻은 아니다. 고객 잔고 반영·사용 허용·서비스 통지 시점은 CORE에서 정해야 한다.','온체인 입금 확정 / SDK 이동 보류')),
        row('자동스윕 — 받는주소 → 보내는주소',
            cell('CORE → BCM → 블록체인','CORE가 여러 고객 지갑의 집금을 요청하면, BCM이 배치로 실행한다.\nCORE는 고객별 성공·실패와 실제 이동액을 반영한다.','여러 고객 지갑 · 배치 집금'),
            cell('CORE → SDK → 블록체인','입금 한 건과 목적지를 지정한다. 설계상 정책 검사 후 집금하며, 현재 실 정책 서버 연결은 미구현이다.\n여러 입금을 묶는 배치 실행은 문서에서 확인되지 않는다.','입금 한 건 · 집금 요청')),
      ],
      decision='예를 들어 100 USDT 입금이 확정되면 고객 입금주소에는 자금이 도착해 있지만, SDK는 그 입금 건을 이동 보류로 기록한다. CORE가 고객에게 입금 확인·사용 대기로 표시할지, 필요한 확인을 마친 뒤 사용 가능 잔고로 반영할지는 연동 시 정한다. 고객에게 인정한 잔고와 출금 풀에서 실제 전송할 수 있는 재고도 구분해야 한다.',
      ),
 dict(id='suspense', title='미확인 입금과 고객 자산 편입', subtitle='SDK의 FROZEN만으로 별단입금 여부를 판단할 수 없다.',
      difference='별단입금은 CORE가 유지한다. 후속 확인 정보가 도착해도 SDK의 이동 상태와 CORE의 귀속 상태를 각각 갱신해야 한다.',
      rows=[
        row('정보 미확인',
            cell('외부 정보 → CORE','트래블룰에 없는 입금은 고객가수금 처리 대상으로 분류한다.','고객 소유권 편입 보류'),
            cell('SDK → CORE','SDK의 입금 확정 여부와 별개로 CORE가 정상 입금인지 판정해야 한다. 모든 확정 입금이 동결되므로 FROZEN만으로 미확인 입금을 구별할 수 없다.','FROZEN ≠ 별단입금')),
        row('입금 확정',
            cell('DAWBC → CORE → DAWBC','자산별 입금중을 줄이고 잔고를 늘린다. 별단입금 상태는 입금완료로 바꾼다. 고객 SC통화잔고에는 바로 편입하지 않는다.','별단입금 입금완료'),
            cell('SDK → CORE','DEPOSIT_FINALIZED 수신 후 CORE가 미확인 건을 별단입금으로 기록하도록 연결한다. SDK에는 해당 입금 건의 동결 상태가 남는다.','CORE 별단입금 / SDK FROZEN')),
        row('후속 정보 수신',
            cell('트래블룰 정보 · 어드민 → CORE','정보가 확인되면 가수금 이동을 요청하고 트래블룰 내역을 확인한다.','정상 입금 여부 확인'),
            cell('SDK → CORE · 문서상 계약','계약에는 DEPOSIT_UPDATED로 트래블룰·KYT 정보를 보충한다고 적혀 있다. 같은 문서는 해당 이벤트·매칭·연동이 아직 구현되지 않았다고 명시한다.','문서상 미구현')),
        row('소유권 편입',
            cell('CORE → 어드민','받는주소로 받은 가수금은 확인 후 별단입금을 이동완료로 바꾸고 고객 SC통화잔고를 늘린다. 이 이동은 온체인 이체가 아니다.','별단입금 이동완료 / 고객 잔고 증가'),
            cell('CORE의 원장 처리','CORE가 같은 편입 처리를 수행하도록 연결한다. 후속 정보의 중복·역순 도착에도 한 번만 편입해야 하며, 이 처리가 SDK 집금이나 해제를 자동으로 뜻하지는 않는다.','연동 필요 · 귀속과 이동 분리')),
      ],
      decision='DEPOSIT_UPDATED를 현재 제공되는 이벤트로 전제하지 않는다. SDK 제공 시점을 확인하거나 기존 트래블룰·확인 정보 수신 경로를 유지해야 한다. 받는주소 외 입금의 고객 편입은 DAW 원문에 제시된 범위를 넘어 별도 검토한다.',
      ),
 dict(id='sweep', title='별단입금 상태의 집금', subtitle='집금이 끝나도 미확인 자금이라는 사실은 없어지지 않는다.',
      difference='SDK에서는 입금 건의 해제와 집금이 결합된다. CORE에서는 집금 후에도 별단입금과 원입금의 연결을 유지해야 한다.',
      rows=[
        row('집금 대상 선정',
            cell('CORE → DAWBC','원문은 가수금도 확정 후 받는주소에 두지 않고 집금한다고 설명한다.','별단입금 상태 유지'),
            cell('CORE → SDK','depositId와 목적지 WorkspaceVault를 지정한다. 확인 근거가 없다는 사실만으로 자동 거부되지는 않으며 정책 판단을 거친다.','입금 건 단위 지정')),
        row('집금 실행',
            cell('DAWBC → 블록체인','고객 받는주소의 자산을 보내는주소로 옮긴다. 별단입금의 귀속 판단은 별도다.','받는주소 → 출금 풀'),
            cell('SDK → 정책·서명 경로 → 블록체인','동결된 입금 건에 활성 이동이 없을 때 조건부 갱신으로 지정한다. 한 입금은 활성 이동 한 건에만 사용한다.','중복 집금 방지')),
        row('집금 완료',
            cell('블록체인 → DAWBC → CORE','출금 풀에 모아 보관한다. 이후 확인되면 고객 자산 편입 또는 오입금 송금으로 처리한다.','보관 위치 변경 / 별단입금 유지'),
            cell('블록체인 → SDK → CORE','집금 경로에서 입금은 RELEASED를 거치지 않고 WITHDRAWN으로 처리된다. CORE는 집금 완료와 별단입금 편입 완료를 구별한다.','SDK 이동 완료 / CORE 별단입금 유지')),
        row('실패·재처리',
            cell('CORE · DAWBC','이 가수금 집금의 실패 복구·중복 방지 상세는 PDF에 충분히 제시되지 않았다.','상세 확인 필요'),
            cell('SDK → CORE','집금 실패가 종결되면 지정 입금은 FROZEN으로 복귀한다. 원입금 확정과 집금 실패는 서로 다른 사건이다.','재집금 가능 / 원입금 취소 아님')),
      ],
      decision='집금된 별단입금을 일반 출금 재고로 쓸 수 있는지 결정해야 한다. 사용 제한이 필요하다면 CORE 재고 계산 또는 SDK 통제로 이어져야 한다. 목적지에 들어온 내부 집금액의 잔액·동결 반영 방식도 SDK 계약에서 확인한다.',
      ),
 dict(id='return-before', title='집금 전 오입금 반환', subtitle='SDK에는 계정 지갑에서 직접 반환하는 운영자 경로가 있다.',
      difference='DAW 원문의 반환 시퀀스는 보내는주소에서 출발한다. SDK의 AccountWallet 직접 반환과 같은 흐름으로 보면 안 된다.',
      rows=[
        row('오입금 확인',
            cell('어드민 → CORE','별도 확인 절차를 거쳐 원래 발신처로 돌려보낸다.','반환 대상 확인'),
            cell('운영자 → SDK','지정한 입금 건의 발신처로 반환한다. 발신처가 기록되지 않은 건은 이 경로에 지정할 수 없다.','원입금의 발신처 제한')),
        row('요청·승인',
            cell('어드민 → CORE → DAWBC','트래블룰내역 등록과 송금 요청을 거쳐 BCTX를 생성·서명한다. 집금 전 받는주소 직접 반환은 별도 시퀀스가 없다.','직접 반환 상세 없음'),
            cell('SDK 운영자 → 승인 → 서명 인가','AccountWallet 출금 aww-는 운영자 전용이며 정족수 승인을 요구하는 설계다. 현재 Wallet Admin 승인 API는 고정 응답이며 quorum은 null이므로 실제 승인 지원을 확인해야 한다.','승인 계약과 현재 구현 구분')),
        row('반환 출발지',
            cell('보내는주소 → 블록체인 → 원발신처','문서에 그려진 출발지는 보내는주소다. 받는주소에서 직접 반환한다고 보충하지 않는다.','보내는주소에서 반환'),
            cell('AccountWallet → 블록체인 → 원발신처','입금 건을 지정한 반환이 해제를 겸한다. WorkspaceVault를 거치지 않는다.','RELEASED 경유 없이 WITHDRAWN')),
        row('CORE 원장 연결',
            cell('DAWBC → CORE','원문 반환은 별단입금을 송금중·송금완료로 갱신하고 보내는주소합을 반영한다.','원문 반환 원장'),
            cell('SDK 반환 결과 → CORE · 연동 필요','직접 반환을 채택하면 CORE가 원입금·반환 ID를 연결해야 한다. 출발지가 받는주소이므로 원문의 보내는주소합 갱신을 그대로 적용하면 안 된다.','출발 주소 구분에 맞춘 원장 반영')),
      ],
      decision='직접 반환 경로를 채택할지 먼저 정한다. 채택한다면 CORE와 SDK 운영자 승인 절차, 반환 결과 수신, 받는주소 기준 잔고 갱신을 함께 설계해야 한다.',
      ),
 dict(id='return-after', title='집금 후 오입금 반환', subtitle='원입금은 계정 지갑에 있었지만 반환 재원은 출금 풀에 있다.',
      difference='SDK의 전용 오입금 반환은 AccountWallet 경로다. 이미 집금된 자금을 WorkspaceVault에서 반환하는 연결 계약은 확인이 필요하다.',
      rows=[
        row('반환 시점의 보관',
            cell('고객 출금 풀','미확인 입금도 집금되어 보내는주소에 보관된다.','별단입금 유지'),
            cell('WorkspaceVault','AccountWallet에서 집금된 자금은 목적지 vault에 있다. CORE의 별단입금 기록은 계속 유지해야 한다.','원입금과 보관 위치 분리'),True),
        row('요청·출발지',
            cell('어드민 → CORE → DAWBC','별단입금 건을 확인하고 보내는주소에서 송금한다.','출금 풀에서 반환'),
            cell('CORE · SDK · 계약 확인','전용 aww- 반환은 원래 AccountWallet을 출발지로 한다. 집금 후 WorkspaceVault에서 반환하는 전용 흐름은 제공 문서에 없다.','그대로 대응하는 경로 없음')),
        row('반환 연결',
            cell('CORE의 별단입금 처리','트래블룰내역과 송금 BCTX를 만들고 별단입금을 송금중으로 바꾼다.','원입금 ↔ 반환 거래'),
            cell('연동 시 정할 부분','일반 출금으로 처리 가능한지, 어느 귀속으로 요청하는지, 목적지 제한과 승인을 어떻게 적용하는지 확인해야 한다.','일반 출금 사용을 확정하지 않음')),
        row('반환 확정',
            cell('블록체인 → DAWBC → CORE','별단입금을 송금완료로 바꾸고 보내는주소합의 송금중과 잔고를 줄인다.','반환 종결'),
            cell('SDK → CORE · 연동 필요','선택한 반환 경로의 완료를 받아 별단입금을 종결한다. 원입금·집금·반환의 연결과 중복 반환 방지가 필요하다.','결과 매핑 계약 필요')),
      ],
      decision='“가수금도 먼저 집금한다”는 DAW 흐름을 유지하려면 이 반환 경로를 우선 확인해야 한다. 고객 소유권이 확정되지 않은 자금을 일반 고객 출금으로 취급해도 된다고 가정하지 않는다.',
      ),
 dict(id='company', title='회사 주소 입금과 회사 자산 편입', subtitle='회사 소유권 인정과 SDK의 사용 승인이 별도 단계가 된다.',
      difference='회사 별단입금 편입 외에 WorkspaceWallet 입금의 독립 해제가 추가된다. SDK 소속만으로 고객 자산과 회사 자기 자산을 구별하지 않는다.',
      rows=[
        row('회사 주소에 입금',
            cell('블록체인 → DAWBC → CORE','고객거래주소·외부입출금주소·콜드월렛으로 들어온 트래블룰 미등록 입금을 회사 별단입금에 기록한다.','회사 별단입금'),
            cell('블록체인 → SDK','회사 용도로 매핑한 WorkspaceWallet의 확정 입금도 FROZEN으로 받는다. Workspace 입금의 통지·해제 결과 계약은 계정 입금과 별도로 확인한다.','SDK 동결')),
        row('회사 귀속 확인',
            cell('어드민 → CORE','회사 자산임을 확인하고 트래블룰 내역을 확인한다.','회사 자산 편입 판단'),
            cell('CORE의 원장 판단','CORE가 회사 소유권을 확인한다. SDK Workspace에 있다는 사실만으로 회사 자기 자산이라고 판정하지 않는다.','고객 풀과 회사 운영 지갑 구분')),
        row('원장 편입',
            cell('CORE','회사 별단입금을 이동완료로 바꾸고 회사 SC통화잔고를 늘린다.','회사 잔고 증가'),
            cell('CORE · 연동 결정','회사 원장 편입과 SDK 해제의 순서를 정한다. 먼저 편입하면 회사 잔고는 있지만 SDK에서는 아직 사용할 수 없는 금액이 생긴다.','원장 잔고 ≠ SDK 가용액')),
        row('사용 허용',
            cell('CORE · DAWBC','SDK처럼 모든 확정 입금에 적용하는 독립 동결 해제 단계는 이 PDF에 제시되지 않았다.','별도 해제 단계 미제시'),
            cell('SDK 운영자 승인 → 독립 해제','Workspace 입금은 건 단위 전액 해제로 FROZEN에서 RELEASED가 된다. 승인 게이팅은 설계이며 현재 Wallet Admin 승인 API의 고정 응답·quorum 미제공을 확인해야 한다.','해제와 승인 지원 여부 구분')),
      ],
      decision='회사 자산 확인과 SDK 해제를 한 업무로 운영할지 정한다. 운영자 승인과 CORE 원장 반영 사이에 한쪽만 완료되는 경우도 처리해야 한다. 회사 정상 입금 전체의 상세 흐름은 PDF의 회사 가수금 이동 시퀀스와 구분한다.',
      ),
]

STAGE_REFS = stage_references(daw, sdk)
for case in CASES:
    assert len(STAGE_REFS[case['id']]) == len(case['rows'])
    assert len(DIFFERENCES[case['id']]) == len(case['rows'])
    assert all((delta is None) == row['same'] for delta, row in zip(DIFFERENCES[case['id']], case['rows']))
    case['refs'] = list(dict.fromkeys(ref for refs in STAGE_REFS[case['id']] for ref in refs))



CONTRACTS = [
 ('정책 심사 구현 상태','BCM은 실행 정책·allowance·서명 통제를 거쳐 배치 집금을 실행하는 구조다.',
  'v1.1.0은 Wallet 서버와 실 정책 서버가 미연결이며 현재 동기 자동 승인한다고 명시한다. 기존 비동기 시퀀스는 연동 설계다.',
  '정책 연결·거부·실패 처리 검증 전에는 SDK 정책 통제가 실제 동작한다고 전제하지 않는다.'),
 ('Vault 이체 완료 기준','온체인 이벤트 이후 CORE 업무 반영을 별도로 수행한다.',
  'Vault 이체는 ON_CHAIN → SETTLING → COMPLETED다. 양쪽 vault 잔액 반영 후 완료 통지를 보낸다. 웹훅 표의 온체인 확정 시점 설명은 최신 변경 이력과 다르다.',
  'SETTLING은 SDK 내부 잔액 반영 중이다. CORE 원장 완료와 구분하고, 스윕·출금 단계에는 이 상태를 추가하지 않는다.'),
 ('큐에 상응하는 전달 보장','현행 BCM은 outbox·relay·Kafka·CORE 완료 확인을 함께 사용한다.',
  '웹훅 재시도는 설명돼 있으나 영속 보관·순서·실패 복구는 확인되지 않는다.',
  'SDK가 발행·전달을 보장하고 CORE는 수신 영속화·원장 반영 완료의 기록과 추적을 책임진다. SDK 완료 회신은 별도 협의한다.'),
 ('요청 완료의 의미','PDF의 요청 호출은 동기 처리이며 서명된 BCTX 반환·전파 요청·반영 완료 단계가 있다.','집금 도식은 생성·201 후 비동기 심사다. 새 결과 읽기는 인가 전 집금 행을 만들지 않고 거부 시 조회 기록이 없다고 설명한다. 현재 자동 승인이라 거부 분기에 도달하지 않는다.','집금 접수·심사·거부·조회 계약을 먼저 맞춘다. 원문 전체 시퀀스는 설계 흐름으로 유지한다.'),
 ('주소별 잔고 반영','이벤트 수신 후 CORE가 DAWBC에 반영 요청을 보내는 순서가 그려져 있다.','SDK는 자체 입금·집금 상태와 지갑 원장을 관리하고 통지한다.','CORE가 SDK의 잔고를 직접 갱신한다고 가정하지 않는다. 조회 필드·시점·반영 요청 대응 여부를 확인한다.'),
 ('후속 정보와 중복','트래블룰 내역 확인과 가수금 이동을 설명하지만 이벤트 중복·역순 상세는 부족하다.',
  'correlationKey 규칙은 설명에 있으나 감지·확정 JSON 예시에는 없다. eventId 지원은 확인되지 않는다. UPDATED·TR·KYT는 문서상 미구현이다.',
  'SDK에 eventId 발급과 재전송 시 동일 ID 유지를 요구한다. 순서 판단은 별도 버전·상태 전이 규칙으로 처리한다.'),
 ('동시 출금의 예약','SC통화·자산별 송금중과 주소별 송금감지를 기록한다. 잠금·예약 상세는 추가 확인 대상이다.','예약을 세 송금에 적용한다는 설명과 현재 Vault 이체에만 적용됐다는 설명이 함께 있다.','출금 요청 전에 어느 계층이 금액을 예약하고 실패 시 누가 해제하는지 실제 구현을 확인한다.'),
 ('집금 목적지 통제','고객·회사와 받는주소·보내는주소·콜드월렛의 업무 용도를 구분한다.','WorkspaceVault 용도는 파트너 태그로 정의한다. 태그를 정책 입력으로 읽는 경로는 문서상 신설 예정이다.','주소 매핑만으로 목적지 통제가 완성되지 않는다. 고객 풀·회사 운영 지갑을 구분하는 실행 통제를 확인한다.'),
]

DECISIONS = [
 ('고객 잔고 반영과 사용 허용','입금 확정 후 고객 잔고에 언제 반영하고 사용을 허용할지, 서비스에 무엇을 알릴지 정한다. SDK의 이동 보류(FROZEN)만으로 CORE의 고객 사용 가능 여부가 결정되지는 않는다.','normal'),
 ('별단입금의 집금 후 사용','출금 풀에 모인 미확인 금액을 재고로 사용할지. 제한한다면 어느 계층에서 금액을 제외할지.','sweep'),
 ('집금 전·후 반환 경로','AccountWallet 직접 반환을 채택할지, 집금 후 WorkspaceVault 반환을 SDK가 어떻게 지원할지.','return-after'),
 ('확인 정보와 승인 결과 수신','미구현 UPDATED의 대체 경로, 회사 입금 해제 및 운영자 반환 결과를 CORE에 연결하는 방법.','suspense'),
]

def link(href, title):
    target=(OUT.parent/href).resolve()
    assert target.is_file(), target
    return f'<a class="source-link" href="{E(href)}" target="_blank" rel="noopener">{E(title)} <span aria-hidden="true">↗</span><span class="sr-only"> (새 창)</span></a>'
def content(c, side):
    paragraphs = ''.join(f'<p>{E(paragraph)}</p>' for paragraph in c['text'].splitlines())
    return f'<td class="{side}"><div class="route">{E(c["route"])}</div>'+paragraphs+ (f'<span class="state">{E(c["state"])}</span>' if c['state'] else '')+'</td>'
def architecture(label, middle, side):
    return f'<div class="architecture {side}"><p>{label}</p><div class="chain">'+ '<span class="arrow" aria-hidden="true">→</span>'.join(f'<strong class="{"replaced" if n==middle else ""}">{n}</strong>' for n in ['서비스','DAW-CORE',middle,'블록체인'])+'</div></div>'

def sequence_template(ident, title, sequences, refs, case='', stage=0):
    cards = []
    for side, backend, seq in zip(['daw', 'sdk'], [BC, SDK], sequences):
        heading = ('자동스윕 · BCM 배치 실행' if case=='normal' and stage==4 else 'DAW 원문') if side=='daw' else 'SDK 교체 검토'
        basis = f'원문 전체 · {len(seq["messages"])}개 호출' if seq.get('detailed') else ('연동 검토·확인 필요' if seq['review'] else '문서 흐름 요약')
        enlarge = f'<button type="button" data-diagram-open aria-haspopup="dialog" aria-controls="diagram-zoom-dialog" aria-label="{E(heading)} 다이어그램 확대 모달">확대 모달 ↗</button>' if case=='normal' and stage==4 else ''
        cards.append(f'<article class="sequence-card {side}"><header><h3>{E(heading)}</h3><span>{basis}</span>{enlarge}</header><div class="sequence-figure">'+svg(seq, backend, ident+'-'+side)+f'</div><p class="sequence-note">{E(seq["note"])}</p></article>')
    detailed = any(seq.get('detailed') for seq in sequences)
    dimensions = ' data-left-width="690" data-right-width="1980"' if detailed else ''
    delta = DIFFERENCES[case][stage-1]
    comparison = ''
    if delta:
        comparison = f'<section class="sequence-difference" aria-label="이 단계의 차이"><h3>이 단계의 차이</h3>'
        comparison += f'<div class="difference-sides"><p class="difference-daw"><strong>DAWBC</strong>{E(delta["left"])}</p>'
        comparison += f'<p class="difference-sdk"><strong>Wallet SDK</strong>{E(delta["right"])}</p></div>'
        comparison += f'<p class="difference-review"><strong>CORE 연동</strong>{E(delta["review"])}</p></section>'
    support = {
        ('normal', 4): '실 정책 서버는 미연결이며 현재 동기 자동 승인이다. 집금 기록 생성·거부 시점은 원문 시퀀스와 새 결과 읽기의 설명이 달라 확인이 필요하다.',
        ('sweep', 1): '현재 정책 인가는 자동 승인이다. 정책 거부 시 집금 기록이 없다는 새 설명과 기존 생성·201 후 심사 도식의 차이를 확인해야 한다.',
        ('sweep', 2): '아래 정책·서명 경로는 연동 설계다. 현재 Wallet 서버는 실 정책 서버와 연결되지 않아 자동 승인한다.',
        ('return-before', 2): '정족수 승인은 설계 요구다. 현재 Wallet Admin 승인 API는 고정 응답이며 quorum은 null이다. 실제 반환 승인 지원을 확인해야 한다.',
        ('company', 4): '독립 해제의 승인 게이팅은 설계 요구다. 현재 Wallet Admin 승인 API의 고정 응답·quorum 미제공과 실제 해제 승인 지원을 확인해야 한다.',
    }.get((case, stage))
    if support:
        comparison += '<p class="sequence-support-note"><strong>SDK 현재 구현 · v1.1.0</strong> '+E(support)+'</p>'
    has_events = detailed or any(any(key in msg[2] for key in ('eventId', 'DEPOSIT_', 'SWEEP_', 'WITHDRAWAL_', 'VAULT_TRANSFER_')) for msg in sequences[1]['messages'])
    if has_events:
        comparison += '<div class="sequence-requirement-link"><span><strong>SDK에 eventId 발급 요구</strong> · 지원 여부 미확인</span>'
        comparison += '<div class="requirement-actions"><button data-event-details aria-haspopup="dialog" aria-controls="event-details-dialog">사유·발급 규칙 보기 ↗</button>'
        comparison += '<button data-queue-details aria-haspopup="dialog" aria-controls="event-details-dialog">Queue 대응 요구사항 ↗</button></div></div>'
    return f'<template id="{ident}" data-case="{case}" data-stage="{stage}" data-title="{E(title)}">'+comparison+f'<div class="sequence-pair"{dimensions}>'+''.join(cards)+'</div><div class="sequence-sources">'+''.join(link(*ref) for ref in refs)+'</div></template>'


body='''<a class="skip" href="#main">본문으로</a>
<header class="site-header"><a class="sdk-return" href="../wallet-sdk/index.html">← Wallet SDK로 돌아가기</a><a class="brand" href="#top">DAW <span>/</span> 자금 흐름 비교</a><div><span class="confidential">Strictly Confidential</span><button id="print" class="js-only">인쇄</button></div></header>
<main id="main"><section class="hero" id="top"><p class="eyebrow">DAW 원장 v0.1.4 · Wallet SDK 문서 비교</p><h1>DAWBC에서 Wallet SDK로</h1><p class="lead">입금부터 반환까지, DAW-CORE의 흐름은 어디서 달라지는가.</p><p class="scope">왼쪽은 설계자 원문, 오른쪽은 SDK로 교체할 때의 흐름입니다. 자동스윕 단계에는 BCM의 배치 요청부터 결과 반영까지 함께 담았습니다. 같은 단계의 요청·상태·원장 반영을 나란히 배치했습니다.</p><div class="architectures">'''
body+=architecture('01 · DAW 원문 구조','DAWBC','daw')+architecture('02 · SDK 교체 검토','WALLET-SDK','sdk')
body+='''</div><p class="caption">요청 방향을 단순화한 구조입니다. 온체인 이벤트는 반대 방향으로 전달됩니다. 자동스윕의 SDK 도식은 정책 엔진·저장소·이벤트 스트림·서명 인프라·뒷단 플랫폼의 전체 호출을 표시합니다.</p></section>
<div class="document-basis"><div><strong>비교 기준</strong><span>원장 PDF v0.1.4 · SDK v1.1.0 · 보관본 2026-09-11<br>자동스윕 실행: 저장소 BCM API·배치 설계</span></div><div><strong>오른쪽을 읽는 법</strong><span>SDK 계약 + CORE 연동 검토. 현재 Wallet 서버의 실 정책 서버 연결은 미구현이며 동기 자동 승인입니다.</span></div><div><strong>주소 대응 가정</strong><span>고객 받는주소 ≈ AccountWallet<br>고객 출금 풀·회사 운영 주소 ≈ 용도를 나눈 WorkspaceVault</span></div></div>
<aside class="notice"><strong>동결과 별단입금은 서로 다른 상태입니다.</strong><p>동결은 SDK의 이동 허용 여부입니다. 별단입금은 CORE가 고객·회사 소유권 원장에 아직 편입하지 않은 기록입니다. 집금 완료만으로 별단입금을 종결하지 않습니다.</p></aside>
<nav class="section-nav" aria-label="비교할 업무">'''
body+=''.join(f'<a href="#{c["id"]}"><span>{i:02}</span>{E(c["title"])}</a>' for i,c in enumerate(CASES,1))
body+='''<a href="#contracts">공통 연동 계약</a><a href="#sdk-event-id">eventId 요구사항</a><a href="#sdk-event-delivery">Queue 대응 요구사항</a><a href="#decisions">결정할 사항</a></nav>
<div class="reader-controls js-only"><div role="group" aria-label="표시할 단계"><button data-mode="all" aria-pressed="true">전체 단계</button><button data-mode="differences" aria-pressed="false">차이가 있는 단계만</button></div><p id="filter-status" role="status">모든 단계를 표시합니다.</p></div>
<p class="sequence-help js-only">단계 이름이나 행을 누르면 좌우 시퀀스를 볼 수 있습니다.</p><p class="mobile-hint">모바일에서도 좌우 비교를 유지합니다. 표를 좌우로 밀어 읽을 수 있습니다.</p>'''
templates=[]
for i,c in enumerate(CASES,1):
    left_basis = 'DAW 원문 · 자동스윕은 BCM 실행 포함' if c['id']=='normal' else 'DAW 원문'
    assert len(SEQUENCES[c['id']]) == len(c['rows'])
    body+=f'<section class="case" id="{c["id"]}"><div class="section-title"><span class="section-number">{i:02}</span><div><h2>{E(c["title"])}</h2><p>{E(c["subtitle"])}</p></div><a class="permalink" href="#{c["id"]}" aria-label="{E(c["title"])} 바로가기">#</a></div>'
    body+=f'<p class="difference"><span>흐름 차이</span>{E(c["difference"])}</p><div class="compare-scroll" tabindex="0" role="region" aria-label="{E(c["title"])} 좌우 비교표"><table class="comparison"><caption class="sr-only">{E(c["title"])}의 단계별 DAWBC와 Wallet SDK 비교</caption><colgroup><col class="stage-col"><col><col></colgroup><thead><tr><th scope="col">같은 단계</th><th class="daw" scope="col"><small>{E(left_basis)}</small>DAW-CORE ↔ DAWBC</th><th class="sdk" scope="col"><small>SDK 교체 검토</small>DAW-CORE ↔ WALLET-SDK</th></tr></thead><tbody>'
    for j,r in enumerate(c['rows'],1):
        sequence_id=f'sequence-{c["id"]}-{j}'
        body+=f'<tr data-same="{str(r["same"]).lower()}" data-sequence="{sequence_id}"><th scope="row"><span class="row-number">{j:02}</span><span class="stage-name">{E(r["stage"])}</span><button class="sequence-open" data-open-sequence="{sequence_id}" aria-haspopup="dialog" aria-controls="sequence-dialog">{E(r["stage"])}<small>시퀀스 보기 ↗</small></button></th>'+content(r['left'],'daw')+content(r['right'],'sdk')+'</tr>'
        templates.append(sequence_template(sequence_id, c['title']+' · '+r['stage'], SEQUENCES[c['id']][j-1], STAGE_REFS[c['id']][j-1], case=c['id'], stage=j))
    body+='</tbody></table></div>'+f'<aside class="decision"><strong>연동 시 정할 부분</strong><p>{E(c["decision"])}</p></aside><details class="sources"><summary>근거 문서 <span>{len(c["refs"])}개 · 로컬 새 창</span></summary><div>'+''.join(link(*r) for r in c['refs'])+'</div></details>'
    body+='</section>'
body+='''<section class="case" id="contracts"><div class="section-title"><span class="section-number">↔</span><div><h2>업무 전반에서 확인할 연동 계약</h2><p>SDK API로 이름만 바꾸면 맞지 않는 부분입니다.</p></div></div><div class="compare-scroll" tabindex="0" role="region" aria-label="공통 연동 계약 비교"><table class="contracts"><thead><tr><th scope="col">항목</th><th scope="col">DAW 원문</th><th scope="col">SDK 문서</th><th scope="col">연동 확인</th></tr></thead><tbody>'''
for name,l,r,d in CONTRACTS:body+='<tr><th scope="row">'+E(name)+'</th>'+''.join('<td>'+E(x)+'</td>' for x in [l,r,d])+'</tr>'
body+='</tbody></table></div><div class="contract-sources">'+''.join(link(*r) for r in [daw(4,'호출·이벤트 구조'),daw(11,'주소별 잔고'),sdk('fund-flows--sweep','SDK 비동기 집금'),sdk('fund-flows--webhooks','SDK 통지'),sdk('fund-flows--release','SDK 예약 적용 범위'),sdk('accounts-wallets--wallets','Vault 용도'),sdk('policy--results','SDK 정책 결과·현재 구현'),sdk('fund-flows--vault-transfer','SDK Vault 이체'),sdk('changelog--v1-1-0','SDK v1.1.0 변경 이력')])+'</div>'
body+='''<article class="event-requirement" id="sdk-event-id" aria-labelledby="event-requirement-title">
<p class="requirement-status">SDK 연동 요구사항 · 지원 여부 미확인</p>
<h3 id="event-requirement-title">웹훅에 eventId를 요구한다</h3>
<p>SDK가 발행하는 각 웹훅 이벤트에 고유한 <code>eventId</code>를 포함하도록 요구한다.
<code>depositId</code>는 입금 건, <code>eventType</code>은 사건의 종류,
<code>eventId</code>는 개별 이벤트를 식별한다.</p>
<h4>요구 사유</h4>
<ul>
<li><strong>재전송과 새 이벤트 구분.</strong> 같은 입금의 같은 유형이 다시 와도 재전송인지 새로운 정보 갱신인지 구분할 수 있어야 한다.</li>
<li><strong>원장 중복 반영 방지.</strong> 입금·집금·출금 통지를 CORE에서 공통 기준으로 중복 제거한다. 향후 UPDATED처럼 같은 유형의 갱신이 여러 번 와도 누락하지 않는다.</li>
<li><strong>양쪽 처리 이력 대조.</strong> SDK 발행 이력과 CORE 수신·처리 이력을 같은 ID로 찾아 재전송과 장애 복구를 확인한다.</li>
</ul>
<h4>SDK에 요구할 발급 규칙</h4>
<ul>
<li>이벤트를 최초 생성할 때 ID를 확정하고 발행 기록에 보관한다. 테넌트·거래·이벤트 종류가 달라도 SDK 발행 범위 안에서 중복되지 않아야 한다.</li>
<li><strong>동일 이벤트의 재시도·재전송·재생은 같은 eventId</strong>를 사용한다. HTTP 전송 시도마다 새로 발급하지 않는다.</li>
<li><strong>새 상태 변경·정보 갱신에는 새 eventId</strong>를 발급한다. 같은 depositId·eventType이어도 별개의 갱신이면 ID가 달라야 한다.</li>
<li>eventType과 해당 거래 식별자도 함께 제공한다. eventId가 depositId·sweepId·withdrawalId를 대신하지 않는다.</li>
</ul>
<p><strong>CORE 처리:</strong> eventId의 고유 제약으로 동시 중복 처리를 막고,
이벤트 처리 완료 기록과 CORE 원장 반영을 같은 트랜잭션으로 묶는다.
<strong>eventId는 순서를 보장하지 않는다.</strong> 동일 거래의 최신 갱신을 구분할 순번·버전 계약과 상태 전이 규칙은 별도로 확인한다.</p>
<p class="requirement-limit"><strong>지원 전 임시 기준:</strong> 감지·확정·실패처럼 입금별 이벤트 유형이 한 번씩 발생하는 범위는
<code>depositId + eventType</code>으로 중복 제거한다. 반복 가능한 UPDATED에는 이 조합만 사용할 수 없으며 갱신 순번·버전이 필요하다.
correlationKey는 문서 설명에 있으나 실제 수신 필드는 확인이 필요하다. 이 요구사항은 SDK의 구현 완료를 뜻하지 않는다.</p>
</article>'''
body+=delivery_requirement(link)+'</section>'
body+='''<section class="case" id="decisions"><div class="section-title"><span class="section-number">?</span><div><h2>먼저 결정할 네 가지</h2><p>비교에서 드러난 쟁점이며 아직 확정된 설계가 아닙니다.</p></div></div><ol class="decision-list">'''
for title,desc,section in DECISIONS: body+=f'<li><div><strong>{E(title)}</strong><p>{E(desc)}</p></div><a href="#{section}">해당 흐름 ↑</a></li>'
body+='</ol></section><footer><p>2026-09-11 작성 · Strictly Confidential</p><div>'+link('../ledger/index.html','DAW 원장 가이드')+link('../wallet-sdk/index.html','Wallet SDK 가이드')+'</div><p>문서에 적힌 설계·계약을 비교한 자료입니다. SDK 최신 배포 상태와 BCM 구현 상태를 검증한 결과는 아닙니다.</p></footer></main>'
body+=''.join(templates)+'''<dialog id="sequence-dialog" aria-labelledby="sequence-title">
<header class="sequence-toolbar">
<div>
<p id="sequence-position">
</p>
<h2 id="sequence-title">단계 시퀀스</h2>
</div>
<button data-sequence-maximize aria-pressed="false" title="브라우저 창 전체에 펼치기">전체 화면</button>
<button data-sequence-close aria-label="시퀀스 닫기">닫기 ×</button>
</header>
<div class="sequence-tools">
<div class="sequence-navigation">
<button data-sequence-prev>← 이전 단계</button>
<button data-sequence-next>다음 단계 →</button>
</div>
<div class="sequence-side">
<button data-sequence-side="both" aria-pressed="true">나란히 보기</button>
<button data-sequence-side="daw" aria-pressed="false">DAWBC 크게</button>
<button data-sequence-side="sdk" aria-pressed="false">SDK 크게</button>
<button data-sequence-browse aria-expanded="false" aria-controls="sequence-reader" hidden>호출 탐색</button>
</div>
<div class="sequence-zoom">
<button data-sequence-zoom="-" aria-label="시퀀스 축소">−</button>
<output id="sequence-zoom-level">100%</output>
<button data-sequence-zoom="+" aria-label="시퀀스 확대">＋</button>
<button data-sequence-zoom="fit">기본 크기</button>
</div>
</div>
<p class="sequence-legend">실선: 요청·내부 처리 · 점선: 응답·이벤트 · «CORE 유지» 표시: SDK 교체 후에도 유지할 CORE 업무 · 황색 점선: 미확정 연동<br>시간은 위에서 아래로 흐릅니다. SDK의 정책·서명·뒷단 플랫폼은 SDK 경로에 묶었습니다.</p>
<div id="sequence-reader" hidden>
<div class="call-controls">
<label for="sequence-phase">SDK 구간</label>
<select id="sequence-phase" aria-label="SDK 구간 바로가기">
</select>
<button data-call-prev aria-label="이전 SDK 호출">← 이전 호출</button>
<output id="sequence-call-position" aria-live="polite">
</output>
<button data-call-next aria-label="다음 SDK 호출">다음 호출 →</button>
</div>
<div class="call-content" aria-live="polite">
<strong id="sequence-call-route">
</strong>
<p id="sequence-call-text" tabindex="0" aria-label="선택한 호출의 전체 내용">
</p>
</div>
</div>
<div id="sequence-canvas" tabindex="0" role="region" aria-label="좌우 시퀀스 다이어그램. 좌우로 스크롤할 수 있습니다.">
</div>
<footer class="sequence-footer">
<strong>이 시퀀스의 근거 · 새 창</strong>
<div id="sequence-evidence">
</div>
</footer>
</dialog>'''
body+='''<dialog id="event-details-dialog" aria-labelledby="event-details-title">
<header class="event-details-toolbar"><h2 id="event-details-title">eventId 요구사항</h2>
<button data-event-details-close aria-label="eventId 상세 닫기">닫기 ×</button></header>
<div id="event-details-content"></div></dialog>'''
body+='''<dialog id="diagram-zoom-dialog" aria-labelledby="diagram-zoom-title">
<header class="diagram-zoom-toolbar"><h2 id="diagram-zoom-title">다이어그램 확대</h2>
<button type="button" data-diagram-close aria-label="확대 다이어그램 닫기">닫기 ×</button></header>
<div class="diagram-zoom-controls" role="group" aria-label="다이어그램 배율">
<button type="button" data-diagram-zoom="-" aria-label="다이어그램 축소">−</button>
<output id="diagram-zoom-level" aria-live="polite">100%</output>
<button type="button" data-diagram-zoom="+" aria-label="다이어그램 확대">＋</button>
<button type="button" data-diagram-zoom="actual">원본 크기</button>
<button type="button" data-diagram-zoom="fit">가로 맞춤</button>
<p>확대 후 가로·세로로 스크롤할 수 있습니다. 닫으면 비교 화면으로 돌아갑니다.</p></div>
<div id="diagram-zoom-viewport" tabindex="0" role="region" aria-label="확대 다이어그램, 가로·세로 스크롤 가능"><div id="diagram-zoom-content" inert></div></div>
</dialog>'''
css=(HERE/'style.css').read_text()
js=(HERE/'reader.js').read_text()+'\n'+(HERE/'tooltips.js').read_text()+'\n'+(HERE/'diagram-zoom.js').read_text()+'\n'+(HERE/'participant-headers.js').read_text()
outbox_tip = '거래 상태와 전송할 이벤트를 같은 DB 트랜잭션에 저장하고, 별도 작업이 발송하는 구조입니다.'
outbox_link = '<a href="#bcm-outbox" class="term-tooltip" aria-haspopup="dialog" aria-controls="outbox-dialog" title="'+E(outbox_tip)+'">Outbox <span aria-hidden="true">ⓘ</span></a>'
# Replace visible text only; term definitions and other attributes stay plain text.
body = ''.join(part if part.startswith('<') else re.sub(r'(?<![A-Za-z0-9_])outbox(?![A-Za-z0-9_])', lambda _: outbox_link, part, flags=re.I)
               for part in re.split(r'(<[^>]*>)', body))
body += outbox_detail()
OUT.write_text('<!doctype html>\n<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>DAWBC · Wallet SDK 자금 흐름 비교</title><style>'+css+'</style></head><body>'+body+'<script>'+js+'</script></body></html>\n')
print('Built',OUT.relative_to(ROOT),':',len(CASES),'flows,',sum(len(c['rows']) for c in CASES),'paired steps')
