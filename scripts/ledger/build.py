"""Build the offline ledger reader from reviewed editorial data and PDF evidence."""
from pathlib import Path
import hashlib, html, json, re
from bs4 import BeautifulSoup
from content import *

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
OUT = ROOT / 'docs/ledger'
SOURCE = json.loads((HERE/'source.json').read_text())
assert hashlib.sha256((ROOT/SOURCE['pdf']).read_bytes()).hexdigest() == SOURCE['sha256'], 'PDF changed: extract and review before building'
E = lambda x: html.escape(str(x), quote=True)
PAGES = {}
NAV = [('index.html','시작하기'),('architecture.html','전체 구조와 주소'),('data.html','원장과 DB'),('flows.html','업무 흐름'),('reconciliation.html','대사와 자산 총액'),('principles.html','처리 원칙과 확인 사항'),('sources.html','원문 보기')]
TITLES = {1:'DAW아키텍처 원장 관점 v0.1.4',2:'용어',3:'전체 구조',4:'전체 구조 설명',5:'DB구조 설명',6:'SC통화잔고',7:'델타원장',8:'자산별 잔고',9:'별단 입금',10:'블록체인 기타',11:'주소별 잔고',12:'그 외 DB테이블',13:'BCTX 동반 연산 도식',14:'고객 잔고합 대사',15:'고객 잔고합 대사 — 설명',16:'카뱅 잔고 대사',17:'자산 총액',18:'연산별 시퀀스 설명',19:'프로세스 처리 원칙',64:'끝'}
for flow in FLOWS: TITLES[flow['intro']] = flow['title']+' — 개요'
for n,(title,_) in PHASES.items(): TITLES[n]=title
assert set(TITLES)==set(range(1,65))

def a(href,label,modal=False):
    return f'<a href="@@/{E(href)}"'+(f' data-reference-title="{E(label)}" target="_blank" rel="noopener"' if modal else '')+f'>{E(label)}</a>'
def ref(key,label=None): return a('_reference/'+key+'.html',label or TABLES[key][0],True)
def src(n,label=None,step=None):
    return '<a class="source-link" href="@@/_reference/page-%02d.html%s" data-reference-title="%s" target="_blank" rel="noopener">%s</a>'%(n, f'#step-{step}' if step else '', E(f'p.{n} · {TITLES[n]}'),E(label or f'p.{n}'))
def sources(nums): return '<div class="sources">원문 '+''.join(src(n) for n in nums)+'</div>'
def para(s): return '<p>'+E(s)+'</p>'
def bullets(xs): return '<ul>'+''.join('<li>'+E(x)+'</li>' for x in xs)+'</ul>'
def table(headers,rows):
    return '<div class="table-wrap"><table><thead><tr>'+''.join('<th scope="col">'+E(x)+'</th>' for x in headers)+'</tr></thead><tbody>'+''.join('<tr>'+''.join('<td>'+str(x)+'</td>' for x in row)+'</tr>' for row in rows)+'</tbody></table></div>'
def path(items): return '<div class="path">'+'<span class="path-arrow" aria-hidden="true">→</span>'.join('<div class="path-item">'+E(x)+'</div>' for x in items)+'</div>'
def note(s,warn=False):return '<aside class="note'+(' warning' if warn else '')+'">'+s+'</aside>'
def add(route,title,summary,body,nav=None,kind='guide',source_pages=None): PAGES[route]={'title':title,'summary':summary,'body':body,'nav':nav or route,'kind':kind,'sources':source_pages or []}
def flowlinks(items=FLOWS):
    return '<div class="work-list">'+''.join(f'<a class="work-link" href="@@/flows/{f["id"]}.html"><span class="flow-number">{FLOWS.index(f)+1:02}</span><div><strong>{E(f["title"])}</strong><p>{E(f["summary"])}</p></div><span class="arrow">→</span></a>' for f in items)+'</div>'
def refrow(keys):return '<div class="sources">관련 정의 '+''.join(ref(key) for key in keys)+'</div>'

def target_ref(target):
    return 'addresses' if '주소별' in target else 'assets' if '자산별' in target else 'currency' if 'SC통화' in target else 'delta' if ('델타원장' in target or '정산내역' in target) else 'suspense' if '별단' in target else 'travel-rule' if '트래블룰' in target else 'wallet-meta' if '메타' in target else 'bctx'

