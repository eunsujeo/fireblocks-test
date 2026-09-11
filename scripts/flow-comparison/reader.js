(() => {
  document.documentElement.classList.add('js');
  const rows=[...document.querySelectorAll('.comparison tbody tr')];
  const status=document.getElementById('filter-status');
  let mode='all';
  function showRows(){
    rows.forEach(row=>row.hidden=mode==='differences'&&row.dataset.same==='true');
    const shown=rows.filter(row=>!row.hidden).length;
    document.querySelectorAll('[data-mode]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.mode===mode)));
    status.textContent=mode==='all'?`전체 ${rows.length}개 단계를 표시합니다.`:`차이가 있는 ${shown}개 단계를 표시합니다. 공통 ${rows.length-shown}개 단계는 숨겼습니다.`;
  }
  document.querySelectorAll('[data-mode]').forEach(button=>button.addEventListener('click',()=>{mode=button.dataset.mode;showRows()}));
  document.getElementById('print').addEventListener('click',()=>window.print());
  let openSources=[];
  addEventListener('beforeprint',()=>{
    openSources=[...document.querySelectorAll('details')].map(x=>[x,x.open]);
    openSources.forEach(([x])=>x.open=true);rows.forEach(row=>row.hidden=false);
  });
  addEventListener('afterprint',()=>{openSources.forEach(([x,open])=>x.open=open);showRows()});
  const dialog=document.getElementById('sequence-dialog');
  const canvas=document.getElementById('sequence-canvas');
  const eventDialog=document.getElementById('event-details-dialog');
  const eventContent=document.getElementById('event-details-content');
  const requirementBodies=[document.getElementById('sdk-event-id'),document.getElementById('sdk-event-delivery')];
  eventContent.append(...requirementBodies);
  let eventOpener=null,eventScroll=null;
  function openEventDetails(trigger){
    if(eventDialog.open)return;
    const delivery=trigger?.matches('[data-queue-details],a[href="#sdk-event-delivery"]');
    requirementBodies.forEach(body=>body.hidden=body.id!==(delivery?'sdk-event-delivery':'sdk-event-id'));
    const heading=delivery?'Queue 대응 요구사항':'eventId 요구사항';
    document.getElementById('event-details-title').textContent=heading;
    eventDialog.querySelector('[data-event-details-close]').setAttribute('aria-label',heading+' 상세 닫기');
    eventOpener=trigger;eventScroll={left:canvas.scrollLeft,top:canvas.scrollTop,page:scrollY};
    document.body.classList.add('event-details-active');eventDialog.showModal();eventContent.scrollTop=0;
    eventDialog.querySelector('[data-event-details-close]').focus({preventScroll:true});
  }
  eventDialog.querySelector('[data-event-details-close]').addEventListener('click',()=>eventDialog.close());
  eventDialog.addEventListener('close',()=>{
    document.body.classList.remove('event-details-active');
    if(eventOpener?.isConnected)eventOpener.focus({preventScroll:true});
    if(eventScroll){canvas.scrollTo({left:eventScroll.left,top:eventScroll.top,behavior:'instant'});scrollTo({top:eventScroll.page,behavior:'instant'})}
  });
  eventDialog.addEventListener('click',event=>{
    if(event.target!==eventDialog)return;const rect=eventDialog.getBoundingClientRect();
    if(event.clientX<rect.left||event.clientX>rect.right||event.clientY<rect.top||event.clientY>rect.bottom)eventDialog.close();
  });
  const title=document.getElementById('sequence-title');
  const position=document.getElementById('sequence-position');
  const evidence=document.getElementById('sequence-evidence');
  const reader=document.getElementById('sequence-reader');
  const phase=document.getElementById('sequence-phase');
  const callPosition=document.getElementById('sequence-call-position');
  const callRoute=document.getElementById('sequence-call-route');
  const callText=document.getElementById('sequence-call-text');
  const callPrevious=reader.querySelector('[data-call-prev]');
  const callNext=reader.querySelector('[data-call-next]');
  const templates=[...document.querySelectorAll('template[id^="sequence-"]')];
  const previous=dialog.querySelector('[data-sequence-prev]');
  const next=dialog.querySelector('[data-sequence-next]');
  const maximize=dialog.querySelector('[data-sequence-maximize]');
  let sequenceIndex=0,opener=null,sourceTop=0,zoom=100;
  let calls=[],callIndex=0,view='both';
  const browse=dialog.querySelector('[data-sequence-browse]');
  const phases=[['요청 접수',0],['정책 검사',6],['서명·전파',14],['집금 감지',21],['집금 확정',25]];
  function showCall(index,pan=true){
    if(!calls[index])return;
    callIndex=index;calls.forEach((call,i)=>call.classList.toggle('is-selected',i===index));
    const call=calls[index],svg=call.ownerSVGElement;
    const name=id=>svg.querySelector(`[data-actor="${id}"] text`).textContent;
    callRoute.textContent=name(call.dataset.from)+' → '+name(call.dataset.to);
    callText.textContent=call.dataset.sourceLabel;
    callText.scrollTop=0;
    callPosition.textContent=`${index+1} / ${calls.length}`;
    callPrevious.disabled=index===0;callNext.disabled=index===calls.length-1;
    phase.value=String(phases.filter(([,start])=>start<=index).at(-1)[1]);
    let highlight=svg.querySelector('.call-highlight');
    if(!highlight){highlight=document.createElementNS('http://www.w3.org/2000/svg','rect');highlight.classList.add('call-highlight');svg.insertBefore(highlight,svg.querySelector('[data-actor]'))}
    const box=call.getBBox();
    highlight.setAttribute('x','0');highlight.setAttribute('y',String(box.y-10));
    highlight.setAttribute('width',String(svg.viewBox.baseVal.width));highlight.setAttribute('height',String(box.height+20));
    if(pan){
      const area=canvas.getBoundingClientRect(),rect=call.getBoundingClientRect();
      const header=0;
      const left=canvas.scrollLeft+rect.left-area.left-100;
      const top=canvas.scrollTop+rect.top-area.top-header-32;
      canvas.scrollTo({left:Math.max(0,left),top:Math.max(0,top),behavior:'instant'});
    }
  }
  function sizeDiagram(){
    const pair=canvas.querySelector('.sequence-pair');if(!pair)return;
    const padding=parseFloat(getComputedStyle(canvas).paddingLeft)*2;
    const available=canvas.clientWidth-padding;
    const natural=view==='sdk'&&calls.length?1980:690;
    const base=view==='both'?Math.max(1100,Math.min(available,1420)):
      (innerWidth<=760?natural:Math.min(available,natural));
    pair.dataset.view=view;
    pair.style.width=(base*zoom/100)+'px';
    pair.style.gridTemplateColumns=view==='both'?'minmax(0,1fr) minmax(0,1fr)':'minmax(0,1fr)';
    pair.querySelectorAll('.sequence-card').forEach(card=>card.hidden=view!=='both'&&!card.classList.contains(view));
    dialog.querySelectorAll('[data-sequence-side]').forEach(button=>button.setAttribute('aria-pressed',String(button.dataset.sequenceSide===view)));
    document.getElementById('sequence-zoom-level').value=zoom+'%';
    dialog.querySelector('[data-sequence-zoom="-"]').disabled=zoom===100;
    dialog.querySelector('[data-sequence-zoom="+"]').disabled=zoom===400;
  }
  function showSequence(index){
    sequenceIndex=index;const template=templates[index];
    title.textContent=template.dataset.title;
    position.textContent=`전체 ${index+1} / ${templates.length} · 업무 내 ${template.dataset.stage} / 4 단계`;
    const fragment=template.content.cloneNode(true);
    const sources=fragment.querySelector('.sequence-sources');
    evidence.replaceChildren(...sources.children);sources.remove();
    canvas.replaceChildren(fragment);
    view='both';zoom=100;callIndex=0;
    calls=[...canvas.querySelectorAll('.sdk-detail .source-message')];
    reader.hidden=true;browse.hidden=calls.length===0;browse.setAttribute('aria-expanded','false');
    phase.replaceChildren(...phases.map(([label,start])=>{
      const option=document.createElement('option');option.value=String(start);option.textContent=label;return option;
    }));
    const legend=dialog.querySelector('.sequence-legend');
    if(!legend.dataset.default)legend.dataset.default=legend.innerHTML;
    legend.innerHTML=calls.length?'두 흐름을 나란히 표시합니다. SDK 크게를 누르면 전체 폭으로 읽을 수 있습니다.':legend.dataset.default;
    previous.disabled=index===0;next.disabled=index===templates.length-1;
    sizeDiagram();canvas.scrollTo({left:0,top:0,behavior:'instant'});
  }
  function openSequence(id,button){
    const index=templates.findIndex(x=>x.id===id);if(index<0)return;
    opener=button;sourceTop=scrollY;zoom=100;
    document.body.classList.add('sequence-active');dialog.showModal();
    showSequence(index);canvas.scrollLeft=0;dialog.querySelector('[data-sequence-close]').focus();
  }
  document.addEventListener('click',event=>{
    const detail=event.target.closest('[data-event-details],[data-queue-details],a[href="#sdk-event-id"],a[href="#sdk-event-delivery"]');
    if(detail){event.preventDefault();openEventDetails(detail);return}
    const button=event.target.closest('[data-open-sequence]');
    if(button){openSequence(button.dataset.openSequence,button);return}
    const row=event.target.closest('tr[data-sequence]');
    if(row&&!event.target.closest('a,button,input,summary')&&!getSelection().toString()){
      const trigger=row.querySelector('[data-open-sequence]');openSequence(row.dataset.sequence,trigger);
    }
  });
  document.addEventListener('keydown',event=>{
    const detail=event.target.closest('.sequence-message[data-event-details],.sequence-message[data-queue-details]');
    if(detail&&(event.key==='Enter'||event.key===' ')){event.preventDefault();openEventDetails(detail)}
  });
  dialog.querySelector('[data-sequence-close]').addEventListener('click',()=>dialog.close());
  dialog.addEventListener('close',()=>{
    document.body.classList.remove('sequence-active');
    if(opener?.isConnected)opener.focus({preventScroll:true});
    scrollTo({top:sourceTop,behavior:'instant'});
  });
  dialog.addEventListener('click',event=>{
    if(event.target!==dialog)return;const box=dialog.getBoundingClientRect();
    if(event.clientX<box.left||event.clientX>box.right||event.clientY<box.top||event.clientY>box.bottom)dialog.close();
  });
  previous.addEventListener('click',()=>{if(sequenceIndex>0)showSequence(sequenceIndex-1)});
  next.addEventListener('click',()=>{if(sequenceIndex<templates.length-1)showSequence(sequenceIndex+1)});
  function expand(expanded){
    dialog.classList.toggle('is-maximized',expanded);
    maximize.setAttribute('aria-pressed',String(expanded));
    maximize.textContent=expanded?'원래 크기':'전체 화면';
    sizeDiagram();
  }
  maximize.addEventListener('click',()=>expand(!dialog.classList.contains('is-maximized')));
  browse.addEventListener('click',()=>{
    reader.hidden=!reader.hidden;browse.setAttribute('aria-expanded',String(!reader.hidden));
    if(!reader.hidden){if(view==='daw')setView('sdk');showCall(callIndex)}
  });
  phase.addEventListener('change',()=>showCall(Number(phase.value)));
  callPrevious.addEventListener('click',()=>showCall(callIndex-1));
  callNext.addEventListener('click',()=>showCall(callIndex+1));
  canvas.addEventListener('click',event=>{
    const call=event.target.closest('.source-message');
    if(call){reader.hidden=false;browse.setAttribute('aria-expanded','true');showCall(calls.indexOf(call),false)}
  });
  dialog.querySelectorAll('[data-sequence-zoom]').forEach(button=>button.addEventListener('click',()=>{
    const action=button.dataset.sequenceZoom;
    zoom=action==='fit'?100:Math.max(100,Math.min(400,zoom+(action==='+'?25:-25)));
    sizeDiagram();
  }));
  function setView(side){
    view=side;zoom=100;
    if(side!=='both')expand(true);
    if(side==='daw'){reader.hidden=true;browse.setAttribute('aria-expanded','false')}
    sizeDiagram();canvas.scrollTo({left:0,top:0,behavior:'instant'});
  }
  dialog.querySelectorAll('[data-sequence-side]').forEach(button=>button.addEventListener('click',()=>setView(button.dataset.sequenceSide)));
  addEventListener('resize',()=>{if(dialog.open)sizeDiagram()});
  const openLinkedRequirement=()=>{if(['#sdk-event-id','#sdk-event-delivery'].includes(location.hash))openEventDetails(document.querySelector(`a[href="${location.hash}"]`))};
  addEventListener('hashchange',openLinkedRequirement);openLinkedRequirement();
  showRows();
})();
