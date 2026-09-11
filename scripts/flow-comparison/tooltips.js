(() => {
  const terms=[...document.querySelectorAll('.term-tooltip')];
  if(!terms.length)return;
  const tip=document.createElement('div');
  tip.id='term-explanation';tip.className='term-explanation';
  tip.setAttribute('role','tooltip');tip.setAttribute('popover','manual');tip.hidden=true;
  document.body.append(tip);
  terms.forEach(term=>{
    term.dataset.tooltip=term.title;term.removeAttribute('title');
    term.setAttribute('aria-describedby',tip.id);
  });
  let active=null,leaveTimer=null;
  const dialog=document.getElementById('outbox-dialog');
  const content=document.getElementById('outbox-content');
  content.append(document.getElementById('bcm-outbox'));
  let opener=null;
  function openDetails(term){
    hide();opener=term;
    document.body.classList.add('outbox-active');
    dialog.showModal();content.scrollTop=0;
    content.querySelector('.outbox-diagram').scrollLeft=0;
    dialog.querySelector('[data-outbox-close]').focus({preventScroll:true});
  }
  dialog.querySelector('[data-outbox-close]').addEventListener('click',()=>dialog.close());
  dialog.addEventListener('close',()=>{
    document.body.classList.remove('outbox-active');
    opener?.focus({preventScroll:true});hide();
  });
  dialog.addEventListener('click',event=>{
    if(event.target!==dialog)return;
    const box=dialog.getBoundingClientRect();
    if(event.clientX<box.left||event.clientX>box.right||event.clientY<box.top||event.clientY>box.bottom)dialog.close();
  });
  const contains=node=>node instanceof Node&&(active?.contains(node)||tip.contains(node));
  function hide(){
    clearTimeout(leaveTimer);
    if(tip.matches(':popover-open'))tip.hidePopover();
    tip.hidden=true;active=null;
  }
  function show(term){
    clearTimeout(leaveTimer);
    if(active===term&&!tip.hidden)return;
    hide();active=term;
    // Keep the explanation in the modal's accessibility tree and above its clipping area.
    (term.closest('dialog')||document.body).append(tip);
    tip.textContent=term.dataset.tooltip;tip.hidden=false;
    if(tip.showPopover)tip.showPopover();
    position();
  }
  function position(){
    if(!active)return;
    const box=active.getBoundingClientRect();
    if(box.bottom<=0||box.top>=innerHeight){hide();return}
    const width=tip.offsetWidth,height=tip.offsetHeight;
    const left=Math.max(12,Math.min(box.left,innerWidth-width-12));
    const below=box.bottom+8;
    const top=below+height<=innerHeight-12?below:Math.max(12,box.top-height-8);
    tip.style.left=left+'px';tip.style.top=top+'px';
  }
  document.addEventListener('pointerover',event=>{
    if(tip.contains(event.target)){clearTimeout(leaveTimer);return}
    const term=event.target.closest('.term-tooltip');if(term)show(term);
  });
  document.addEventListener('pointerout',event=>{
    if(!contains(event.target)||contains(event.relatedTarget))return;
    clearTimeout(leaveTimer);
    leaveTimer=setTimeout(()=>{if(!active?.contains(document.activeElement))hide()},120);
  });
  document.addEventListener('focusin',event=>{
    const term=event.target.closest('.term-tooltip');if(term)show(term);
    else if(!tip.contains(event.target))hide();
  });
  document.addEventListener('click',event=>{
    const term=event.target.closest('.term-tooltip');
    if(term){
      event.preventDefault();
      if(term.getAttribute('aria-controls')==='outbox-dialog')openDetails(term);
      else show(term);
    }else if(!tip.contains(event.target))hide();
  });
  document.addEventListener('keydown',event=>{
    if(event.key===' '&&event.target.matches('.term-tooltip[aria-controls="outbox-dialog"]')){
      event.preventDefault();openDetails(event.target);return;
    }
    if(event.key==='Escape'&&active){
      event.preventDefault();event.stopImmediatePropagation();hide();
    }
  },true);
  document.addEventListener('scroll',position,true);
  document.addEventListener('close',hide,true);
  addEventListener('resize',position);
  addEventListener('beforeprint',hide);
})();