def steps(n):
    result={}
    for b in SOURCE['pages'][n-1]['blocks']:
        m=re.match(r'^(\d+)\.(?!\d)',b['text'])
        if m and b['box'][1]>20:
            num=int(m.group(1)); assert num not in result,(n,num)
            result[num]={'text':b['text'],'box':b['box']}
    for (page,num),text in CORRECTIONS.items():
        if page==n:result[num]={'text':text,'box':result.get(num,{}).get('box')}
    for (page,num),box in BOX_CORRECTIONS.items():
        if page==n: result[num]['box']=box
    assert sorted(result)==list(range(1,EXPECTED_STEPS[n]+1)),(n,sorted(result),EXPECTED_STEPS[n])
    for num,s in result.items():
        target=TARGETS.get(n,{}).get(num,'')
        s['number']=num;s['target']=target
        s['actor']=ACTORS.get(n,{}).get(num) or ('DAWBC' if '주소별' in target else 'DAW코어' if target else None)
        assert s['actor'],(n,num,'participant not reviewed')
        s['refs']=[(target_ref(label),label) for label in target.split(' · ')] if target else []
    return [result[x] for x in sorted(result)]
STEPS={n:steps(n) for n in PHASES}

for key,(title,owner,nums,description,rows) in TABLES.items():
    body=sources(nums)+f'<p class="eyebrow">{E(owner)}</p>'+para(description)+table(['항목','정의'],[(E(k),E(v)) for k,v in rows])
    body+='<h2>이 정의를 사용하는 업무</h2>'+flowlinks([f for f in FLOWS if key in f['refs']])
    related={'addresses':['currency','assets','delta','bctx'],'delta':['addresses','assets','records'],'assets':['addresses','currency'],'currency':['assets','addresses'],'suspense':['currency','travel-rule'],'wallet-meta':['addresses','currency'],'travel-rule':['suspense','bctx'],'bctx':['addresses','delta'],'records':['delta']}.get(key,[])
    body+='<h2>함께 읽기</h2>'+refrow(related)
    if key=='addresses':body+=note('입금 '+src(31,'Confirmed')+'에는 온체인잔고가 증가한다. '+src(32,'Finalized')+'에는 입금중만 줄인다. SC통화잔고는 Finalized에 증가한다.')
    add('_reference/'+key+'.html',title,description,body,kind='reference',source_pages=nums)
body=sources([2,4,5,6,11,12])+'<dl class="term-list">'+''.join(f'<dt id="term-{i}">{E(term)}</dt><dd>{E(desc)} {src(p)}</dd>' for i,(term,desc,p) in enumerate(TERMS))+'</dl>'+refrow(['currency','assets','addresses','bctx'])
add('_reference/glossary.html','용어','SC통화·SC자산·원장·BCTX·스윕의 의미를 확인한다.',body,kind='reference',source_pages=[2,4,5,6,11,12])

body='<div class="hero"><p class="eyebrow">DAW ARCHITECTURE / LEDGER</p><h1>원장과 자금 흐름</h1><p class="lede">소유권 원장, 자산별 잔고, 주소별 잔고가 어떻게 연결되는지 살펴봅니다. 업무별 처리 순서와 잔고 변화를 함께 읽을 수 있습니다.</p>'+sources([1,3,18])+'</div>'
body+='<div class="home-grid"><section><div class="section-head"><h2>먼저 읽을 내용</h2>'+ref('glossary','용어 찾기')+'</div><ol class="reading-list">'
for i,(href,title,desc) in enumerate([('architecture.html','세 가지 잔고의 관계','SC통화 소유권, SC자산 총량, 온체인잔고의 역할을 구분합니다.'),('flows/deposit.html','입금이 잔고가 되는 과정','사전 통보부터 감지·확정까지, 어느 잔고가 언제 바뀌는지 확인합니다.'),('flows/settlement.html','정산이 끝나는 시점','개별 BCTX 확정과 정산TID 전체 완료를 나누어 읽습니다.')],1):
    body+=f'<li><a href="@@/{href}"><span class="number">0{i}</span><div><strong>{title}</strong><p>{desc}</p></div><span class="arrow">→</span></a></li>'
