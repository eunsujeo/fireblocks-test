"""Reviewed, stage-specific sequences. No routes are inferred from display text.

call: request; reply: response; event: notification; proposal: integration still
undecided; core: DAW business steps retained in the replacement scenario.
Notes distinguish unconfirmed SDK contracts from retained CORE work.
"""
from html import escape as esc
import textwrap
from sdk_sweep import load_sweep, detailed_svg

CORE='DAW-CORE'; BC='DAWBC'; SDK='WALLET-SDK'; CHAIN='블록체인'; SERVICE='서비스'; ADMIN='어드민'; QUEUE='큐'; TR='트래블룰'
def m(a,b,label,kind='call'):return (a,b,label,kind)
def c(a,b,label):return m(a,b,label,'core')
def s(*messages,note='',review=False):return dict(messages=list(messages),note=note,review=review)
def pair(left,right):return (left,right)

SEQUENCES={
'normal':[
 pair(s(m(CHAIN,CHAIN,'고객 받는주소로 자산 전송'),note='입금 발생을 표시한다. CORE의 입금중 반영은 다음 감지 단계다.'),
      s(m(CHAIN,CHAIN,'AccountWallet 주소로 자산 전송'),note='온체인 자산 유입과 SDK의 확정·동결 처리는 다른 단계다.')),
 pair(s(m(CHAIN,BC,'입금 BCTX Confirmed','event'),m(BC,QUEUE,'Confirmed 전달','event'),m(QUEUE,CORE,'Confirmed 수신','event'),m(CORE,CORE,'자산별 입금중 증가'),m(CORE,CORE,'트래블룰 확인 · 고객 입금중 증가'),m(CORE,BC,'Confirmed 반영 요청'),m(BC,BC,'주소별 입금중 · 온체인잔고 증가'),m(BC,CORE,'반영 완료','reply'),m(CORE,SERVICE,'입금중 통지','event'),note='자산별 입금중 갱신 후 트래블룰을 확인한다.'),
      s(m(CHAIN,SDK,'입금 관측','event'),
        m(SDK,SDK,'입금 기록 생성 · depositId / txHash'),
        m(SDK,SDK,'전달 보장 · SDK에 요구','proposal'),
        m(SDK,CORE,'웹훅 · DEPOSIT_DETECTED','event'),
        m(CORE,CORE,'eventId로 중복 제거 · SDK에 요구','proposal'),
        c(CORE,CORE,'자산별 입금중 증가'),
        c(CORE,CORE,'확인 정보로 트래블룰 내역 확인'),
        c(CORE,CORE,'확인된 고객의 입금중 증가'),
        c(CORE,SERVICE,'입금중 통지'),
        note='“CORE 유지”가 붙은 항목은 SDK 교체 후에도 유지할 CORE 업무다. DEPOSIT_DETECTED 수신 시 입금중을 늘려도 되는지 확인한 뒤 적용한다. SDK의 트래블룰 매칭·KYT·UPDATED는 문서상 미구현이다. SDK는 자체 입금 기록을 갱신하며, CORE가 Confirmed 반영을 다시 요청하는 대응 API는 확인되지 않는다.',review=True)),
 pair(s(m(CHAIN,BC,'입금 BCTX Finalized','event'),m(BC,QUEUE,'Finalized 전달','event'),m(QUEUE,CORE,'Finalized 수신','event'),m(CORE,CORE,'고객 SC통화 입금중 감소 · 잔고 증가'),m(CORE,CORE,'자산별 입금중 감소 · 잔고 증가'),m(CORE,BC,'Finalized 반영 요청'),m(BC,BC,'주소별 입금중 감소'),m(BC,CORE,'반영 완료','reply'),m(CORE,SERVICE,'입금완료 통지','event'),note='주소별 온체인잔고는 여기서 다시 증가시키지 않는다.'),
      s(m(CHAIN,SDK,'입금 확정','event'),
        m(SDK,SDK,'DB에 이동 보류(FROZEN) 기록'),
        m(SDK,SDK,'전달 보장 · SDK에 요구','proposal'),
        m(SDK,CORE,'웹훅 · DEPOSIT_FINALIZED','event'),
        m(CORE,CORE,'eventId로 중복 제거 · SDK에 요구','proposal'),
        m(CORE,CORE,'고객 잔고 인정 시점 확인','proposal'),
        c(CORE,CORE,'고객 SC통화 입금중 감소 · 잔고 증가'),
        c(CORE,CORE,'자산별 입금중 감소 · 잔고 증가'),
        m(CORE,CORE,'사용 허용 · 서비스 통지 시점 확인','proposal'),
        c(CORE,SERVICE,'입금완료 통지'),
        note='CORE 잔고 반영과 서비스 통지는 DAW 업무 유지안이며 적용 시점은 연동 시 정한다. SDK의 FROZEN은 DB 이동 보류이며 블록체인 자산 동결이 아니다. CORE가 SDK에 Finalized 잔고 반영을 요청하고 완료 응답을 받는 대응 API는 확인되지 않는다.',review=True)),
 pair(s(m(CORE,CORE,'입금 FINALIZED 반영 · 완료 확인 성공'),m(CORE,BC,'POST /sweeps · 계정 1..N / sourceEventIds'),m(BC,CORE,'202 · sweepRequestId / 항목 ID','reply'),m(BC,BC,'이번에 집금할 지갑·금액 결정'),m(BC,BC,'컨트랙트의 토큰 이동 권한 확인·필요 시 설정'),m(BC,BC,'전송할 배치와 고객별 내역을 DB에 저장'),m(BC,CHAIN,'운영 계정이 batchSweep(items) 제출'),m(CHAIN,CHAIN,'컨트랙트가 고객별 transferFrom 실행'),m(CHAIN,BC,'배치 확정 · 항목별 이동 증적','event'),m(BC,BC,'항목별 성공 / 실패 · 실제 이동액 대사'),m(BC,QUEUE,'sweep-events · 고객 항목별 결과','event'),m(QUEUE,CORE,'항목별 결과 수신','event'),m(CORE,CORE,'eventId로 중복 방지 · 업무 반영'),m(CORE,BC,'PUT /events/{eventId}/completion'),m(BC,CORE,'처리 완료 확인 응답','reply'),m(CORE,QUEUE,'Kafka offset 커밋'),note='자동스윕의 업무 방향은 원장 설계, 요청·배치 실행·결과 처리는 BCM API·설계에 근거한다. 같은 네트워크·토큰의 여러 고객 vault를 한 컨트랙트 호출로 집금한다. 요청 1건은 여러 배치로 나뉠 수 있고, approve 준비 거래는 별도다. 배치 거래 확정만으로 모든 항목을 성공 처리하지 않는다. 현행 목적지는 컨트랙트에 고정된 옴니버스이며 출금 풀 직접 집금 전환은 미확정이다.'),
      load_sweep()),
],
'suspense':[
 pair(s(m(CORE,CORE,'트래블룰에 없는 입금으로 분류'),note='고객 소유권 편입을 보류한다.'),
      s(m(SDK,CORE,'웹훅 · DEPOSIT_DETECTED / 입금 정보','event'),
        c(CORE,CORE,'확인 정보로 정상 / 미확인 입금 판정'),
        c(CORE,CORE,'미확인 건은 고객가수금 처리 대상으로 분류'),
        note='확인 정보가 부족한 입금의 분류는 CORE 업무로 유지한다. SDK는 모든 확정 입금을 FROZEN으로 기록하므로 이 상태만으로 별단입금을 구분하지 않는다.',review=True)),
 pair(s(m(CHAIN,BC,'입금 BCTX Finalized','event'),m(BC,QUEUE,'Finalized 전달','event'),m(QUEUE,CORE,'Finalized 수신','event'),m(CORE,CORE,'자산별 입금중 감소 · 잔고 증가'),m(CORE,CORE,'별단입금 → 입금완료'),m(CORE,BC,'Finalized 반영 요청'),m(BC,BC,'주소별 입금중 감소'),m(BC,CORE,'반영 완료','reply'),note='고객 SC통화잔고를 늘리는 단계는 없다.'),
      s(m(CHAIN,SDK,'입금 확정','event'),
        m(SDK,SDK,'DB에 이동 보류(FROZEN) 기록'),
        m(SDK,SDK,'전달 보장 · SDK에 요구','proposal'),
        m(SDK,CORE,'웹훅 · DEPOSIT_FINALIZED','event'),
        m(CORE,CORE,'eventId로 중복 제거 · SDK에 요구','proposal'),
        c(CORE,CORE,'자산별 입금중 감소 · 잔고 증가'),
        c(CORE,CORE,'별단입금 → 입금완료'),
        note='SDK 입금 건과 CORE 별단입금을 연결한 뒤 DAW의 별단입금 확정 처리를 유지한다. 고객 SC통화잔고는 늘리지 않는다. SDK에 별도의 Finalized 잔고 반영 요청을 보내는 계약은 확인되지 않는다.',review=True)),
 pair(s(m(TR,CORE,'트래블룰 정보 확인'),m(ADMIN,CORE,'가수금 이동 요청'),m(CORE,CORE,'트래블룰 내역 확인'),note='후속 정보 수신의 구체 인터페이스는 원문에 없다.'),
      s(c(TR,CORE,'기존 경로로 트래블룰 정보 확인'),
        c(ADMIN,CORE,'가수금 이동 요청'),
        c(CORE,CORE,'트래블룰 내역 확인'),
        note='기존 확인 정보·어드민 경로를 유지하는 안이다. DEPOSIT_UPDATED · 트래블룰 매칭 · KYT 연동은 SDK 문서상 아직 구현되지 않았다. 이를 실제 SDK 통지로 그리지 않으며, 기존 수신 경로 유지 여부와 제공 시점을 확인한다.',review=True)),
 pair(s(m(ADMIN,CORE,'가수금 이동 요청'),m(CORE,CORE,'트래블룰 확인'),m(CORE,CORE,'별단입금 → 이동완료'),m(CORE,CORE,'고객 SC통화잔고 증가'),m(CORE,ADMIN,'이동 완료','reply'),note='받는주소로 받은 가수금의 소유권 편입이며 온체인 이동이 아니다.'),
      s(c(ADMIN,CORE,'가수금 이동 요청'),
        c(CORE,CORE,'트래블룰 내역 확인'),
        m(CORE,CORE,'원입금 연결 · 중복 편입 방지','proposal'),
        c(CORE,CORE,'별단입금 → 이동완료'),
        c(CORE,CORE,'고객 SC통화잔고 증가'),
        c(CORE,ADMIN,'이동 완료'),
        note='DAW의 CORE 소유권 편입 업무를 유지한다. 온체인 이동이 아니므로 SDK 호출이 자동으로 생기지 않는다. SDK 집금·해제는 별도 요청이다.',review=True)),
],
'sweep':[
 pair(s(m(CORE,CORE,'확정된 가수금을 집금 대상으로 선정'),note='가수금 상태에서도 집금해 보관한다.'),
      s(m(CORE,SDK,'POST /api/v1/sweeps · depositId / 목적지 vault'),
        m(SDK,SDK,'입금 건 확인 · 집금 기록 생성'),
        m(SDK,CORE,'201 · sweepId','reply'),
        note='Idempotency-Key와 referenceId가 필요하다. 입금 건이 수량을 정하며 정책 평가·서명·전파는 접수 뒤 진행한다. 확인 근거가 없다는 사실만으로 자동 거부된다는 계약은 아니다.')),
 pair(s(m(BC,CHAIN,'받는주소 → 보내는주소 집금'),note='별단입금 소유권 편입과 별개다.'),
      s(m(SDK,SDK,'정책 승인 · 서명 인가 확인'),
        m(SDK,SDK,'FROZEN + 활성 지정 없음 조건부 갱신'),
        m(SDK,CHAIN,'지정 입금 건의 집금 전송'),
        m(SDK,CORE,'웹훅 · SWEEP_INITIATED','event'),
        note='집금 요청·201 응답은 앞 단계에 있다. 전송 제출 시 CORE에 시작을 통지한다. 한 입금에 활성 이동 한 건만 허용하며 정책·서명 절차는 SDK 경로에 묶었다.')),
 pair(s(m(CORE,CORE,'출금 풀 보관 · 별단입금 상태 유지'),note='가수금 집금의 확정 통지 상세는 원문에 없어 추가 화살표를 그리지 않았다.'),
      s(m(CHAIN,SDK,'집금 확정','event'),m(SDK,SDK,'입금 WITHDRAWN · 해제/이동 원장 반영'),m(SDK,CORE,'SWEEP_FINALIZED','event'),m(CORE,CORE,'집금 결과 연결 · 별단입금 유지','proposal'),note='SDK 집금 완료와 CORE의 별단입금 편입 완료는 다르다.',review=True)),
 pair(s(note='이 가수금 집금의 실패 복구·중복 방지 상세는 PDF에 충분히 제시되지 않았다. 임의의 롤백 순서를 만들지 않는다.',review=True),
      s(m(SDK,SDK,'집금 실패 종결'),m(SDK,SDK,'지정 입금 건 FROZEN 복귀'),m(SDK,CORE,'SWEEP_FAILED','event'),m(CORE,CORE,'원입금 확정은 유지 · 집금 실패 처리','proposal'),note='실패가 종결된 뒤 다시 집금할 수 있다. 원입금과 집금은 다른 거래다.',review=True)),
],
'return-before':[
 pair(s(m(ADMIN,CORE,'오입금 확인 · 반환 대상 결정'),note='별도 확인 절차 후 원발신처로 반환한다. 이 화살표는 업무 요청의 요약이다.'),
      s(c(ADMIN,CORE,'오입금 확인 · 반환 대상 결정'),
        m(CORE,ADMIN,'SDK 운영자 반환 절차로 연결','proposal'),
        m(ADMIN,SDK,'반환 입금 건 지정'),
        m(SDK,SDK,'입금 건의 발신처 확인'),
        note='CORE의 반환 대상 확인은 유지하고 SDK 운영자 절차로 넘기는 연동을 검토한다. 파트너사 반환 API로 그리지 않는다. 발신처가 기록되지 않은 입금은 이 전용 반환에 지정할 수 없다.',review=True)),
 pair(s(note='원문은 보내는주소에서 반환하는 흐름이다. 집금 전 받는주소에서 직접 반환하는 요청·승인 시퀀스는 제공되지 않는다.',review=True),
      s(m(ADMIN,SDK,'계정 지갑 반환 요청 · 운영자 경로'),m(SDK,SDK,'승인 정족수 · 요청자/승인자 분리'),m(SDK,SDK,'서명 인가 확인'),note='파트너사 API에는 이 반환 경로가 없다. 승인·정책·서명 시스템을 SDK 경로 안에 묶어 표시했다.')),
 pair(s(note='원문 반환의 출발지는 보내는주소다. 이 단계에 받는주소 직접 반환 화살표를 보충하지 않는다.',review=True),
      s(m(SDK,CHAIN,'AccountWallet → 원입금 발신처 반환'),note='전용 aww- 반환은 WorkspaceVault를 거치지 않는다. 입금 건의 해제를 겸하며 RELEASED를 경유하지 않는다.')),
 pair(s(note='원문은 보내는주소합을 갱신한다. 집금 전 직접 반환을 채택할 경우의 받는주소합 갱신 시퀀스는 별도 설계가 필요하다.',review=True),
      s(m(SDK,CORE,'운영자 반환 결과 연결 · 방식 확인','proposal'),
        m(CORE,CORE,'원입금·반환 ID 연결 / 별단입금 종결','proposal'),
        m(CORE,CORE,'받는주소 출발 기준 잔고 반영','proposal'),
        c(CORE,ADMIN,'송금완료 통지'),
        note='반환 결과 수신 방식과 받는주소 기준 반영은 연동 검토다. 전용 aww-의 결과 계약을 확인해야 하며, 완료 후 어드민 통지는 DAW 업무를 유지한다.',review=True)),
],
'return-after':[
 pair(s(m(CORE,CORE,'출금 풀에 보관 · 별단입금 유지'),note='집금 후에도 별단입금 상태를 유지한다.'),
      s(m(SDK,SDK,'집금된 자금은 WorkspaceVault에 보관'),m(CORE,CORE,'원입금에 대한 별단입금 유지','proposal'),note='보관 위치와 고객 귀속 상태를 각각 관리해야 한다.',review=True)),
 pair(s(m(ADMIN,CORE,'트래블룰내역 추가 요청'),m(CORE,ADMIN,'추가 완료','reply'),m(ADMIN,CORE,'오입금 송금 요청'),m(CORE,BC,'서명된 송금 BCTX 요청'),m(BC,BC,'보내는주소 출발 BCTX 생성·서명'),m(BC,CORE,'서명된 BCTX 반환','reply'),note='오입금 송금 요청부터 서명된 BCTX 반환까지의 흐름이다.'),
      s(c(ADMIN,CORE,'트래블룰내역 추가 요청'),
        c(CORE,ADMIN,'추가 완료'),
        c(ADMIN,CORE,'오입금 송금 요청'),
        m(CORE,CORE,'집금 후 반환에 사용할 SDK 경로 확인','proposal'),
        note='어드민·CORE 요청은 DAW 업무로 유지한다. 전용 aww-는 AccountWallet 출발이며 WorkspaceVault에서 반환하는 전용 흐름은 제공 문서에 없다. CORE에서 SDK로 보낼 API와 승인 방식은 확정되지 않았다.',review=True)),
 pair(s(m(CORE,CORE,'트래블룰 txHash 기록 · 별단입금 송금중'),m(CORE,CORE,'보내는주소합 송금중 증가'),m(CORE,BC,'송금 BCTX 전파 요청'),m(BC,BC,'보내는주소 송금감지 증가'),m(BC,CHAIN,'송금 BCTX 전파'),m(CHAIN,BC,'전파 성공','reply'),m(BC,CORE,'전파 완료','reply'),m(CORE,ADMIN,'송금중','event'),note='전파 성공은 온체인 확정이 아니다.'),
      s(m(CORE,CORE,'선택한 SDK 반환 경로의 거래 연결','proposal'),
        c(CORE,CORE,'트래블룰 txHash 기록 · 별단입금 송금중'),
        c(CORE,CORE,'보내는주소합 송금중 증가'),
        m(CORE,CORE,'SDK 제출 결과와 반영 시점 연결','proposal'),
        c(CORE,ADMIN,'송금중 통지'),
        note='보내는주소에서 반환하는 경로를 채택할 때 유지할 CORE 업무다. SDK 요청·응답·전파 API는 경로 확정 전이므로 그리지 않는다. 귀속·목적지·승인·원입금 연결 계약을 확인해야 한다.',review=True)),
 pair(s(m(CHAIN,BC,'송금 BCTX Finalized','event'),m(BC,QUEUE,'Finalized 전달','event'),m(QUEUE,CORE,'Finalized 수신','event'),m(CORE,CORE,'별단입금 → 송금완료'),m(CORE,CORE,'보내는주소합 송금중 · 잔고 감소'),m(CORE,BC,'Finalized 반영 요청'),m(BC,BC,'주소별 송금중 감소'),m(BC,CORE,'반영 완료','reply'),m(CORE,ADMIN,'송금완료','event'),note='먼저 선택한 반환 경로의 출발 주소 구분을 유지해야 한다.'),
      s(m(SDK,CORE,'선택한 반환 경로의 결과 수신','proposal'),
        m(CORE,CORE,'원입금·집금·반환 연결 / 중복 반환 방지','proposal'),
        c(CORE,CORE,'별단입금 → 송금완료'),
        c(CORE,CORE,'보내는주소합 송금중 · 잔고 감소'),
        c(CORE,ADMIN,'송금완료 통지'),
        note='보내는주소 반환 경로와 SDK 결과 계약을 확인한 뒤 적용할 흐름이다. CORE 원장·어드민 통지는 DAW 업무 유지안이다. SDK로 별도 잔고 반영 요청을 보내는 계약은 확인되지 않는다.',review=True)),
],
'company':[
 pair(s(m(CORE,CORE,'회사 주소의 미등록 입금을 별단입금 기록'),note='회사 가수금 입금 전체의 상세 시퀀스는 제공되지 않았다.'),
      s(m(CHAIN,SDK,'WorkspaceWallet 입금 확정','event'),
        m(SDK,SDK,'DB에 이동 보류(FROZEN) 기록'),
        m(SDK,CORE,'Workspace 입금 정보 연결 · 방식 확인','proposal'),
        c(CORE,CORE,'회사 주소의 미등록 입금을 별단입금 기록'),
        note='회사 별단입금 기록은 CORE 업무로 유지한다. SDK에서 CORE로 입금 정보를 연결하는 선은 연동 필요 지점이며 확정된 웹훅이 아니다. Workspace 통지는 AccountWallet의 DEPOSIT 이벤트와 별도로 확인한다.',review=True)),
 pair(s(m(ADMIN,CORE,'회사 가수금 이동 요청'),m(CORE,CORE,'트래블룰 내역 확인'),note='회사 자산이라는 확인을 전제로 한다.'),
      s(c(ADMIN,CORE,'회사 가수금 이동 요청'),
        c(CORE,CORE,'회사 자기 자산인지 확인'),
        c(CORE,CORE,'트래블룰 내역 확인'),
        note='회사 귀속 확인은 CORE 업무이므로 SDK 호출 없이 유지한다. Workspace 소속만으로 회사 자기 자산이라고 판정하지 않는다. 고객 출금 풀도 WorkspaceVault에 매핑될 수 있다.',review=True)),
 pair(s(m(CORE,CORE,'회사 별단입금 → 이동완료'),m(CORE,CORE,'회사 SC통화잔고 증가'),m(CORE,ADMIN,'이동 완료','reply'),note='회사 소유권을 원장에 반영한다.'),
      s(m(CORE,CORE,'회사 원장 편입과 SDK 해제의 순서 확인','proposal'),
        c(CORE,CORE,'회사 별단입금 → 이동완료'),
        c(CORE,CORE,'회사 SC통화잔고 증가'),
        c(CORE,ADMIN,'이동 완료'),
        note='회사 원장 편입·어드민 응답은 DAW 업무를 유지한다. SDK 독립 해제는 다음 단계이며 순서는 연동 시 정한다. 먼저 편입하면 SDK에서는 아직 사용할 수 없는 회사 잔고가 생길 수 있다.',review=True)),
 pair(s(note='이 PDF에는 모든 회사 확정 입금에 적용하는 SDK 방식의 독립 동결 해제 시퀀스가 없다.',review=True),
      s(m(ADMIN,SDK,'Workspace 입금 건 독립 해제 요청'),
        m(SDK,SDK,'운영자 승인 게이팅'),
        m(SDK,SDK,'건 단위 전액 FROZEN → RELEASED'),
        m(SDK,SDK,'가용 잔액 편입'),
        m(SDK,CORE,'해제 결과 연결 · 방식 확인','proposal'),
        m(CORE,CORE,'SDK 사용 가능 상태 연결','proposal'),
        note='CORE의 회사 귀속 확인과 SDK 사용 승인은 다른 처리다. SDK 내부 해제 후 CORE 결과 연결은 필요 지점만 표시한 미확정 연동이다. 실제 조회·통지 계약을 확인해야 한다.',review=True)),
],
}


