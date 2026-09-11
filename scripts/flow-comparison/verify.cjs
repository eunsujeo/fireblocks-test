const fs=require('fs'),path=require('path'),{pathToFileURL}=require('url');
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright');
const root=path.resolve(__dirname,'../../docs/flow-comparison');
const url=pathToFileURL(path.join(root,'index.html')).href;
const out='/tmp/flow-comparison-verification';fs.mkdirSync(out,{recursive:true});
const report={checks:[],errors:[],external:[]};
const assert=(ok,message)=>{if(!ok)throw Error(message)};
(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.CHROME_BIN||'/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
 const context=await browser.newContext({viewport:{width:1440,height:1100},reducedMotion:'reduce'});
 await context.route('**/*',r=>{if(/^https?:/.test(r.request().url())){report.external.push(r.request().url());return r.abort()}return r.continue()});
 const p=await context.newPage();p.on('pageerror',e=>report.errors.push(e.message));p.on('requestfailed',r=>report.errors.push(r.url()));
 const check=async(name,fn)=>{try{await fn();report.checks.push({name,passed:true});console.log('PASS',name)}catch(e){report.checks.push({name,passed:false,error:e.message});console.log('FAIL',name,e.message)}};
 await p.goto(url);
 await check('Desktop keeps all six flows aligned in paired rows',async()=>{
  assert(await p.locator('table.comparison').count()===6,'six scenarios');
  assert(await p.locator('.comparison tbody tr').count()===24,'paired steps');
  for(const r of await p.locator('.comparison tbody tr').all()){
   const left=await r.locator('td.daw').boundingBox(),right=await r.locator('td.sdk').boundingBox();
   assert(Math.abs(left.y-right.y)<1&&Math.abs(left.height-right.height)<1,'misaligned row');
  }
  assert(await p.evaluate(()=>document.documentElement.scrollWidth===innerWidth),'desktop overflow');
  await p.screenshot({path:path.join(out,'01-overview.png')});
  await p.locator('#normal').scrollIntoViewIfNeeded();await p.screenshot({path:path.join(out,'02-normal.png')});
 });
 await check('Differences filter preserves headings and restores all steps',async()=>{
  await p.locator('[data-mode="differences"]').click();
  assert(await p.locator('.comparison tbody tr:visible').count()===22,'difference count');
  assert(await p.locator('.comparison tbody tr[data-same="false"]:visible').count()===22,'difference hidden');
  await p.locator('.section-nav a[href="#return-after"]').click();
  assert(p.url().endsWith('#return-after'),'anchor navigation');
  await p.locator('[data-mode="all"]').click();assert(await p.locator('.comparison tbody tr:visible').count()===24,'restore count');
 });
 await check('Local evidence opens in a separate window without losing the comparison',async()=>{
  await p.locator('#suspense .sources summary').click();
  const link=p.locator('#suspense .source-link[href*="webhooks"]');
  const before=p.url();const [popup]=await Promise.all([context.waitForEvent('page'),link.click()]);
  await popup.waitForLoadState();assert(popup.url().startsWith('file:')&&popup.url().includes('/wallet-sdk/_reference/'),'local rebuilt source');
  assert((await popup.locator('body').innerText()).includes('아직 구현되지'),'implementation caveat present');
  assert(p.url()===before,'comparison moved');await popup.close();
 });
 await check('Narrow screens retain both columns inside scrollable tables',async()=>{
  for(const width of [320,390,768]){
   await p.setViewportSize({width,height:844});await p.goto(url);
   assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'body overflow at '+width);
   const region=p.locator('#normal > .compare-scroll');
   assert(await region.evaluate(x=>x.scrollWidth>x.clientWidth),'table should scroll at '+width);
   await region.focus();await p.keyboard.press('ArrowRight');
   await p.waitForFunction(()=>document.querySelector('#normal > .compare-scroll').scrollLeft>0);
   if(width===390){await p.screenshot({path:path.join(out,'03-mobile-table.png')});await p.goto(url);await p.screenshot({path:path.join(out,'04-mobile-overview.png')})}
  }
 });
 await check('Print includes every step and restores reading controls afterward',async()=>{
  await p.setViewportSize({width:1440,height:1100});await p.locator('[data-mode="differences"]').click();
  const before=await p.locator('details[open]').count();
  await p.evaluate(()=>dispatchEvent(new Event('beforeprint')));await p.emulateMedia({media:'print'});
  assert(await p.locator('.comparison tbody tr:visible').count()===24,'print missing common steps');
  assert(await p.locator('details[open]').count()===6,'print evidence collapsed');
  assert(await p.locator('.reader-controls').isHidden(),'print controls');
  await p.emulateMedia({media:'screen'});await p.evaluate(()=>dispatchEvent(new Event('afterprint')));
  assert(await p.locator('.comparison tbody tr:visible').count()===22,'filter lost after print');
  assert(await p.locator('details[open]').count()===before,'source state lost');
 });
 await check('Without JavaScript the full comparison and evidence remain readable',async()=>{
  const c=await browser.newContext({javaScriptEnabled:false,viewport:{width:390,height:844}});const n=await c.newPage();await n.goto(url);
  assert(await n.locator('.comparison tbody tr:visible').count()===24,'no-script content');
  assert(await n.locator('.reader-controls').isHidden(),'inactive filter visible');
  await n.locator('#company .sources summary').click();assert(await n.locator('#company .source-link').first().isVisible(),'native source disclosure');await c.close();
 });
 await check('All 24 stages open two readable local sequence diagrams',async()=>{
  await p.setViewportSize({width:1440,height:1100});await p.goto(url);
  const buttons=await p.locator('.comparison [data-open-sequence]').all();assert(buttons.length===24,'stage controls');
  for(let i=0;i<buttons.length;i++){
   await buttons[i].click();assert(await p.locator('#sequence-dialog').evaluate(x=>x.open),'dialog closed');
   assert(await p.locator('#sequence-canvas svg').count()===2,'paired diagrams');
   assert(await p.locator('#sequence-position').innerText()===`전체 ${i+1} / 24 · 업무 내 ${i%4+1} / 4 단계`,'wrong stage');
   const overflow=await p.locator('#sequence-canvas svg').evaluateAll(svgs=>svgs.flatMap(svg=>[...svg.querySelectorAll('text')].filter(t=>{const b=t.getBBox();return b.x<0||b.x+b.width>svg.viewBox.baseVal.width+1||b.y+b.height>svg.viewBox.baseVal.height+1}).map(t=>t.textContent)));
   assert(overflow.length===0,'clipped SVG text: '+overflow.join(' / '));
   if(i===2)await p.screenshot({path:path.join(out,'05-confirmed-sequence.png')});
   if(i===6){assert((await p.locator('#sequence-canvas .sdk').innerText()).includes('아직 구현되지'),'unimplemented notice missing');await p.screenshot({path:path.join(out,'06-missing-sequence.png')})}
   await p.locator('[data-sequence-close]').click();
  }
 });
 await check('Stage navigation, zoom and Escape preserve the original reading position',async()=>{
  await p.goto(url);const button=p.locator('[data-open-sequence="sequence-normal-1"]');
  await button.scrollIntoViewIfNeeded();await button.focus();const top=await p.evaluate(()=>scrollY);
  await p.keyboard.press('Enter');assert(await p.locator('[data-sequence-prev]').isDisabled(),'first-stage boundary');
  await p.locator('[data-sequence-next]').click();assert((await p.locator('#sequence-title').innerText()).includes('감지'),'next stage');
  const width=await p.locator('.sequence-pair').evaluate(x=>x.clientWidth);
  await p.locator('[data-sequence-zoom="+"]').click();assert(await p.locator('.sequence-pair').evaluate(x=>x.clientWidth)>width,'zoom did not grow');
  assert(await p.locator('#sequence-zoom-level').innerText()==='125%','zoom indicator');
  await p.locator('[data-sequence-zoom="fit"]').click();assert(await p.locator('#sequence-zoom-level').innerText()==='100%','reset zoom');
  await p.keyboard.press('Escape');assert(await p.locator('#sequence-dialog').isHidden(),'Escape dismissal');
  assert(await button.evaluate(x=>x===document.activeElement),'focus restore');
  assert(Math.abs(await p.evaluate(()=>scrollY)-top)<2,'scroll restore');
  await p.locator('[data-open-sequence="sequence-company-4"]').click();assert(await p.locator('[data-sequence-next]').isDisabled(),'last-stage boundary');
  await p.locator('[data-sequence-close]').click();
 });
 await check('Sequence sources open locally and unsupported return paths stay explicit',async()=>{
  await p.locator('[data-open-sequence="sequence-return-after-2"]').click();
  assert((await p.locator('#sequence-canvas .sdk').innerText()).includes('전용 흐름은 제공 문서에 없다'),'unsupported return hidden');
  const messages=await p.locator('#sequence-canvas .sdk .sequence-message').evaluateAll(nodes=>nodes.map(x=>({from:x.dataset.from,to:x.dataset.to,kind:x.dataset.kind})));
  assert(messages.filter(x=>x.kind==='core').length===3,'retained admin/CORE requests missing');
  assert(!messages.some(x=>[x.from,x.to].some(a=>a==='WALLET-SDK'||a==='블록체인')),'invented SDK transfer');
  const [popup]=await Promise.all([context.waitForEvent('page'),p.locator('#sequence-evidence a[href*="withdrawal"]').click()]);
  await popup.waitForLoadState();assert(popup.url().startsWith('file:')&&popup.url().includes('/_reference/'),'external evidence');await popup.close();
  assert(await p.locator('#sequence-dialog').evaluate(x=>x.open),'evidence dismissed dialog');await p.locator('[data-sequence-close]').click();
 });
 await check('Mobile row clicks open a full-screen scrollable sequence comparison',async()=>{
  await p.setViewportSize({width:390,height:844});await p.goto(url);
  await p.locator('#normal tr[data-sequence="sequence-normal-3"] td.daw p').click();
  const box=await p.locator('#sequence-dialog').boundingBox();assert(box.x===0&&box.y===0&&box.width===390&&box.height===844,'mobile modal bounds');
  const canvas=p.locator('#sequence-canvas');assert(await canvas.evaluate(x=>x.scrollWidth>x.clientWidth),'mobile diagrams should scroll');
  await canvas.focus();await p.keyboard.press('ArrowRight');await p.waitForFunction(()=>document.querySelector('#sequence-canvas').scrollLeft>0);
  await p.waitForTimeout(250); // Let the browser finish its keyboard scroll before changing views.
  await p.locator('[data-sequence-side="sdk"]').click();
  assert(await p.locator('#sequence-canvas .sdk').isVisible()&&await p.locator('#sequence-canvas .daw').isHidden(),'SDK large view');
  await p.locator('[data-sequence-side="daw"]').click();
  assert(await canvas.evaluate(x=>x.scrollLeft<3),'DAW shortcut');
  assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'mobile body overflow');
  await p.screenshot({path:path.join(out,'07-mobile-sequence.png')});await p.keyboard.press('Escape');
 });
 await check('Stage evidence follows deposit detection, finalization and automatic sweep separately',async()=>{
  await p.setViewportSize({width:1440,height:1100});await p.goto(url);
  const hrefs=()=>p.locator('#sequence-evidence a').evaluateAll(links=>links.map(a=>a.getAttribute('href')));
  await p.locator('[data-open-sequence="sequence-normal-2"]').click();
  let sources=await hrefs();assert(sources.some(x=>x.includes('page-31'))&&!sources.some(x=>x.includes('page-32')||x.includes('page-46')),'detection sources');
  await p.locator('[data-sequence-next]').click();sources=await hrefs();
  assert(sources.some(x=>x.includes('page-32'))&&!sources.some(x=>x.includes('page-31')||x.includes('page-46')),'finalization sources');
  await p.locator('[data-sequence-next]').click();sources=await hrefs();
  assert((await p.locator('#sequence-title').innerText()).includes('자동스윕'),'automatic sweep title');
  assert(sources.some(x=>x.includes('page-46'))&&sources.some(x=>x.includes('sweeps-create')),'sweep sources');
  assert(!sources.some(x=>/page-(31|32|47)/.test(x)),'unrelated stage source');
  assert(sources.some(x=>x.includes('06-sweep.md'))&&sources.some(x=>x.includes('openapi.yaml')),'batch execution sources missing');
  const left=await p.locator('#sequence-canvas .daw').innerText();
  assert(left.includes('자동스윕')&&left.includes('업무 방향은 원장 설계')&&left.includes('POST /sweeps')&&left.includes('batchSweep'),'unified automatic sweep missing');
  const [popup]=await Promise.all([context.waitForEvent('page'),p.locator('#sequence-evidence a[href*="page-46"]').click()]);
  await popup.waitForLoadState();assert(popup.url().includes('page-46.html'),'automatic sweep evidence');await popup.close();
  await p.screenshot({path:path.join(out,'08-automatic-sweep.png')});
  await p.locator('[data-sequence-next]').click();assert((await p.locator('#sequence-title').innerText()).includes('정보 미확인'),'wrong next stage');
  await p.keyboard.press('Escape');
 });
 await check('Automatic sweep includes batch execution in one stage on desktop and mobile',async()=>{
  for(const width of [1440,390]){
   await p.setViewportSize({width,height:width===390?844:1100});await p.goto(url);
   assert(await p.locator('[data-open-sequence]').count()===24,'extra sequence outside the flows');
   assert(await p.locator('#batch-supplement').count()===0,'separate batch supplement remains');
   await p.locator('[data-open-sequence="sequence-normal-4"]').click();
   assert((await p.locator('#sequence-position').innerText()).startsWith('전체 4 / 24'),'unified stage position');
   assert(await p.locator('[data-sequence-prev]').isEnabled()&&await p.locator('[data-sequence-next]').isEnabled(),'stage navigation unavailable');
   const left=await p.locator('#sequence-canvas .daw').innerText(),right=await p.locator('#sequence-canvas .sdk').innerText();
   assert(left.includes('BCM 배치 실행')&&left.includes('batchSweep')&&left.includes('transferFrom')&&left.includes('/completion'),'batch sequence missing');
   assert(right.includes('입금 1건')&&right.includes('계약은 확인되지 않는다'),'SDK batch caveat missing');
   const refs=await p.locator('#sequence-evidence a').evaluateAll(a=>a.map(x=>x.getAttribute('href')));
   assert(refs.some(x=>x.includes('06-sweep.md'))&&refs.some(x=>x.includes('openapi.yaml'))&&refs.some(x=>x.includes('page-46')),'unified evidence missing');
   const policy=await p.locator('#sequence-canvas .sdk svg desc').textContent();
   assert(policy.includes('정책 평가')&&policy.includes('서명 인가 발급 (TTL · 1회용)'),'policy steps missing');
   const clipped=await p.locator('#sequence-canvas svg').evaluateAll(svgs=>svgs.some(svg=>[...svg.querySelectorAll('text')].some(t=>{const b=t.getBBox();return b.x<0||b.x+b.width>svg.viewBox.baseVal.width+1})));
   assert(!clipped,'batch diagram text clipped');
   await p.locator('[data-sequence-side="sdk"]').click();await p.locator('[data-sequence-side="daw"]').click();
   await p.screenshot({path:path.join(out,`09-batch-${width}.png`)});
   await p.keyboard.press('Escape');assert(await p.locator('[data-open-sequence="sequence-normal-4"]').evaluate(x=>x===document.activeElement),'stage focus restore');
   assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'stage body overflow');
  }
 });
 await check('Detailed SDK sweep preserves every source message, note and participant',async()=>{
  const markdown=fs.readFileSync(path.resolve(root,'../wallet-sdk/fund-flows/sweep.md'),'utf8');
  const blocks=[...markdown.matchAll(/```mermaid\n([\s\S]*?)```/g)].map(x=>x[1]);
  assert(blocks.length===2,'source diagrams changed');
  const normalize=s=>s.replace(/<br\s*\/?>/g,' ').replace(/\s+/g,' ').trim();
  const expected=[],notes=[],actors=new Set();let activations=0;
  for(const block of blocks)for(const line of block.split('\n').map(x=>x.trim())){
   const message=line.match(/^(\w+)\s*(--?>>)\s*([+-]?)(\w+)\s*:\s*(.+)$/);
   if(message){expected.push({from:message[1],to:message[4],arrow:message[2],label:normalize(message[5])});if(message[3]==='+')activations++}
   const note=line.match(/^Note (?:over|right of) [\w, ]+\s*:\s*(.+)$/);if(note)notes.push(normalize(note[1]));
   const actor=line.match(/^participant (\w+) as /);if(actor)actors.add(actor[1]);
  }
  await p.setViewportSize({width:1440,height:1100});await p.goto(url);
  await p.locator('[data-open-sequence="sequence-normal-4"]').click();
  const actual=await p.locator('.sdk-detail .source-message').evaluateAll(nodes=>nodes.map(n=>({from:n.dataset.from,to:n.dataset.to,arrow:n.dataset.arrow,label:n.dataset.sourceLabel.replace(/\s+/g,' ').trim()})));
  assert(expected.length===30&&JSON.stringify(actual)===JSON.stringify(expected),'SDK calls omitted, reordered or merged');
  const actualNotes=await p.locator('.sdk-detail .source-note').evaluateAll(nodes=>nodes.map(n=>n.dataset.sourceLabel.replace(/\s+/g,' ').trim()));
  assert(JSON.stringify(actualNotes)===JSON.stringify(notes),'SDK notes changed');
  const actualActors=await p.locator('.sdk-detail [data-actor]').evaluateAll(nodes=>nodes.map(n=>n.dataset.actor));
  assert(actors.size===9&&JSON.stringify(actualActors.sort())===JSON.stringify([...actors].sort()),'SDK participants merged');
  assert(await p.locator('.sdk-detail .activation').count()===activations,'source activation missing');
  const previewWidth=(await p.locator('.sdk-detail').boundingBox()).width;
  await p.locator('[data-sequence-side="sdk"]').click();
  assert((await p.locator('.sdk-detail').boundingBox()).width>previewWidth*1.8,'SDK large view did not enlarge diagram');
  await p.screenshot({path:path.join(out,'10-sdk-full-start.png')});
  await p.locator('[data-sequence-browse]').click();
  await p.locator('#sequence-phase').selectOption('25');
  for(let i=0;i<4;i++)await p.locator('[data-call-next]').click();
  await p.screenshot({path:path.join(out,'11-sdk-full-end.png')});
  await p.keyboard.press('Escape');
 });
 await check('Long sequences offer optional phase navigation and complete call text',async()=>{
  for(const width of [1440,390]){
   await p.setViewportSize({width,height:width===390?844:1100});await p.goto(url);
   await p.locator('[data-open-sequence="sequence-normal-4"]').click();
   assert(await p.locator('#sequence-reader').isHidden(),'call reader crowds initial diagram');
   await p.locator('[data-sequence-side="sdk"]').click();
   await p.locator('[data-sequence-browse]').click();
   assert(await p.locator('#sequence-reader').isVisible(),'SDK call reader missing');
   const count=await p.locator('.sdk-detail .source-message').count();
   assert(count===30,'reader removed calls');
   for(const start of [0,6,14,21,25]){
    await p.locator('#sequence-phase').selectOption(String(start));
    assert(await p.locator('#sequence-call-position').innerText()===`${start+1} / 30`,'phase location');
    const selected=p.locator('.sdk-detail .source-message').nth(start);
    const original=await selected.getAttribute('data-source-label');
    assert(await p.locator('#sequence-call-text').textContent()===original,'call text shortened');
    assert(await selected.evaluate(x=>x.classList.contains('is-selected')),'selected call not highlighted');
    const rect=await selected.boundingBox(),area=await p.locator('#sequence-canvas').boundingBox();
    assert(rect.y>=area.y+20&&rect.y<area.y+area.height,'call is outside canvas');
    assert(await p.locator('.sdk-detail .source-message').count()===count,'phase omitted calls');
   }
   await p.locator('[data-call-next]').click();assert(await p.locator('#sequence-call-position').innerText()==='27 / 30','next call');
   await p.locator('[data-call-prev]').click();assert(await p.locator('#sequence-call-position').innerText()==='26 / 30','previous call');
   for(let i=0;i<4;i++)await p.locator('[data-call-next]').click();assert(await p.locator('[data-call-next]').isDisabled(),'last call boundary');
   assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'reader page overflow');
   await p.screenshot({path:path.join(out,`12-call-reader-${width}.png`)});
   await p.locator('[data-sequence-prev]').click();assert(await p.locator('#sequence-reader').isHidden(),'SDK controls leaked into another stage');
   await p.keyboard.press('Escape');
  }
 });
 await check('Large diagram view fills the window, zooms to 400% and restores its size',async()=>{
  for(const width of [2560,1440,390]){
   const height=width===2560?1440:width===390?844:1100;
   await p.setViewportSize({width,height});await p.goto(url);
   await p.locator('[data-open-sequence="sequence-normal-4"]').click();
   const dialog=p.locator('#sequence-dialog'),maximize=p.locator('[data-sequence-maximize]');
   const original=await dialog.boundingBox();
   if(width>760){
    assert(original.width<=1540&&original.height<=1100,'normal dialog exceeds reading size');
    await maximize.click();
    assert(await maximize.getAttribute('aria-pressed')==='true','expanded state missing');
   }else assert(await maximize.isHidden(),'redundant mobile maximize control');
   const expanded=await dialog.boundingBox();
   assert(Math.abs(expanded.x)<1&&Math.abs(expanded.y)<1&&Math.abs(expanded.width-width)<1&&Math.abs(expanded.height-height)<1,'full window bounds');
   await p.locator('[data-sequence-browse]').click();
   const svg=p.locator('.sdk-detail'),base=await svg.boundingBox();
   for(let i=0;i<12;i++)await p.locator('[data-sequence-zoom="+"]').click();
   assert(await p.locator('#sequence-zoom-level').textContent()==='400%','maximum zoom');
   assert(await p.locator('[data-sequence-zoom="+"]').isDisabled(),'zoom limit unavailable');
   assert((await svg.boundingBox()).width>=base.width*3.99,'diagram did not enlarge');
   await p.locator('#sequence-phase').selectOption('25');
   assert(await p.locator('#sequence-call-position').innerText()==='26 / 30','zoom lost call navigation');
   const selected=await p.locator('.sdk-detail .is-selected').boundingBox(),area=await p.locator('#sequence-canvas').boundingBox();
   assert(area.height>100&&selected.y>=area.y&&selected.y<area.y+area.height,'zoomed call not reachable');
   assert(await p.locator('.sdk-detail .source-message').count()===30,'zoom removed source content');
   await p.locator('[data-sequence-zoom="fit"]').click();
   assert(await p.locator('#sequence-zoom-level').textContent()==='100%','reset zoom');
   await p.locator('[data-sequence-zoom="+"]').click();
   await p.locator('[data-sequence-zoom="+"]').click();
   await p.locator('#sequence-phase').selectOption('14');
   await p.screenshot({path:path.join(out,`13-large-view-${width}.png`)});
   if(width>760){
    await maximize.click();const restored=await dialog.boundingBox();
    assert(Math.abs(restored.width-original.width)<1&&Math.abs(restored.height-original.height)<1,'restore original bounds');
    assert(await maximize.getAttribute('aria-pressed')==='false','restore button state');
   }
   await p.keyboard.press('Escape');
   assert(await p.locator('[data-open-sequence="sequence-normal-4"]').evaluate(x=>x===document.activeElement),'large view focus restore');
   assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'large view body overflow');
  }
 });
 await check('Reported wide-screen regression: normal spacing stays stable and SDK opens large without losing calls',async()=>{
  for(const width of [1440,1920,3766]){
   await p.setViewportSize({width,height:1280});await p.goto(url);
   await p.locator('[data-open-sequence="sequence-normal-2"]').click();
   assert(await p.locator('.sequence-participants').count()===0,'duplicate actor row leaves blank space');
   const scales=await p.locator('#sequence-canvas svg').evaluateAll(svgs=>svgs.map(s=>s.getBoundingClientRect().width/s.viewBox.baseVal.width));
   assert(scales.every(x=>x>.9&&x<1.1),'normal diagram enlarged by window width');
   assert(await p.locator('#sequence-canvas .daw text[y="44"]').first().isVisible(),'original participant header missing');
   await p.locator('[data-sequence-maximize]').click();
   const expandedScales=await p.locator('#sequence-canvas svg').evaluateAll(svgs=>svgs.map(s=>s.getBoundingClientRect().width/s.viewBox.baseVal.width));
   assert(expandedScales.every(x=>x<1.1),'full window stretches ordinary diagrams');
   await p.screenshot({path:path.join(out,`14-restored-normal-${width}.png`)});
   await p.keyboard.press('Escape');await p.locator('[data-open-sequence="sequence-normal-4"]').click();
   const area=await p.locator('#sequence-canvas').boundingBox();
   const preview=await p.locator('.sdk-detail').boundingBox();
   assert(preview.x>=area.x&&preview.x+preview.width<=area.x+area.width,'paired SDK right side cut off');
   assert(await p.locator('#sequence-canvas').evaluate(x=>x.scrollTop===0),'diagram opens halfway down');
   assert(await p.locator('#sequence-reader').isHidden(),'call panel takes space before requested');
   await p.locator('[data-sequence-side="sdk"]').click();
   const enlarged=await p.locator('.sdk-detail').boundingBox();
   assert(enlarged.width>preview.width*1.8,'large view merely pans instead of enlarging');
   const bounds=await p.locator('#sequence-canvas').boundingBox();
   assert(enlarged.x>=bounds.x&&enlarged.x+enlarged.width<=bounds.x+bounds.width,'SDK participants cut off in large view');
   assert(await p.locator('.sdk-detail .source-message').count()===30,'large view omits source calls');
   assert(await p.locator('.sdk-detail [data-actor]').count()===9,'large view merges participants');
   await p.screenshot({path:path.join(out,`15-sdk-large-${width}.png`)});
   await p.locator('[data-sequence-side="both"]').click();
   assert(await p.locator('#sequence-canvas .sequence-card:visible').count()===2,'return to paired comparison');
   await p.keyboard.press('Escape');
  }
 });
 await check('Stage differences appear above diagrams, follow navigation and leave common stages unmarked',async()=>{
  for(const width of [1440,390]){
   await p.setViewportSize({width,height:width===390?844:1100});await p.goto(url);
   const count=await p.locator('template[id^="sequence-"]').evaluateAll(templates=>templates.filter(t=>t.content.querySelector('.sequence-difference')).length);
   assert(count===22,'differences missing or invented for common stages');
   await p.locator('[data-open-sequence="sequence-normal-1"]').click();
   assert(await p.locator('.sequence-difference').count()===0,'common deposit marked as different');
   for(let i=0;i<3;i++)await p.locator('[data-sequence-next]').click();
   const summary=p.locator('.sequence-difference');
   assert((await summary.innerText()).includes('배치')&&(await summary.innerText()).includes('입금 한 건'),'wrong sweep comparison');
   assert((await summary.innerText()).includes('문서에서 확인되지 않는다'),'unconfirmed batch support stated as implemented');
   const box=await summary.boundingBox(),pair=await p.locator('.sequence-pair').boundingBox(),area=await p.locator('#sequence-canvas').boundingBox();
   assert(box.y>=area.y&&box.y+box.height<=pair.y,'summary not above diagrams');
   assert(box.x>=area.x&&box.x+box.width<=area.x+area.width,'summary overflows canvas');
   assert(box.height<240,'summary too tall');
   await p.screenshot({path:path.join(out,`16-stage-difference-${width}.png`)});
   await p.locator('[data-sequence-side="sdk"]').click();
   assert(await summary.isVisible(),'difference lost in large view');
   assert(await p.locator('.sdk-detail .source-message').count()===30,'difference replaced source calls');
   await p.locator('#sequence-canvas').evaluate(x=>x.scrollTop=600);
   assert((await summary.boundingBox()).y<(await p.locator('#sequence-canvas').boundingBox()).y,'summary remains fixed over diagram');
   await p.locator('[data-sequence-prev]').click();
   assert((await summary.innerText()).includes('FROZEN')&&!(await summary.innerText()).includes('배치'),'previous stage difference stale');
   await p.keyboard.press('Escape');
   await p.locator('[data-open-sequence="sequence-suspense-3"]').click();
   assert((await summary.innerText()).includes('문서상 미구현'),'implementation caveat lost');
   await p.keyboard.press('Escape');
   await p.locator('[data-open-sequence="sequence-return-after-1"]').click();
   assert(await p.locator('.sequence-difference').count()===0,'common custody marked as different');
   await p.keyboard.press('Escape');
  }
 });
 await check('SDK replacement retains CORE work and separates known calls from unconfirmed interfaces',async()=>{
  await p.setViewportSize({width:1440,height:1100});await p.goto(url);
  const open=async id=>{if(await p.locator('#sequence-dialog').evaluate(x=>x.open))await p.keyboard.press('Escape');await p.locator(`[data-open-sequence="sequence-${id}"]`).click()};
  const sdkText=()=>p.locator('#sequence-canvas .sdk').innerText();
  const messages=()=>p.locator('#sequence-canvas .sdk .sequence-message').evaluateAll(nodes=>nodes.map(x=>({from:x.dataset.from,to:x.dataset.to,kind:x.dataset.kind,text:x.textContent})));
  for(const id of ['normal-2','normal-3','suspense-2']){
   await open(id);const data=await messages();
   assert(data.some(x=>x.from==='WALLET-SDK'&&x.to==='DAW-CORE'&&x.kind==='event'&&x.text.includes('DEPOSIT_')),'documented webhook absent');
   assert(data.some(x=>x.from==='DAW-CORE'&&x.kind==='core'&&x.text.includes('입금중')),'CORE ledger work omitted');
   assert(!data.some(x=>x.from==='DAW-CORE'&&x.to==='WALLET-SDK'),'invented SDK balance update API');
   assert((await sdkText()).includes('대응 API는 확인되지')||(await sdkText()).includes('계약은 확인되지'),'missing interface caveat');
  }
  await open('normal-3');assert((await messages()).some(x=>x.from==='DAW-CORE'&&x.to==='서비스'&&/입금완료\s*통지/.test(x.text)),'CORE service notification omitted');
  await p.screenshot({path:path.join(out,'17-core-deposit-comparison.png')});
  for(const id of ['suspense-3','suspense-4','return-after-2','return-after-3','company-2','company-3']){
   await open(id);const data=await messages();
   assert(data.some(x=>x.kind==='core'),'shared CORE stage left empty');
   assert(!data.some(x=>x.kind==='core'&&[x.from,x.to].includes('WALLET-SDK')),'CORE marker used for SDK contract');
   if(id==='suspense-3')assert(!data.some(x=>x.text.includes('DEPOSIT_UPDATED'))&&(await sdkText()).includes('아직 구현되지'),'unimplemented UPDATED drawn as active');
   if(id.startsWith('return-after'))assert(!data.some(x=>[x.from,x.to].some(a=>a==='WALLET-SDK'||a==='블록체인')),'unconfirmed return executed');
  }
  await open('sweep-2');assert((await messages()).some(x=>x.from==='WALLET-SDK'&&x.to==='DAW-CORE'&&x.text.includes('SWEEP_INITIATED')&&x.kind==='event'),'known sweep notification omitted');
  for(const id of ['company-1','company-4']){
   await open(id);const boundary=(await messages()).filter(x=>x.from==='WALLET-SDK'&&x.to==='DAW-CORE');
   assert(boundary.length===1&&boundary[0].kind==='proposal','Workspace result link presented as a confirmed API');
   assert(!boundary[0].text.includes('DEPOSIT_FINALIZED'),'Account webhook reused for Workspace');
  }
  await p.keyboard.press('Escape');
 });
 await check('eventId details open over the sequence and restore its view, focus and scroll',async()=>{
  for(const width of [1440,390]){
   await p.setViewportSize({width,height:width===390?844:1100});await p.goto(url);
   const detail=p.locator('#event-details-dialog'),canvas=p.locator('#sequence-canvas');
   assert(await detail.isHidden(),'details open without request');
   await p.locator('[data-open-sequence="sequence-normal-3"]').click();
   await p.locator('[data-sequence-side="sdk"]').click();
   await p.locator('[data-sequence-zoom="+"]').click();
   const node=p.locator('.sequence-message[data-event-details]');
   await node.scrollIntoViewIfNeeded();await node.focus();
   const before=await canvas.evaluate(x=>({left:x.scrollLeft,top:x.scrollTop}));
   await p.keyboard.press('Enter');
   assert(await detail.evaluate(x=>x.open),'sequence message does not open details');
   assert(await p.locator('#sequence-dialog').evaluate(x=>x.open),'underlying sequence closed');
   assert((await detail.innerText()).includes('지원 여부 미확인')&&(await detail.innerText()).includes('같은 eventId'),'requirement content missing');
   assert(await p.locator('#sdk-event-id').count()===1,'duplicated requirement body');
   await p.locator('#event-details-content').evaluate(x=>x.scrollTop=x.scrollHeight);
   assert(await detail.locator('[data-event-details-close]').isVisible(),'close control scrolled away');
   await p.screenshot({path:path.join(out,`19-event-details-${width}.png`)});
   await p.keyboard.press('Escape');
   assert(await detail.isHidden()&&await p.locator('#sequence-dialog').evaluate(x=>x.open),'Escape closed parent sequence');
   assert(await node.evaluate(x=>x===document.activeElement),'diagram focus not restored');
   const after=await canvas.evaluate(x=>({left:x.scrollLeft,top:x.scrollTop}));
   assert(Math.abs(after.left-before.left)<2&&Math.abs(after.top-before.top)<2,'diagram scroll changed');
   assert(await p.locator('#sequence-zoom-level').innerText()==='125%','diagram zoom reset');
   await p.keyboard.press('Space');assert(await detail.evaluate(x=>x.open),'Space activation');
   await detail.locator('[data-event-details-close]').click();
   await p.keyboard.press('Escape');
   await p.locator('[data-open-sequence="sequence-normal-4"]').click();
   await p.locator('.sequence-requirement-link [data-event-details]').click();
   assert(await detail.evaluate(x=>x.open),'sweep requirement button missing');
   await detail.locator('[data-event-details-close]').click();
   assert(await p.locator('.sdk-detail .source-message').count()===30,'modal changed full source diagram');
   await p.keyboard.press('Escape');
   await p.locator('.section-nav a[href="#sdk-event-id"]').click();
   assert(await detail.evaluate(x=>x.open),'navigation shortcut broken');
   await p.keyboard.press('Escape');
   await p.waitForFunction(()=>!document.getElementById('event-details-dialog').open&&!document.body.classList.contains('event-details-active'));
   assert(await p.locator('.section-nav a[href="#sdk-event-id"]').evaluate(x=>x===document.activeElement),'shortcut focus not restored');
   assert(await p.evaluate(()=>document.documentElement.scrollWidth<=innerWidth),'details cause page overflow');
  }
  await p.goto(url+'#sdk-event-id');
  assert(await p.locator('#event-details-dialog').evaluate(x=>x.open),'existing deep link broken');
  await p.keyboard.press('Escape');
 });
 await check('SDK Queue requirements distinguish delivery guarantees, receipt ACK and CORE completion',async()=>{
  for(const width of [1440,390]){
   await p.setViewportSize({width,height:width===390?844:1100});await p.goto(url);
   await p.locator('[data-open-sequence="sequence-normal-3"]').click();
   const deliveryNode=p.locator('.sequence-message[data-queue-details]');
   assert(await deliveryNode.getAttribute('data-kind')==='proposal','delivery guarantee presented as implemented');
   await deliveryNode.scrollIntoViewIfNeeded();await deliveryNode.focus();await p.keyboard.press('Enter');
   const detail=p.locator('#event-details-dialog');
   assert(await detail.evaluate(x=>x.open),'queue requirement node not actionable');
   assert(await p.locator('#sdk-event-id').isHidden()&&await p.locator('#sdk-event-delivery').isVisible(),'wrong requirement shown');
   const body=await p.locator('#sdk-event-delivery').innerText();
   for(const term of ['영속 보관','같은 eventId','전달 순서','DLQ','2xx는 CORE 수신 확인','별도 업무 완료 확인 계약','수신 저장소(Inbox)','Queue 제거를 확정하지 않는다'])assert(body.includes(term),'missing boundary: '+term);
   await p.screenshot({path:path.join(out,`20-queue-requirement-${width}.png`)});
   await p.keyboard.press('Escape');
   await p.waitForFunction(()=>!document.body.classList.contains('event-details-active'));
   assert(await deliveryNode.evaluate(x=>x===document.activeElement),'queue node focus lost');
   await p.locator('.sequence-requirement-link [data-event-details]').click();
   assert(await p.locator('#sdk-event-id').isVisible()&&await p.locator('#sdk-event-delivery').isHidden(),'requirements bleed across dialog opens');
   await p.keyboard.press('Escape');await p.waitForFunction(()=>!document.body.classList.contains('event-details-active'));
   await p.keyboard.press('Escape');
   await p.locator('[data-open-sequence="sequence-normal-4"]').click();
   await p.locator('.sequence-requirement-link [data-queue-details]').click();
   assert(await p.locator('#sdk-event-delivery').isVisible(),'full sweep has no queue requirement');
   assert(await p.locator('.sdk-detail .source-message').count()===30,'queue requirement alters source sweep');
   await p.keyboard.press('Escape');await p.waitForFunction(()=>!document.body.classList.contains('event-details-active'));await p.keyboard.press('Escape');
  }
  await p.goto(url+'#sdk-event-delivery');
  assert(await p.locator('#event-details-dialog').evaluate(x=>x.open)&&await p.locator('#sdk-event-delivery').isVisible(),'queue deep link');
  await p.emulateMedia({media:'print'});
  assert(await p.locator('#sdk-event-id').isVisible()&&await p.locator('#sdk-event-delivery').isVisible(),'print loses unselected requirement');
  await p.emulateMedia({media:'screen'});await p.keyboard.press('Escape');
  const c=await browser.newContext({javaScriptEnabled:false,viewport:{width:390,height:844}}),n=await c.newPage();await n.goto(url);
  assert(await n.locator('#sdk-event-id').isVisible()&&await n.locator('#sdk-event-delivery').isVisible(),'no-script requirements missing');await c.close();
 });
 report.summary={checks:report.checks.length,failed:report.checks.filter(x=>!x.passed).length,errors:report.errors.length,externalRequests:report.external.length};
 fs.writeFileSync(path.join(out,'report.json'),JSON.stringify(report,null,2));console.log('SUMMARY',JSON.stringify(report.summary));
 await browser.close();if(report.summary.failed||report.summary.errors||report.summary.externalRequests)process.exitCode=1;
})().catch(e=>{console.error(e);process.exit(1)});