body+='</ol></section><aside class="side-summary"><h3>이 문서의 기준</h3><dl><div><dt>설계자 문서</dt><dd>원장 v0.1.4 · 64쪽</dd></div><div><dt>업무 범위</dt><dd>계좌 생성부터 가수금까지 13개</dd></div><div><dt>참고 화면</dt><dd>정의와 원문을 모달로 확인</dd></div><div><dt>읽을 때 구분할 내용</dt><dd>설계 내용과 현재 BCM 구현 상태</dd></div></dl></aside></div>'
body+='<h2>업무에서 시작하기</h2>'+flowlinks([FLOWS[i] for i in [3,2,7,8,4,11]])
body+=note('본문은 설계자 원장 v0.1.4를 재구성했습니다. 원문의 DAWBC 명칭을 유지하며, 기존 논의에서 BCM이 맡기로 한 범위와 현재 구현 완료 여부는 구분합니다. '+a('principles.html','처리 원칙과 확인 사항'))
add('index.html','원장과 자금 흐름','원장 v0.1.4의 업무 흐름과 DB 설계 안내.',body,source_pages=[1,2,18])

body='<h2 id="layers">세 가지 잔고</h2><div class="layer-map">'
for label,title,desc,key in [('소유권','SC통화잔고','고객별·회사별로 네트워크를 구분하지 않는 SC통화 소유권을 기록한다.','currency'),('자산 총량','SC자산별 잔고','ETH-USDC처럼 네트워크별 자산을 주소 구분에 따라 합산한다.','assets'),('블록체인','주소별 잔고','관리하는 각 주소의 온체인잔고와 처리 중인 이동을 기록한다.','addresses')]:
    body+=f'<div class="layer-label">{label}</div><div class="layer-content"><strong>{ref(key,title)}</strong><p>{desc}</p></div>'
body+='</div>'+sources([3,4])+'<h2 id="systems">시스템 역할</h2>'+table(['구성 요소','역할'],[(E(k),E(v)) for k,v in [('서비스','고객과 직접 상호작용하며 DAW코어를 호출하는 상위 레이어.'),('DAW코어','원장 기록·관리, 업무 로직·정책, 계정계·대외계 연동.'),('DAWBC','블록체인 네트워크 처리와 관리 주소별 잔고.'),('큐','DAWBC가 감지한 블록체인 이벤트를 DAW코어에 전달.'),('계정계·대외계','계좌이체·수신·회계, 트래블룰 연동.'),('어드민','수동·교환·브릿징스윕 준비와 가수금 처리 등에 등장.')]])+sources([4,42,48,49,59])
body+='<h2 id="customer-addresses">고객 주소의 자금 경로</h2>'+path(['받는주소','보내는주소','콜드월렛'])+para('받는주소 → 보내는주소, 보내는주소 → 콜드월렛은 자동스윕이다. 콜드월렛 → 보내는주소는 수동스윕이다. 교환스윕과 브릿징스윕은 보내는주소와 콜드월렛 사이의 두 자산을 교환한다.')+sources([2,13,46])+refrow(['addresses','assets'])
body+='<h2 id="company-addresses">회사 주소의 구분</h2>'+table(['주소','원문의 용도'],[(E(k),E(v)) for k,v in [('고객거래주소','고객과의 거래, 발행·소각. whitelist 출금 제한. 대부분의 핫 잔액 보유.'),('외부입출금주소','불특정 외부주소와 입출금. blacklist 출금 제한. 평소 잔액은 거의 없음.'),('콜드월렛','많은 자산을 보관.')]])+sources([8,13])
body+='<h2 id="calls">요청과 이벤트</h2>'+path(['서비스','DAW코어','DAWBC'])+para('p.4는 이 방향의 호출을 동기 처리로 설명한다.')+path(['네트워크','DAWBC','큐','DAW코어'])+para('블록체인 이벤트는 DAWBC가 감지해 큐로 전달한다. DAW코어는 원장을 갱신하고 DAWBC에 반영을 요청한다.')+sources([4,31,32])+note('화살표는 업무 경로를 나타낸다. 물리 vault 수나 현재 BCM API·토픽 명세를 정의하는 그림은 아니다.')
add('architecture.html','전체 구조와 주소','소유권·자산 총량·블록체인 기록의 역할과 고객·회사 주소의 관계.',body,source_pages=[3,4,13])