ORDER=[SERVICE,ADMIN,TR,CORE,BC,SDK,QUEUE,CHAIN]

def lines(text, width):
    return textwrap.wrap(text,width=width,break_long_words=False,break_on_hyphens=False) or ['']

def svg(sequence, backend, ident):
    if sequence.get('detailed'):
        return detailed_svg(sequence, ident)
    actors=[a for a in ORDER if a in [SERVICE,CORE,backend,CHAIN] or any(a in msg[:2] for msg in sequence['messages'])]
    width=max(680,len(actors)*138)
    xs={a:64+i*(width-128)/(len(actors)-1) for i,a in enumerate(actors)}
    positions=[];y=116
    for a,b,label,kind in sequence['messages']:
        if kind=='core':
            assert SDK not in (a,b) and BC not in (a,b), 'CORE work cannot invent backend calls'
            label='CORE 유지 · '+label
        wrap=lines(label,15 if a==b else max(16,int(abs(xs[a]-xs[b])/12)))
        positions.append((y,a,b,wrap,kind))
        y+=max(82,len(wrap)*18+40)
    height=max(230,y+18)
    color='#126f5e' if backend==BC else '#345d91'
    out=f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" role="img" aria-labelledby="{ident}-title {ident}-desc"><title id="{ident}-title">{esc(backend)} 단계 시퀀스</title><desc id="{ident}-desc">'+esc(' / '.join(f'{a} → {b}: {label}' for a,b,label,k in sequence['messages']) or sequence['note'])+'</desc>'
    out+='<defs>'
    for key,c in [('arrow',color),('review','#947024'),('core','#56616a')]:out+=f'<marker id="{ident}-{key}" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse"><path d="M 0 0 L 10 5 L 0 10 z" fill="{c}"/></marker>'
    out+='</defs>'
    for actor in actors:
        actor_label = sequence.get('backendLabel', backend) if actor==backend else actor
        x=xs[actor]
        out+=f'<line x1="{x}" x2="{x}" y1="62" y2="{height-15}" stroke="#c6d2cc" stroke-dasharray="5 5"/><rect x="{x-57}" y="18" width="114" height="42" rx="4" fill="{color if actor==backend else "#f2f5f3"}" stroke="#d4dfd8"/><text x="{x}" y="44" text-anchor="middle" fill="{"white" if actor==backend else "#35453e"}" font-size="12" font-weight="600">{esc(actor_label)}</text>'
    for num,(yy,a,b,wrapped,kind) in enumerate(positions,1):
        details = ' data-event-details tabindex="0" role="button" aria-haspopup="dialog" aria-controls="event-details-dialog" aria-label="eventId 요구 사유와 발급 규칙 보기"' if backend==SDK and 'eventId' in ''.join(wrapped) else ''
        if backend==SDK and '전달 보장' in ''.join(wrapped):
            details = ' data-queue-details tabindex="0" role="button" aria-haspopup="dialog" aria-controls="event-details-dialog" aria-label="SDK Queue 대응 요구사항 보기"'
        out+=f'<g class="sequence-message" data-from="{esc(a)}" data-to="{esc(b)}" data-kind="{kind}"{details}>'
        x1,x2=xs[a],xs[b];review=kind=='proposal';stroke='#947024' if review else ('#56616a' if kind=='core' else color)
        dash=' stroke-dasharray="6 4"' if kind in ['reply','event','proposal'] else ''
        marker=f' marker-end="url(#{ident}-{"review" if review else "core" if kind=="core" else "arrow"})"'
        if a==b:
            direction=-1 if x1>width-250 else 1
            bend=x1+direction*32
            out+=f'<path d="M {x1} {yy} H {bend} V {yy+23} H {x1}" fill="none" stroke="{stroke}" stroke-width="1.6"{dash}{marker}/>'
            tx=x1-direction*9;anchor='end' if direction==1 else 'start'
            # Put text on the wide side of the lane so rightmost actors stay in view.
            tx=x1-10 if direction==-1 else x1+43;anchor='end' if direction==-1 else 'start'
            texty=yy-2
        else:
            out+=f'<line x1="{x1}" x2="{x2}" y1="{yy+len(wrapped)*18}" y2="{yy+len(wrapped)*18}" stroke="{stroke}" stroke-width="1.6"{dash}{marker}/>'
            tx=(x1+x2)/2;anchor='middle';texty=yy
        for line_i,line in enumerate(wrapped):
            out+=f'<text x="{tx}" y="{texty+line_i*18}" text-anchor="{anchor}" fill="{stroke}" font-size="12" paint-order="stroke" stroke="white" stroke-width="4" stroke-linejoin="round">{esc((str(num)+". " if line_i==0 else "")+line)}</text>'
        out+='</g>'
    if not positions:
        out+=f'<rect x="28" y="95" width="{width-56}" height="82" rx="4" fill="#fcf8ed" stroke="#dfc99c"/><text x="{width/2}" y="130" text-anchor="middle" fill="#765718" font-size="14">상세 시퀀스 미제공 · 확인 필요</text><text x="{width/2}" y="154" text-anchor="middle" fill="#765718" font-size="12">설명에 없는 요청·응답을 추가하지 않았습니다.</text>'
    return out+'</svg>'
