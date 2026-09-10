// Embedded into the exported HTML; no JavaScript file is needed in the shared ZIP.
(()=>{
  const root=new URL(document.currentScript.dataset.root,location.href);
  const modal=document.getElementById('reference-dialog');
  const frame=document.getElementById('reference-frame');
  const heading=modal.querySelector('h2');
  const back=modal.querySelector('[data-reference-back]');
  const separate=modal.querySelector('[data-reference-window]');
  const close=modal.querySelector('[data-reference-close]');
  const loading=modal.querySelector('.reference-loading');
  let entries=[],opener=null;
  function valid(raw){const u=new URL(raw,location.href);return u.href.startsWith(new URL('_reference/',root).href)&&u.pathname.endsWith('.html')?u:null}
  function show(entry){heading.textContent=entry.title;separate.href=entry.url;back.disabled=entries.length<2;loading.hidden=false;frame.title=entry.title;const u=new URL(entry.url);u.searchParams.set('view','modal');frame.src=u.href}
  function open(raw,title,trigger){const u=valid(raw);if(!u)return;const search=document.getElementById('search-dialog');opener=search?.contains(trigger)?document.querySelector('.search-open'):trigger;if(search?.open)search.close();entries=[{url:u.href,title,scroll:0}];if(!modal.open)modal.showModal();document.body.classList.add('reference-open');show(entries[0]);close.focus()}
  function finish(){if(modal.open)modal.close()}
  modal.addEventListener('close',()=>{frame.src='about:blank';document.body.classList.remove('reference-open');if(opener?.isConnected)opener.focus({preventScroll:true});entries=[]});
  close.addEventListener('click',finish);
  back.addEventListener('click',()=>{if(entries.length>1){entries.pop();show(entries[entries.length-1])}});
  modal.addEventListener('click',e=>{if(e.target!==modal)return;const b=modal.getBoundingClientRect();if(e.clientX<b.left||e.clientX>b.right||e.clientY<b.top||e.clientY>b.bottom)finish()});
  document.addEventListener('click',e=>{const a=e.target.closest('a[data-reference-title]');if(!a||e.defaultPrevented||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;e.preventDefault();open(a.href,a.dataset.referenceTitle,a)});
  addEventListener('message',e=>{
    if(e.source!==frame.contentWindow||!modal.open||e.data?.kind!=='wallet-reference-v1')return;
    const current=entries[entries.length-1];
    if(e.data.action==='ready'){
      const loaded=valid(e.data.url);if(!loaded||loaded.pathname!==new URL(current.url).pathname)return;
      heading.textContent=e.data.title;current.title=e.data.title;loading.hidden=true;
      if(current.scroll)frame.contentWindow.postMessage({kind:'wallet-reference-parent-v1',action:'scroll',top:current.scroll},'*');
    } else if(e.data.action==='navigate'){
      const u=valid(e.data.url);if(!u)return;current.scroll=Number(e.data.scroll)||0;entries.push({url:u.href,title:e.data.title,scroll:0});show(entries[entries.length-1]);
    } else if(e.data.action==='close')finish();
  });
})();
