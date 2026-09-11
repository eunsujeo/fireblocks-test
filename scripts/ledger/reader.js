// Included inline in the generated HTML. All navigation stays within the local reader.
(() => {
  const root = new URL(document.currentScript.dataset.root, location.href);
  const embedded = document.documentElement.classList.contains('embed');
  const refPage = document.documentElement.classList.contains('reference-page');
  const modal = document.getElementById('reference-dialog');
  const search = document.getElementById('search-dialog');
  const send = data => parent.postMessage({kind:'ledger-reference-v1', ...data}, '*');
  function reference(raw) {
    const u = new URL(raw, location.href);
    if (!u.href.startsWith(new URL('_reference/', root).href) || !u.pathname.endsWith('.html')) return null;
    u.searchParams.delete('view');
    return u;
  }
  let zoom = 100;
  const inner = document.querySelector('.figure-inner');
  const viewport = document.querySelector('.figure-viewport');
  function setZoom(value) {
    if (!inner) return;
    zoom = Math.min(400, Math.max(100, value));
    inner.style.width = zoom + '%';
    document.getElementById('zoom-level').value = zoom + '%';
  }
  document.querySelectorAll('[data-zoom]').forEach(button => button.addEventListener('click', () => {
    const action = button.dataset.zoom;
    const ratioX = viewport.scrollWidth ? (viewport.scrollLeft + viewport.clientWidth / 2) / viewport.scrollWidth : .5;
    const ratioY = viewport.scrollHeight ? (viewport.scrollTop + viewport.clientHeight / 2) / viewport.scrollHeight : .5;
    setZoom(action === 'fit' ? 100 : zoom + (action === '+' ? 50 : -50));
    if(action==='fit')document.querySelectorAll('.source-mark').forEach(x=>x.classList.remove('current'));
    viewport.scrollLeft = ratioX * viewport.scrollWidth - viewport.clientWidth / 2;
    viewport.scrollTop = ratioY * viewport.scrollHeight - viewport.clientHeight / 2;
  }));
  function markSource() {
    if (!inner) return;
    const id = location.hash.slice(1);
    const mark = document.getElementById(id);
    document.querySelectorAll('.source-mark').forEach(x => x.classList.remove('current'));
    if (!mark?.classList.contains('source-mark')) return;
    mark.classList.add('current');setZoom(200);
    requestAnimationFrame(() => {
      viewport.scrollLeft = mark.offsetLeft + mark.offsetWidth / 2 - viewport.clientWidth / 2;
      viewport.scrollTop = mark.offsetTop + mark.offsetHeight / 2 - viewport.clientHeight / 2;
    });
  }
  function snapshot() {
    return {top:scrollY, details:[...document.querySelectorAll('details')].map(x=>x.open), zoom,
      markedId:document.querySelector('.source-mark.current')?.id || null,
      left:viewport?.scrollLeft || 0, insideTop:viewport?.scrollTop || 0};
  }
  function restore(state) {
    if (!state) return;
    document.querySelectorAll('details').forEach((x,i)=>x.open=Boolean(state.details?.[i]));
    setZoom(state.zoom || 100);
    document.querySelectorAll('.source-mark').forEach(x=>x.classList.toggle('current',x.id===state.markedId));
    requestAnimationFrame(() => {
      if (viewport) {viewport.scrollLeft=state.left || 0;viewport.scrollTop=state.insideTop || 0}
      scrollTo(0,state.top || 0);
    });
  }
  if (embedded) {
    document.addEventListener('click', event => {
      const link=event.target.closest('a[href]');
      if (!link || event.defaultPrevented || event.button!==0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) return;
      const url=reference(link.href);
      if (url) {
        event.preventDefault();
        send({action:'navigate',url:url.href,title:link.dataset.referenceTitle || link.textContent,state:snapshot()});
      } else if (link.target!=='_blank' && link.href.startsWith(root.href) && !link.getAttribute('href').startsWith('#')) {
        event.preventDefault();window.open(link.href,'_blank','noopener');
      }
    });
    document.addEventListener('keydown',event=>{if(event.key==='Escape'){event.preventDefault();send({action:'close'})}});
    addEventListener('message',event=>{
      if(event.source===parent && event.data?.kind==='ledger-parent-v1' && event.data.action==='restore') restore(event.data.state);
    });
  }
  async function ready() {
    await document.fonts.ready;
    await Promise.all([...document.images].filter(img=>img.loading!=='lazy').map(img=>img.complete?Promise.resolve():new Promise(resolve=>{img.addEventListener('load',resolve,{once:true});img.addEventListener('error',resolve,{once:true})})));
    markSource();
    document.documentElement.dataset.readerReady='true';
    if(embedded)send({action:'ready',url:location.href,title:document.title.split(' | ')[0]});
  }
  if(document.readyState==='complete')ready();else addEventListener('load',ready,{once:true});
  addEventListener('hashchange',markSource);

  if(modal){
    const frame=document.getElementById('reference-frame');
    const title=document.getElementById('reference-title');
    const back=modal.querySelector('[data-reference-back]');
    const separate=modal.querySelector('[data-reference-window]');
    const close=modal.querySelector('[data-reference-close]');
    const loading=modal.querySelector('.reference-loading');
    let history=[],opener=null;
    function show(entry){
      title.textContent=entry.title;separate.href=entry.url;back.disabled=history.length<2;loading.hidden=false;
      frame.title=entry.title;const u=new URL(entry.url);u.searchParams.set('view','modal');frame.src=u.href;
    }
    function open(raw,label,trigger){
      const url=reference(raw);if(!url)return;
      opener=search?.contains(trigger)?document.querySelector('.search-open'):trigger;
      if(search?.open)search.close();
      history=[{url:url.href,title:label,state:null}];
      if(!modal.open)modal.showModal();
      document.body.classList.add('modal-open');show(history[0]);close.focus();
    }
    modal.addEventListener('close',()=>{
      frame.src='about:blank';document.body.classList.remove('modal-open');history=[];
      if(opener?.isConnected)opener.focus({preventScroll:true});
    });
    close.addEventListener('click',()=>modal.close());
    back.addEventListener('click',()=>{if(history.length>1){history.pop();show(history[history.length-1])}});
    modal.addEventListener('click',event=>{
      if(event.target!==modal)return;const b=modal.getBoundingClientRect();
      if(event.clientX<b.left || event.clientX>b.right || event.clientY<b.top || event.clientY>b.bottom)modal.close();
    });
    document.addEventListener('click',event=>{
      const link=event.target.closest('a[data-reference-title]');
      if(!link || event.defaultPrevented || event.button!==0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)return;
      if(!reference(link.href))return;event.preventDefault();open(link.href,link.dataset.referenceTitle,link);
    });
    addEventListener('message',event=>{
      if(event.source!==frame.contentWindow || !modal.open || event.data?.kind!=='ledger-reference-v1')return;
      const entry=history[history.length-1];
      if(event.data.action==='ready'){
        const loaded=reference(event.data.url),expected=new URL(entry.url);
        if(!loaded || loaded.pathname!==expected.pathname || loaded.hash!==expected.hash)return;
        title.textContent=event.data.title;entry.title=event.data.title;loading.hidden=true;
        if(entry.state)frame.contentWindow.postMessage({kind:'ledger-parent-v1',action:'restore',state:entry.state},'*');
      }else if(event.data.action==='navigate'){
        const url=reference(event.data.url);if(!url)return;
        entry.state=event.data.state;history.push({url:url.href,title:event.data.title,state:null});show(history[history.length-1]);
      }else if(event.data.action==='close')modal.close();
    });
  }
  if(search){
    const data=JSON.parse(document.getElementById('search-index').textContent);
    const input=document.getElementById('search-input'),results=document.getElementById('search-results'),status=document.getElementById('search-status');
    const normalize=s=>s.toLocaleLowerCase().replace(/\s+/g,' ').trim();
    const indexed=data.map(item=>({...item,hay:normalize(item.title+' '+item.text)}));
    function render(){
      const raw=normalize(input.value);
      const terms=raw.split(' ').filter(Boolean);
      const aliases={'출금':['송금'],'핫월렛':['보내는주소'],'핫지갑':['보내는주소']};
      const groups=terms.map(term=>[term,...(aliases[term]||[])]);
      const matches=indexed.filter(x=>groups.every(group=>group.some(term=>x.hay.includes(term))));
      matches.sort((a,b)=>{
        const score=x=>terms.reduce((sum,t)=>sum+(normalize(x.title).includes(t)?15:0),0)+(x.kind==='업무 단계'?3:x.kind==='정의'?2:x.kind==='원문'?-5:0);
        return score(b)-score(a);
      });
      const shown=matches.slice(0,40);results.replaceChildren();
      status.textContent=terms.length?(matches.length?matches.length+'개 결과'+(matches.length>40?' · 상위 40개 표시':''):'검색 결과가 없습니다. 다른 용어나 페이지 번호로 찾아보세요.'):'업무명·테이블·필드명을 입력하세요.';
      for(const item of shown){
        const li=document.createElement('li'),link=document.createElement('a'),type=document.createElement('small'),heading=document.createElement('strong'),excerpt=document.createElement('p');
        link.href=new URL(item.path,root).href;
        if(item.path.startsWith('_reference/')){link.dataset.referenceTitle=item.title;link.target='_blank';link.rel='noopener'}
        type.textContent=item.kind;heading.textContent=item.title;
        const excerptText=item.text.replace(/\s+/g,' ').trim();
        const hits=groups.flat().map(term=>normalize(excerptText).indexOf(term)).filter(hit=>hit>=0);
        const start=Math.max(0,(hits.length?Math.min(...hits):0)-30);
        excerpt.textContent=(start?'…':'')+excerptText.slice(start,start+120)+(excerptText.length>start+120?'…':'');
        link.append(type,heading,excerpt);li.append(link);results.append(li);
      }
    }
    function openSearch(){render();search.showModal();input.focus()}
    document.querySelector('.search-open').addEventListener('click',openSearch);
    document.querySelector('[data-search-close]').addEventListener('click',()=>search.close());
    search.addEventListener('close',()=>document.querySelector('.search-open').focus({preventScroll:true}));
    input.addEventListener('input',render);
    results.addEventListener('click',event=>{
      const link=event.target.closest('a[href]');
      if(!link || event.defaultPrevented || event.button!==0 || event.metaKey || event.ctrlKey || event.shiftKey || event.altKey)return;
      if(!link.dataset.referenceTitle)search.close();
    });
    document.addEventListener('keydown',event=>{
      if((event.metaKey||event.ctrlKey)&&event.key.toLowerCase()==='k'&&!modal?.open){event.preventDefault();if(!search.open)openSearch()}
    });
  }
  const menu=document.querySelector('.menu-toggle'),sidebar=document.getElementById('sidebar');
  menu?.addEventListener('click',()=>{const open=sidebar.classList.toggle('visible');menu.setAttribute('aria-expanded',String(open))});
  document.addEventListener('keydown',event=>{if(event.key==='Escape'&&sidebar?.classList.contains('visible')){sidebar.classList.remove('visible');menu.setAttribute('aria-expanded','false');menu.focus()}});
  document.querySelectorAll('.phase').forEach(phase=>{
    const details=[...phase.querySelectorAll('.step details')];
    const expand=phase.querySelector('[data-expand-phase]');
    phase.querySelector('[data-expand-phase]').addEventListener('click',event=>{
      const all=details.every(x=>x.open);
      details.forEach(x=>x.open=!all);event.currentTarget.textContent=all?'모두 펼치기':'모두 접기';
    });
    phase.querySelectorAll('.step details').forEach(detail=>detail.addEventListener('toggle',()=>{
      expand.textContent=details.every(x=>x.open)?'모두 접기':'모두 펼치기';
      const active=detail.open?detail:details.find(x=>x.open&&x.closest('.step').hasAttribute('aria-current'))||details.filter(x=>x.open).pop();
      const step=active?.closest('.step'),actor=step?.dataset.actor||'';
      phase.querySelectorAll('.step').forEach(x=>x.removeAttribute('aria-current'));step?.setAttribute('aria-current','step');
      phase.querySelectorAll('[data-node]').forEach(x=>x.classList.toggle('active',actor.includes(x.dataset.node)));
      phase.querySelector('.stage-selected').textContent=step?'원문 '+step.dataset.step+' · '+actor:'처리 순서를 펼치면 해당 시스템과 변경 내용을 강조합니다.';
      phase.querySelectorAll('[data-mutation]').forEach(x=>x.classList.toggle('selected',x.dataset.mutation===step?.dataset.step));
    }));
  });
  const filter=document.getElementById('page-filter');
  filter?.addEventListener('input',()=>{
    const q=filter.value.trim().toLowerCase();let count=0;
    document.querySelectorAll('.source-card').forEach(card=>{card.hidden=!card.dataset.pageLabel.toLowerCase().includes(q);if(!card.hidden)count++});
    document.getElementById('page-count').textContent=count+'쪽';
  });
  let printDetails=[];
  addEventListener('beforeprint',()=>{printDetails=[...document.querySelectorAll('.step details')].map(x=>[x,x.open]);printDetails.forEach(([x])=>x.open=true)});
  addEventListener('afterprint',()=>printDetails.forEach(([x,open])=>x.open=open));
})();
