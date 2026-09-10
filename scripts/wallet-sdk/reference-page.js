(()=>{
  const embedded=new URLSearchParams(location.search).get('view')==='modal'&&parent!==window;
  if(embedded)document.documentElement.classList.add('embed');
  const send=data=>parent.postMessage({kind:'wallet-reference-v1',...data},'*');
  document.addEventListener('click',e=>{
    const a=e.target.closest('a[data-reference-title]');if(!a||e.defaultPrevented||e.button!==0||e.metaKey||e.ctrlKey||e.shiftKey||e.altKey)return;
    const u=new URL(a.href);if(u.pathname===location.pathname&&u.hash){e.preventDefault();location.hash=u.hash;return;}
    if(embedded){e.preventDefault();send({action:'navigate',url:u.href,title:a.dataset.referenceTitle,scroll:scrollY})}
  });
  addEventListener('keydown',e=>{if(embedded&&e.key==='Escape'){e.preventDefault();send({action:'close'})}});
  addEventListener('message',e=>{if(embedded&&e.source===parent&&e.data?.kind==='wallet-reference-parent-v1'&&e.data.action==='scroll')scrollTo({top:Number(e.data.top)||0,behavior:'instant'})});
  function fit(){for(const box of document.querySelectorAll('.interactive-diagram')){const scale=box.clientWidth/1440;box.style.height=(900*scale)+'px';box.querySelector('iframe').style.transform='scale('+scale+')'}}
  new ResizeObserver(fit).observe(document.body);fit();
  if(embedded){const ready=()=>document.fonts.ready.then(()=>send({action:'ready',url:location.href,title:document.querySelector('h1').textContent}));if(document.readyState==='complete')ready();else addEventListener('load',ready,{once:true})}
})();
