// Uses an existing Playwright installation. No server or vendor calls.
const fs=require('fs'),path=require('path'),{pathToFileURL}=require('url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const root=path.resolve(process.argv[2]||path.join(__dirname,'../../docs/ledger'));
const out=process.env.LEDGER_REPORT_DIR||'/tmp/ledger-verification';
const manifest=JSON.parse(fs.readFileSync(path.join(root,'manifest.json'),'utf8'));
const report={checks:[],pages:[],errors:[],external:[]};
const url=p=>pathToFileURL(path.join(root,p)).href;
const assert=(ok,message)=>{if(!ok)throw Error(message)};
fs.mkdirSync(out,{recursive:true});
(async()=>{
 const browser=await chromium.launch({headless:true,...(process.env.CHROME_BIN?{executablePath:process.env.CHROME_BIN}:{})});
 const context=await browser.newContext({viewport:{width:1440,height:1000},reducedMotion:'reduce'});
 context.setDefaultTimeout(12000);
 await context.route('**/*',route=>{
   if(/^https?:/.test(route.request().url())){report.external.push(route.request().url());return route.abort()}
   return route.continue();
 });
 const page=await context.newPage();
 page.on('pageerror',e=>report.errors.push(e.message));
 page.on('requestfailed',r=>report.errors.push(r.url()+' '+r.failure().errorText));
 async function go(p){await page.goto(url(p));await page.waitForFunction(()=>document.documentElement.dataset.readerReady==='true')}
 async function currentFrame(expected){
   if(expected)await page.waitForFunction(x=>document.querySelector('#reference-frame').src.includes(x),expected);
   await page.waitForFunction(()=>document.querySelector('#reference-dialog').open&&document.querySelector('.reference-loading').hidden);
   return (await page.locator('#reference-frame').elementHandle()).contentFrame();
 }
 async function check(name,run){
   try{await run();report.checks.push({name,passed:true});console.log('PASS',name)}
   catch(e){report.checks.push({name,passed:false,error:e.message});console.log('FAIL',name,e.message)}
 }
 await check('Seven-section navigation and thirteen workflow entries',async()=>{
   await go('index.html');assert(await page.locator('.sidebar nav a').count()===7,'navigation count');
   await page.screenshot({path:path.join(out,'01-home.png')});
   await page.locator('.sidebar a[href="flows.html"]').click();
   assert(await page.locator('main .work-link').count()===13,'workflow count');
 });
 await check('Deposit phases preserve Confirmed and Finalized balance differences',async()=>{
   await go('flows/deposit.html');
   const confirmed=await page.locator('#phase-31 .balance-panel').innerText();
   const finalized=await page.locator('#phase-32 .balance-panel').innerText();
   assert(confirmed.includes('온체인잔고: 증가'),'Confirmed amount');
   assert(!finalized.includes('온체인잔고: 증가')&&finalized.includes('입금중: 감소'),'Finalized double-count');
   await page.locator('#step-31-8 summary').click();
   await page.waitForFunction(()=>document.querySelector('#phase-31 [data-node="DAWBC"]').classList.contains('active'));
   assert(await page.locator('#phase-31 [data-mutation="8"]').evaluate(x=>x.classList.contains('selected')),'balance highlight');
   await page.locator('#phase-31').scrollIntoViewIfNeeded();
   await page.screenshot({path:path.join(out,'02-deposit.png')});
 });
 await check('Definition modal keeps the guide location and keyboard focus',async()=>{
   const link=page.locator('#step-31-8 .step-body a[href$="/addresses.html"]');
   await link.scrollIntoViewIfNeeded();
   const before=await page.evaluate(()=>({url:location.href,top:scrollY}));
   await link.click();const frame=await currentFrame('addresses.html');
   assert((await frame.locator('main').innerText()).includes('Confirmed와 Finalized'),'definition content');
   assert(await frame.locator('.site-header').isHidden(),'embedded header');
   await page.screenshot({path:path.join(out,'03-definition-modal.png')});
   await page.locator('[data-reference-close]').click();
   assert(page.url()===before.url,'guide URL changed');
   assert(Math.abs(await page.evaluate(()=>scrollY)-before.top)<2,'guide scroll');
   assert(await link.evaluate(x=>x===document.activeElement),'focus restore');
 });
 await check('Nested reference navigation and Back restore reading position',async()=>{
   await page.locator('#step-31-8 .step-body a[href$="/addresses.html"]').click();
   let frame=await currentFrame('addresses.html');
   const nested=frame.locator('a[href$="/currency.html"]').last();await nested.scrollIntoViewIfNeeded();
   const top=await frame.evaluate(()=>scrollY);
   await nested.click();frame=await currentFrame('currency.html');
   assert((await frame.locator('main').innerText()).includes('이용가능 잔고'),'nested definition');
   await page.locator('[data-reference-back]').click();
   frame=await currentFrame('addresses.html');
   await frame.waitForFunction(t=>Math.abs(scrollY-t)<2,top);
   await frame.locator('main > p.eyebrow').click();await page.keyboard.press('Escape');
   await page.locator('#reference-dialog').waitFor({state:'hidden'});
 });
 await check('Source step opens at the original position with zoom controls',async()=>{
   await page.locator('#step-31-8 .step-body a.source-link').click();
   const frame=await currentFrame('page-31.html');
   await frame.waitForFunction(()=>document.querySelector('[data-source-step="8"]').classList.contains('current'));
   assert(await frame.locator('#zoom-level').innerText()==='200%','initial zoom');
   await frame.locator('[data-zoom="+"]').click();
   assert(await frame.locator('#zoom-level').innerText()==='250%','zoom in');
   await frame.locator('[data-zoom="-"]').click();
   await page.screenshot({path:path.join(out,'04-source-position.png')});
   await frame.locator('[data-zoom="fit"]').click();
   assert(await frame.locator('#zoom-level').innerText()==='100%','fit');
   assert(await frame.locator('.source-mark.current').count()===0,'fit leaves dim overlay');
 });
 await check('Separate window opens a complete local reference page',async()=>{
   const [popup]=await Promise.all([context.waitForEvent('page'),page.locator('[data-reference-window]').click()]);
   await popup.waitForLoadState();
   assert(popup.url().includes('/_reference/page-31.html'),'new page');
   assert(!popup.url().includes('view=modal'),'modal flag on standalone');
   assert(await popup.locator('.site-header').isVisible(),'standalone header');
   await popup.close();await page.locator('[data-reference-close]').click();
 });
 await check('Search finds fields and opens rebuilt definitions',async()=>{
   await go('index.html');await page.locator('.search-open').click();
   await page.locator('#search-input').fill('주소별 잔고');
   await page.locator('#search-results a[href$="/addresses.html"]').click();
   const frame=await currentFrame('addresses.html');
   assert((await frame.locator('main').innerText()).includes('델타정산입금대기'),'definition search');
   await page.locator('[data-reference-close]').click();
   await page.locator('.search-open').click();await page.locator('#search-input').fill('계좌 생성');
   await page.locator('#search-results a[href$="/flows/account.html"]').click();
   await page.waitForURL('**/flows/account.html');
   assert((await page.locator('h1').innerText())==='계좌 생성','workflow search');
 });
 await check('Search includes text that was stored as images in the PDF',async()=>{
   await page.locator('.search-open').click();await page.locator('#search-input').fill('80%');
   const results=await page.locator('#search-results').innerText();
   assert(results.includes('브릿징스윕'),'raster note search');
   await page.locator('[data-search-close]').click();
 });
 await check('Source catalogue filters all 64 original pages',async()=>{
   await go('sources.html');assert(await page.locator('.source-card').count()===64,'source coverage');
   await page.locator('#page-filter').fill('31');
   assert(await page.locator('.source-card:visible').count()===1,'page filter');
   await page.locator('.source-card:visible').click();
   const frame=await currentFrame('page-31.html');
   assert(await frame.locator('.figure-inner img').evaluate(x=>x.complete&&x.naturalWidth===2160),'raster load');
   await page.locator('[data-reference-close]').click();
 });
 await check('All-step expansion and printable text remain available',async()=>{
   await go('flows/settlement.html');
   await page.locator('#phase-52 [data-expand-phase]').click();
   assert(await page.locator('#phase-52 .step details[open]').count()===10,'expand all');
   assert((await page.locator('#phase-52').innerText()).includes('전체 완료 조건'),'settlement boundary note');
   await page.evaluate(()=>window.dispatchEvent(new Event('beforeprint')));
   assert(await page.locator('.step details:not([open])').count()===0,'print missing steps');
   await page.evaluate(()=>window.dispatchEvent(new Event('afterprint')));
   await page.screenshot({path:path.join(out,'05-settlement.png')});
 });
 await check('Mobile navigation, full-screen reference and source zoom',async()=>{
   await page.setViewportSize({width:390,height:844});await go('index.html');
   await page.locator('.menu-toggle').click();assert(await page.locator('.sidebar').isVisible(),'mobile menu');
   await page.keyboard.press('Escape');assert(await page.locator('.sidebar').isHidden(),'menu dismissal');
   await go('flows/deposit.html');
   await page.locator('main .sources a[href$="/addresses.html"]').first().click();
   let frame=await currentFrame('addresses.html');const b=await page.locator('#reference-dialog').boundingBox();
   assert(b.x===0&&b.y===0&&b.width===390&&b.height===844,'full-screen modal');
   assert(await frame.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'definition overflow');
   await page.screenshot({path:path.join(out,'06-mobile-definition.png')});
   await page.locator('[data-reference-close]').click();
   await page.locator('#step-31-8 summary').click();
   await page.locator('#step-31-8 .step-body .source-link').click();
   frame=await currentFrame('page-31.html');await frame.locator('[data-zoom="+"]').click();
   await page.screenshot({path:path.join(out,'07-mobile-source.png')});
   await page.locator('[data-reference-close]').click();
   assert(page.url()===url('flows/deposit.html'),'mobile guide URL');
 });
 await check('JavaScript-disabled reference links open local standalone content',async()=>{
   const nojs=await browser.newContext({javaScriptEnabled:false,viewport:{width:1440,height:900}});
   const p=await nojs.newPage();await p.goto(url('flows/deposit.html'));
   const [popup]=await Promise.all([nojs.waitForEvent('page'),p.locator('main .sources a[href$="/addresses.html"]').first().click()]);
   await popup.waitForLoadState();
   assert(popup.url()===url('_reference/addresses.html'),'fallback target');
   assert(await popup.locator('main table').isVisible(),'fallback content');
   assert(p.url()===url('flows/deposit.html'),'fallback changed guide');await nojs.close();
 });
 await check('Exact field names survive search aliases and phase results navigate',async()=>{
   await page.setViewportSize({width:1440,height:1000});await go('index.html');
   await page.locator('.search-open').click();
   for(const term of ['외부입출금주소','핫월렛']){
     await page.locator('#search-input').fill(term);
     assert(await page.locator('#search-results a[href$="/assets.html"]').count()===1,'original field missing: '+term);
   }
   await page.locator('#search-input').fill('출금');
   assert(await page.locator('#search-results a[href$="/flows/withdrawal.html"]').count()===1,'withdrawal alias missing');
   await page.locator('#search-input').fill('입금 확정 Finalized');
   await page.locator('#search-results a[href$="/flows/deposit.html#phase-32"]').click();
   await page.waitForURL('**/flows/deposit.html#phase-32');
   assert(await page.locator('#phase-32 h2').isVisible(),'phase search target');
 });
 await check('Closing steps clears highlights and keeps expansion labels accurate',async()=>{
   await go('flows/deposit.html');const phase=page.locator('#phase-31');
   await phase.locator('#step-31-8 summary').click();
   await page.waitForFunction(()=>document.querySelector('#step-31-8').hasAttribute('aria-current'));
   await phase.locator('#step-31-8 summary').click();
   await page.waitForFunction(()=>!document.querySelector('#phase-31 .step[aria-current]'));
   assert(await phase.locator('.stage-node.active,.balance-entry.selected').count()===0,'closed highlight');
   await phase.locator('[data-expand-phase]').click();
   for(const summary of await phase.locator('.step summary').all())await summary.click();
   await page.waitForFunction(()=>document.querySelector('#phase-31 [data-expand-phase]').textContent==='모두 펼치기');
   assert(await phase.locator('.step details[open]').count()===0,'all closed');
 });
 await check('Reading diagrams and related work reflect separate roles',async()=>{
   await go('data.html');
   assert((await page.locator('main').innerText()).includes('하루에 한 번'),'snapshot timing');
   assert(await page.locator('main .path-arrow').count()===0,'record roles shown as sequence');
   await go('flows/sweep.html');
   assert(await page.locator('.variant-map').evaluate(x=>getComputedStyle(x).display==='flex'),'sweep variant layout');
   assert(await page.locator('.variant-map .path-item').count()===3,'sweep alternatives');
   await page.screenshot({path:path.join(out,'08-sweep-variants.png')});
   await go('flows/deposit.html');
   const related=await page.locator('main .work-link').evaluateAll(xs=>xs.map(x=>x.getAttribute('href')));
   assert(related.join('|')==='../flows/sweep.html|../flows/customer-suspense.html','related reading');
 });
 await check('Mobile navigation works with JavaScript disabled',async()=>{
   const nojs=await browser.newContext({javaScriptEnabled:false,viewport:{width:390,height:844}});
   const p=await nojs.newPage();await p.goto(url('index.html'));
   assert(await p.locator('.sidebar').isVisible(),'mobile fallback navigation');
   assert(await p.locator('.search-open').isHidden(),'inactive search control');
   await p.locator('.sidebar a[href="flows.html"]').click();await p.waitForURL('**/flows.html');
   assert(await p.locator('main .work-link').count()===13,'fallback workflows');
   await p.screenshot({path:path.join(out,'09-mobile-nojs.png')});await nojs.close();
 });
 await check('Search within the current workflow closes before reading the result',async()=>{
   await go('flows/deposit.html');await page.locator('.search-open').click();
   await page.locator('#search-input').fill('입금 확정 Finalized');
   await page.locator('#search-results a[href$="/flows/deposit.html#phase-32"]').click();
   await page.waitForURL('**/flows/deposit.html#phase-32');
   assert(!await page.locator('#search-dialog').evaluate(x=>x.open),'search still covers the current workflow');
   await page.locator('#step-32-7 summary').click();
   assert(await page.locator('#step-32-7 details').evaluate(x=>x.open),'result is not interactive');
 });
 await check('Source Back preserves an explicitly cleared highlight and zoom',async()=>{
   await go('flows/deposit.html');await page.locator('#step-31-8 summary').click();
   await page.locator('#step-31-8 .step-body .source-link').click();
   let frame=await currentFrame('page-31.html');
   await frame.locator('[data-zoom="fit"]').click();
   assert(await frame.locator('.source-mark.current').count()===0,'fit did not clear highlight');
   await frame.locator('.source-pagination a[href*="page-32.html"]').click();
   await currentFrame('page-32.html');await page.locator('[data-reference-back]').click();
   frame=await currentFrame('page-31.html');
   await frame.waitForFunction(()=>document.querySelector('#zoom-level').value==='100%');
   assert(await frame.locator('.source-mark.current').count()===0,'Back restored an unwanted highlight');
   await page.locator('[data-reference-close]').click();
 });
 await check('Offramp balance check links both ownership and asset definitions',async()=>{
   await go('flows/ramp.html');await page.locator('#step-24-2 summary').click();
   for(const key of ['currency','assets']){
     const link=page.locator('#step-24-2 .step-body a[href$="/'+key+'.html"]');
     assert(await link.count()===1,'missing definition: '+key);
     await link.click();const frame=await currentFrame(key+'.html');
     assert(await frame.locator('main table').isVisible(),'definition table missing');
     await page.locator('[data-reference-close]').click();
     assert(await link.evaluate(x=>x===document.activeElement),'definition focus restore');
     assert(await page.locator('#phase-24 .balance-panel a[href$="/'+key+'.html"]').count()>0,'balance panel definition missing');
   }
 });
 let next=0;
 const tasks=manifest.pages.flatMap(p=>[1440,390].map(width=>({...p,width})));
 async function worker(){
   const p=await context.newPage();let errors=[];
   p.on('pageerror',e=>errors.push(e.message));p.on('requestfailed',r=>errors.push(r.url()+' '+r.failure().errorText));
   while(next<tasks.length){
     const task=tasks[next++];errors=[];
     try{
       await p.setViewportSize({width:task.width,height:task.width===390?844:1000});
       await p.goto(url(task.path));await p.waitForFunction(()=>document.documentElement.dataset.readerReady==='true');
       assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'horizontal body overflow');
       assert(await p.locator('h1').isVisible(),'missing title');
       assert(await p.locator('main').innerText(),'empty body');
       assert(await p.evaluate(()=>[...document.images].filter(x=>x.loading!=='lazy').every(x=>x.complete&&x.naturalWidth>0)),'broken image');
       report.pages.push({path:task.path,width:task.width,passed:errors.length===0,errors:[...errors]});
     }catch(e){report.pages.push({path:task.path,width:task.width,passed:false,errors:[...errors,e.message]})}
     if(report.pages.length%40===0)console.log('PAGES',report.pages.length);
   }
   await p.close();
 }
 await Promise.all([worker(),worker(),worker()]);
 report.summary={checks:report.checks.length,failed:report.checks.filter(x=>!x.passed).length,pageViewportChecks:report.pages.length,pageFailures:report.pages.filter(x=>!x.passed).length,errors:report.errors.length,externalRequests:report.external.length};
 fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2));
 console.log('SUMMARY',JSON.stringify(report.summary));
 console.log('FAILED PAGES',JSON.stringify(report.pages.filter(x=>!x.passed)));
 await browser.close();
 if(report.summary.failed||report.summary.pageFailures||report.summary.errors||report.summary.externalRequests)process.exitCode=1;
})().catch(e=>{console.error(e);process.exit(1)});