body='<h2>잔고의 반영 시점</h2>'+table(['계층','입금중','송금중','잔고 기준'],[(ref('currency'),'Confirmed','Broadcasting + Confirmed','Finalized'),(ref('assets'),'p.6과 동일','p.6과 동일','구분별 확정 잔고'),(ref('addresses'),'Confirmed','Confirmed','온체인잔고는 Confirmed 이상')])+sources([6,8,11])+note('주소별 입금감지·송금감지는 Broadcasting 상태다. 같은 “송금중”이라는 이름이어도 SC통화잔고와 주소별 잔고가 포함하는 상태는 다르다.')
body+='<h2>정의 찾아보기</h2><div class="definition-grid">'
for key,(title,owner,nums,desc,rows) in TABLES.items():body+='<div class="definition-item">'+ref(key)+f'<p>{E(desc)}</p><small>{E(owner)} · 원문 '+', '.join(f'p.{n}' for n in nums)+'</small></div>'
body+='</div><h2>상태와 이력을 함께 읽기</h2>'+table(['기록','작성 시점과 역할'],[(ref('records','상태 테이블'),'업무 처리에 따라 현재 잔고·메타를 변경한다.'),(ref('records','거래내역·정산내역'),'상태 변경 시 해당 내역을 함께 기록한다.'),(ref('records','일일스냅샷'),'하루에 한 번 상태 테이블의 스냅샷을 남긴다.')])+para('현재 상태를 변경할 때 내역도 기록한다. 원문 시퀀스에서 반복적인 거래내역 기록은 생략되어 있다. 정산내역 Type 업데이트는 append-only 원칙의 명시된 예외다.')+sources([5,7])+refrow(['records','delta'])
add('data.html','원장과 DB','테이블의 역할, 식별자, 필드 의미와 잔고 반영 시점을 확인합니다.',body,source_pages=list(range(5,13)))
body=para('업무별로 시작 조건, 처리 단계, 변경되는 잔고를 묶었습니다. 상세 도식이 없는 단계는 원문 개요에 나온 범위까지만 설명합니다.')+flowlinks()+sources([18])
add('flows.html','업무 흐름','13개 업무의 처리 순서와 원장 갱신 관계.',body,source_pages=[18])

for f in FLOWS:
    overview=path(f['stages'])
    if f['id']=='sweep':
        overview='<div class="variant-map">'+''.join('<div class="path-item">'+E(x)+'</div>' for x in ['자동: 받는 → 보내는 / 보내는 → 콜드','수동: 콜드 → 보내는','교환: 보내는 ↔ 콜드'])+'</div>'
    elif f['id']=='customer-suspense':
        overview='<div class="branch-map"><div class="path-item">가수금 입금 확정</div><div class="branch-options"><div class="path-item">트래블룰 확인 → 고객 자산 편입</div><div class="path-item">오입금 확인 → 송금 시작·확정</div></div></div>'
    body='<p class="eyebrow">시작 조건</p>'+para(f['trigger'])+overview+sources([f['intro']]+f['pages'])
    for text in f['notes']:body+=para(text)
    body+=refrow(f['refs'])
    if f['id']=='ramp':
        body+='<details class="full-source"><summary>거래 가능 잔고 산식</summary><h3>온램프 · 회사</h3><pre class="formula">거래가능잔고 = 고객거래주소합.잔고\n+ SUM(델타원장.filter(TO=KB, 상태=미정산|정산중).수량)\n− SUM(델타원장.filter(TO=USER, 상태=미정산|정산중).수량)</pre><h3>오프램프 · 고객</h3><pre class="formula">고객거래가능잔고 = 보내는주소합.잔고\n+ SUM(델타원장.filter(TO=USER, 상태=미정산|정산중).수량)\n− SUM(델타원장.filter(TO=KB, 상태=미정산|정산중).수량)</pre>'+sources([23,24])+'</details>'
    body+='<nav class="on-page" aria-label="이 업무의 단계">'+''.join(f'<a href="#phase-{n}">{E(PHASES[n][0])}</a>' for n in f['pages'])+'</nav>'
    for i,n in enumerate(f['pages'],1):
        title,desc=PHASES[n];items=STEPS[n]
        body+=f'<section class="phase" id="phase-{n}"><div class="phase-head"><h2><span class="phase-number">{i:02}</span>{E(title)}</h2>{src(n,"원문 보기")}</div><p class="phase-summary">{E(desc)}</p>'
        if n==52:body+=note('p.11·50의 전체 완료 조건을 함께 읽어야 한다. 아래는 p.52의 순번을 보존한 것으로, 모든 단계를 개별 BCTX 확정마다 실행한다는 뜻이 아니다. '+a('principles.html#settlement-gate','확인 사항'),True)
        nodes=['서비스','DAW코어','DAWBC','큐','네트워크','계정계','대외계','어드민']
        present=[x for x in nodes if any(x in step['actor'] for step in items)]
        body+='<div class="stage-map" aria-label="단계별 참여 시스템">'+''.join(f'<span class="stage-node" data-node="{x}">{x}</span>' for x in present)+'</div><p class="stage-selected" aria-live="polite">처리 순서를 펼치면 해당 시스템과 변경 내용을 강조합니다.</p>'
        body+='<div class="phase-columns"><div><div class="phase-tools"><h3>처리 순서 <span class="subtle">· 원문 순번</span></h3><button class="text-button" data-expand-phase>모두 펼치기</button></div><ol class="steps">'
        mutations=[]
        for step in items:
            num=step['number'];text=re.sub(r'^\d+\.\s*','',step['text']);lines=text.splitlines();label=' '.join(lines) if not step['target'] else lines[0]
            target=step['target']
            if target and label in ['업데이트','새로운 행 추가','확인']:label=target+' '+('기록' if label=='새로운 행 추가' else label)
            definition_links=''.join(ref(key,'관련 정의' if len(step['refs'])==1 else label) for key,label in step['refs'])
            body+=f'<li class="step" id="step-{n}-{num}" data-actor="{E(step["actor"])}" data-step="{num}"><details><summary><span class="step-num">{num}</span><span class="step-actor">{E(step["actor"])}</span><span class="step-title">{E(label)}</span></summary><div class="step-body"><p>{E(text)}</p><div class="source-links">{src(n,"원문 단계 보기",num)}'+definition_links+'</div></div></details></li>'
            if target:
                detail=E('\n'.join(lines[1:]).strip() or lines[0])
                detail=detail.replace('증가','<span class="delta-up">증가</span>').replace('감소','<span class="delta-down">감소</span>')
                target_links=' · '.join(ref(key,label) for key,label in step['refs'])
                mutations.append(f'<div class="balance-entry" data-mutation="{num}"><span class="target"><small>{num:02}</small>{target_links}</span><p>{detail}</p></div>')
        body+='</ol></div><aside class="balance-panel"><h3>잔고·기록 변경</h3>'+(''.join(mutations) if mutations else para('이 단계에는 별도의 잔고 테이블 갱신이 표시되어 있지 않습니다. 원문 요청·응답을 확인하세요.'))+'</aside></div></section>'
    body+='<h2>원문 개요</h2>'
    if f['intro'] in IMAGE_NOTES:body+='<details class="full-source"><summary>개요 설명 펼치기 · '+f'p.{f["intro"]}'+'</summary><p class="source-note">'+E(IMAGE_NOTES[f['intro']])+'</p>'+sources([f['intro']])+'</details>'
    else:body+=sources([f['intro']])
    body+='<h2>함께 읽을 업무</h2>'+flowlinks([next(x for x in FLOWS if x['id']==key) for key in RELATED_FLOWS[f['id']]])+a('flows.html','전체 업무 보기 →')
    add('flows/'+f['id']+'.html',f['title'],f['summary'],body,nav='flows.html',source_pages=[f['intro']]+f['pages'])

