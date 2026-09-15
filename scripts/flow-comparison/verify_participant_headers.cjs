const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1100}});
    const errors = [], external = [];
    page.on('pageerror', error => errors.push(error.message));
    await page.route('**/*', r => { if (/^https?:/.test(r.request().url())) { external.push(r.request().url()); return r.abort(); } return r.continue(); });
    await page.goto(pathToFileURL(path.resolve(__dirname,'../../docs/flow-comparison/index.html')).href);
    async function checkAlignment(dialogId, viewportId, expectedPanels) {
      // Wait for layout observers and the next painted header after a zoom/scroll.
      await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
      await page.waitForFunction(({dialogId,expectedPanels}) => document.querySelectorAll(`#${dialogId} .participant-panel`).length === expectedPanels, {dialogId,expectedPanels});
      const result = await page.evaluate(({dialogId,viewportId}) => {
        const dialog = document.getElementById(dialogId), viewport = document.getElementById(viewportId);
        const area = viewport.getBoundingClientRect();
        const svgs = [...viewport.querySelectorAll('.sequence-figure > svg')].filter(svg => svg.getBoundingClientRect().width);
        return [...dialog.querySelectorAll('.participant-panel')].map((panel,index) => {
          const labels = [...panel.querySelectorAll('.participant-label')];
          const actors = [...svgs[index].querySelectorAll('[data-actor]')];
          return {top:Math.abs(panel.getBoundingClientRect().top - area.top - viewport.clientTop),
            names:labels.map(label => label.textContent), expected:actors.map(actor => actor.querySelector('text').textContent),
            errors:labels.map((label,i) => Math.abs(label.getBoundingClientRect().left + label.getBoundingClientRect().width/2 - actors[i].querySelector('rect').getBoundingClientRect().left - actors[i].querySelector('rect').getBoundingClientRect().width/2))};
        });
      },{dialogId,viewportId});
      for (const panel of result) { assert(panel.top < 2); assert.deepEqual(panel.names,panel.expected); assert(panel.errors.every(error => error < 2),JSON.stringify(panel)); }
    }
    await page.locator('[data-open-sequence="sequence-normal-4"]').click();
    assert(await page.locator('#sequence-dialog .participant-overlay').isHidden());
    await page.locator('#sequence-canvas').evaluate(x => x.scrollTop = 800);
    await checkAlignment('sequence-dialog','sequence-canvas',2);
    await page.locator('[data-sequence-zoom="+"]').click();
    await page.locator('#sequence-canvas').evaluate(x => {x.scrollTop = 950; x.scrollLeft = 130;});
    await checkAlignment('sequence-dialog','sequence-canvas',2);
    await page.screenshot({path:'/tmp/participant-headers-comparison.png'});
    await page.locator('#sequence-canvas').evaluate(x => x.scrollTop = 0);
    await page.waitForFunction(() => document.querySelector('#sequence-dialog .participant-overlay').hidden);
    console.log('PASS paired headers appear only after scrolling, align through horizontal scroll and zoom, disappear at the top');
    await page.locator('#sequence-canvas .sdk [data-diagram-open]').click();
    assert(await page.locator('#diagram-zoom-dialog .participant-overlay').isHidden());
    await page.locator('[data-diagram-zoom="actual"]').click();
    await page.locator('#diagram-zoom-viewport').evaluate(x => {x.scrollTop=500;x.scrollLeft=300;});
    await checkAlignment('diagram-zoom-dialog','diagram-zoom-viewport',1);
    await page.locator('[data-diagram-zoom="+"]').click();
    await checkAlignment('diagram-zoom-dialog','diagram-zoom-viewport',1);
    await page.screenshot({path:'/tmp/participant-headers-zoom.png'});
    for (const width of [320,390]) {
      await page.setViewportSize({width,height:844});
      await page.locator('#diagram-zoom-viewport').evaluate(x => {x.scrollTop=800;x.scrollLeft=400;});
      await checkAlignment('diagram-zoom-dialog','diagram-zoom-viewport',1);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
    }
    await page.locator('[data-diagram-zoom="fit"]').click();
    await page.waitForFunction(() => document.querySelector('#diagram-zoom-dialog .participant-overlay').hidden);
    await page.keyboard.press('Escape');
    await page.waitForFunction(() => document.querySelector('#diagram-zoom-dialog .participant-overlay').hidden);
    assert.equal(await page.locator('#sequence-canvas .sdk .source-message').count(),30);
    console.log('PASS enlargement, 320/390px, fit reset, modal return and all 30 calls');
    await page.setViewportSize({width:1440,height:1100});
    await page.locator('[data-sequence-prev]').click();
    await page.locator('#sequence-canvas').evaluate(x => x.scrollTop=400);
    await checkAlignment('sequence-dialog','sequence-canvas',2);
    await page.emulateMedia({media:'print'});
    assert(await page.locator('#sequence-dialog .participant-overlay').isHidden());
    await page.emulateMedia({media:'screen'});
    assert.deepEqual(errors,[]); assert.deepEqual(external,[]);
    console.log('PASS stage change, print, no errors or external requests');
  } finally { await browser.close(); }
})().catch(error => {console.error(error);process.exitCode=1;});