body=para('SC통화, SC자산, 주소별 잔고를 같은 기준으로 맞추기 위해 처리 중 항목을 보정한다. 아래 식은 원문의 필드명과 부호를 유지했다.')+refrow(['currency','assets','addresses','delta','suspense'])
for n,title in [(14,'고객 잔고합 대사'),(16,'카뱅 잔고 대사')]:
    raw=SOURCE['pages'][n-1]['text'];formula=raw[raw.index('● 다음'):].replace('Strictly Confidential','').strip()
    body+='<h2 id="formula-'+str(n)+'">'+title+'</h2>'+sources([n])+'<pre class="formula">'+E(formula)+'</pre>'
    if n==16:body+=note('p.16의 “[카뱅]고객별SC통화잔고” 표기를 그대로 유지했다. p.6의 “[카뱅]SC통화잔고”와 명칭이 다르다. '+a('principles.html#company-name','확인 사항'))
body+='<h2 id="equations">온체인잔고를 확정 잔고로 환산하기</h2><pre class="formula">통잔(SC통화 잔고) = f입금 − f출금\n자잔(SC자산 잔고) = f입금 − f출금\n온잔(온체인 잔고) = c입금 + f입금 − c출금 − f출금\n환잔(온체인 환산 잔고) = 온잔 − c입금 + c출금\n\n통잔 = 자잔 = 환잔</pre>'+sources([15])+para('위 식의 c는 아직 Confirmed인 금액, f는 Finalized된 금액으로 읽는다. 별단입금·델타원장·크로스브릿징 등이 있으면 p.14~16의 보정 항목을 함께 적용한다.')
body+='<h3>크로스브릿징 상태 예시</h3>'+para('p.15는 고객·회사 상태 변화가 아토믹이고 크로스브릿징 입금·송금 수량이 같다는 전제를 둔다.')
body+=table(['고객 상태','카뱅 상태','SC자산','BC'],[(E(a),E(b),E(c),E(d)) for a,b,c,d in [
('A 입금감지 / B 송금감지','A 송금감지 / B 입금감지','자잔 + 크입중 − 크송중','환잔 + 크입감지 − 크송감지'),
('A 입금중 / B 송금감지','A 송금중 / B 입금감지','자잔 + 크입중 − 크송중','환잔 + 크입중 − 크송감지'),
('A 입금확정 / B 송금감지','A 송금확정 / B 입금감지','자잔 (+반영된 크입) − 크송중','환잔 (+반영된 크입) − 크송감지'),
('A 입금확정 / B 송금중','A 송금확정 / B 입금중','자잔 (+반영된 크입) − 크송중','환잔 (+반영된 크입) − 크송중'),
('A 입금확정 / B 송금확정','A 송금확정 / B 입금확정','자잔 (+반영된 크입 − 반영된 크송)','환잔 (+반영된 크입 − 반영된 크송)')]])+para('각 행의 SC통화 열은 “통잔”이다. 크입·크송은 크로스브릿징 입금·송금이다.')+sources([15])
body+='<details class="full-source"><summary>대사 등식과 원문 설명 전체</summary><pre class="source-text">'+E(SOURCE['pages'][14]['text'])+'</pre>'+src(15,'원문 표 보기')+'</details>'
body+='<h2 id="totals">자산 총액</h2><pre class="formula">'+E(SOURCE['pages'][16]['text'].replace('Strictly Confidential','').strip())+'</pre>'+sources([17])+note('원문의 SUM은 자료형이나 계산 코드를 정의한 것이 아니다. 대사 단위와 기준 시점은 '+a('principles.html#units','확인 사항')+'에 기록했다.')
add('reconciliation.html','대사와 자산 총액','세 가지 잔고를 대조하는 식과 처리 중 금액의 보정 항목.',body,source_pages=[14,15,16,17])

body='<h2>요청은 동기 호출, 블록체인 이벤트는 큐로 전달</h2>'+path(['서비스','DAW코어','DAWBC'])+para('p.4는 요청 호출을 모두 sync로 처리한다고 설명한다. 반대 방향의 블록체인 이벤트는 DAWBC가 감지해 큐로 전달하고, DAW코어가 받아 다시 sync로 처리한다.')+sources([4])
body+='<h2>롤백이 어려운 처리를 뒤에 배치</h2>'+para('p.19는 DAW코어 기준으로 롤백이 더 어려운 처리를 나중에 수행한다고 설명한다. 아래는 원문에 적힌 “롤백이 힘든 순”이다.')+table(['난이도 순','처리','원문 설명'],[(E(a),E(b),E(c)) for a,b,c in [('1','DAWBC의 BCTX 전파','회사 내부 BCTX는 역거래 가능, 외부 송금은 역거래가 매우 어려움.'),('2','계정계 처리','구체 복구 절차는 이 페이지에 없음.'),('3','DAWBC의 DB 수정','DAWBC 처리 내역을 모두 롤백한 후 실패 리턴.'),('4','DAW코어의 DB 수정','DAW코어 처리 내역을 모두 롤백한 후 실패 리턴.')]])+sources([19])
body+='<h2>원문에서 확인이 필요한 부분</h2>'+para('아래는 문서를 재구성하며 발견한 확인 사항이다. 설계 본문을 수정하거나 현재 구현 여부를 판정한 결과는 아니다.')
for key,title,nums,desc in ISSUES:body+=f'<section id="{key}"><h3>{E(title)}</h3>{para(desc)}{sources(nums)}</section>'
add('principles.html','처리 원칙과 확인 사항','동기 호출·큐·반영 완료와 원문에 남아 있는 상세 조건을 구분합니다.',body,source_pages=[4,19])

body=para('페이지를 선택하면 이 문서 안에서 원문을 확인할 수 있습니다. 표지와 장 구분 페이지를 포함한 64쪽 전체입니다.')+'<label class="subtle" for="page-filter">페이지 번호 또는 제목</label><br><input class="gallery-filter" id="page-filter" type="search" placeholder="예: 31, 입금, 델타" autocomplete="off"><p class="subtle" id="page-count" aria-live="polite">64쪽</p><div class="source-gallery">'
for n in range(1,65):body+=f'<a class="source-card" href="@@/_reference/page-{n:02}.html" data-reference-title="{E(f"p.{n} · {TITLES[n]}")}" target="_blank" rel="noopener" data-page-label="{E(str(n)+" "+TITLES[n])}"><img loading="lazy" src="@@/assets/page-{n:02}.webp" width="2160" height="1215" alt="원문 {n}쪽 미리보기"><span><small>{n:02}</small>{E(TITLES[n])}</span></a>'
body+='</div>'
add('sources.html','원문 보기','원장 v0.1.4 · 전체 64쪽',body,source_pages=list(range(1,65)))

for n in range(1,65):
    page=SOURCE['pages'][n-1]
    body=f'<div class="figure-toolbar"><span>원문 {n} / 64</span><button data-zoom="-" aria-label="원문 축소">−</button><output id="zoom-level">100%</output><button data-zoom="+" aria-label="원문 확대">＋</button><button data-zoom="fit">화면 맞춤</button><a href="@@/../{E(Path(SOURCE["pdf"]).name)}#page={n}" target="_blank" rel="noopener">PDF 열기 ↗</a></div>'
    body+='<figure class="source-figure"><div class="figure-viewport" tabindex="0" aria-label="원문 확대 영역"><div class="figure-inner">'+f'<img src="@@/{page["image"]}" width="2160" height="1215" alt="{E(TITLES[n])} — 원문 {n}쪽">'
    for step in STEPS.get(n,[]):
        if step['box']:
            x,y,x2,y2=step['box'];body+=f'<span class="source-mark" id="step-{step["number"]}" data-source-step="{step["number"]}" style="left:{max(0,x-3)/720*100:.3f}%;top:{max(0,y-3)/405*100:.3f}%;width:{(x2-x+6)/720*100:.3f}%;height:{(y2-y+6)/405*100:.3f}%"></span>'
        else:
            body+=f'<span id="step-{step["number"]}"></span>'
    body+='</div></div><figcaption>원장 v0.1.4 · PDF '+str(n)+'쪽 · Strictly Confidential</figcaption></figure>'
    related=[f for f in FLOWS if n in f['pages']+[f['intro']]]
    if related:body+='<div class="sources">업무 설명 '+a('flows/'+related[0]['id']+'.html',related[0]['title'])+'</div>'
    body+='<details class="full-source"><summary>추출한 본문 텍스트</summary><pre class="source-text">'+E(page['text'])+'</pre></details>'
    note_n=n if n in IMAGE_NOTES else NOTE_PAGE_MAP.get(n)
    if note_n:
        body+=f'<details class="full-source"><summary>이미지에 포함된 개요 설명 · p.{note_n}</summary><p class="source-note">{E(IMAGE_NOTES[note_n])}</p>'+('' if n==note_n else src(note_n,'장 개요 원문'))+'</details>'
    body+='<nav class="source-pagination" aria-label="원문 페이지 이동">'+(src(n-1,'← 이전 페이지') if n>1 else '<span></span>')+(src(n+1,'다음 페이지 →') if n<64 else '<span></span>')+'</nav>'
    add(f'_reference/page-{n:02}.html',f'p.{n} · {TITLES[n]}','원장 v0.1.4의 원문 페이지',body,kind='reference',source_pages=[n])

search=[]
for route,page in PAGES.items():
    soup=BeautifulSoup(page['body'],'html.parser');text=soup.get_text(' ',strip=True)
    search.append({'path':route,'title':page['title'],'kind':'원문' if route.startswith('_reference/page-') else '정의' if page['kind']=='reference' else '업무' if route.startswith('flows/') else '안내','text':text})
    if route.startswith('flows/'):
        for section in soup.select('section.phase'):
            search.append({'path':route+'#'+section['id'],'title':page['title']+' · '+section.h2.get_text(' ',strip=True),'kind':'업무 단계','text':section.get_text(' ',strip=True)})
script=(HERE/'reader.js').read_text()
OUT.mkdir(parents=True,exist_ok=True);(OUT/'assets/style.css').write_text((HERE/'style.css').read_text())

def shell(route,page):
    prefix='../' if '/' in route else ''
    isref=page['kind']=='reference'
    root='../' if '/' in route else './'
    active=page['nav']
    sidebar='<aside class="sidebar" id="sidebar"><p class="sidebar-label">원장 v0.1.4</p><nav aria-label="주 메뉴">'+''.join(f'<a href="@@/{href}"'+(' aria-current="page"' if href==active else '')+f'><span class="nav-num">{i:02}</span>{title}</a>' for i,(href,title) in enumerate(NAV,1))+'</nav><div class="sidebar-footer">DAW 아키텍처<br>설계자 원장 · 64쪽<br>'+ref('glossary','용어 찾기 ↗')+'</div></aside>'
    header='<header class="site-header"><a class="brand" href="@@/index.html"><span class="brand-symbol">L</span><strong>DAW 원장</strong><small>v0.1.4</small></a><div class="header-right"><span class="classification">Strictly Confidential</span>'+('<a href="@@/index.html">문서 홈</a>' if isref else '<button class="search-open" aria-haspopup="dialog">문서 검색 <kbd>⌘ K</kbd></button><button class="menu-toggle" aria-controls="sidebar" aria-expanded="false">목차</button>')+'</div></header>'
    heading='' if route=='index.html' else '<div class="page-heading">'+('<div class="breadcrumb">'+a('flows.html','업무 흐름')+'<span>/</span>'+E(page['title'])+'</div>' if route.startswith('flows/') else '<p class="eyebrow">DAW / 원장 v0.1.4</p>')+'<h1>'+E(page['title'])+'</h1><p class="lede">'+E(page['summary'])+'</p></div>'
    dialogs='''<dialog id="reference-dialog" aria-labelledby="reference-title"><div class="reference-toolbar"><h2 id="reference-title">참고 문서</h2><button data-reference-back disabled>← 뒤로</button><a data-reference-window href="#" target="_blank" rel="noopener">새 창 ↗</a><button data-reference-close aria-label="참고 문서 닫기">닫기 ×</button></div><p class="reference-loading" role="status">문서를 여는 중입니다.</p><iframe id="reference-frame" title="참고 문서" src="about:blank"></iframe></dialog>
<dialog id="search-dialog" aria-labelledby="search-label"><div class="search-head"><label id="search-label" for="search-input" hidden>문서 검색</label><input id="search-input" type="search" placeholder="업무, 테이블, 필드명 검색" autocomplete="off"><button data-search-close>닫기</button></div><p id="search-status" role="status"></p><ul id="search-results"></ul></dialog>'''
    index='<script type="application/json" id="search-index">'+json.dumps(search,ensure_ascii=False).replace('<','\\u003c')+'</script>'
    body=header+('' if isref else sidebar)+'<main id="main">'+heading+page['body']+'<footer class="site-footer"><span>DAW 아키텍처 · 원장 v0.1.4</span><span>Strictly Confidential</span></footer></main>'+('' if isref else dialogs+index)
    early="document.documentElement.classList.add('js');if(new URLSearchParams(location.search).get('view')==='modal')document.documentElement.classList.add('embed');"
    result='<!doctype html>\n<html lang="ko"'+(' class="reference-page"' if isref else '')+'><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><meta name="color-scheme" content="light"><title>'+E(page['title'])+' | DAW 원장 v0.1.4</title><link rel="stylesheet" href="@@/assets/style.css"><script>'+early+'</script></head><body'+(' class="reference-page"' if isref else '')+'><a class="skip" href="#main">본문으로</a>'+body+f'<script data-root="{root}">'+script+'</script></body></html>\n'
    return result.replace('@@/',prefix)

for route,page in PAGES.items():
    dest=OUT/route;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_text(shell(route,page))
manifest={'version':VERSION,'pdf':SOURCE['pdf'],'pdfSha256':SOURCE['sha256'],'pdfPages':64,'flows':len(FLOWS),'flowPhases':len(PHASES),'numberedSteps':sum(len(x) for x in STEPS.values()),'definitions':len(TABLES)+1,'htmlPages':len(PAGES),'pages':[{'path':route,'title':p['title'],'kind':p['kind'],'sourcePages':p['sources']} for route,p in PAGES.items()],'coverage':[{'page':n,'title':TITLES[n],'reader':f'_reference/page-{n:02}.html','guides':[route for route,p in PAGES.items() if p['kind']=='guide' and n in p['sources']],'rasterNoteSource':n if n in IMAGE_NOTES else NOTE_PAGE_MAP.get(n)} for n in range(1,65)],'extractionCorrections':[{'page':p,'step':s} for p,s in CORRECTIONS],'files':{str(p.relative_to(OUT)):hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(OUT.rglob('*')) if p.is_file() and p.name not in ['manifest.json','PLAN.md']}}
(OUT/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
print(json.dumps({k:manifest[k] for k in ['htmlPages','flows','flowPhases','numberedSteps','definitions','pdfPages']},ensure_ascii=False))
