const assert = require('node:assert/strict');
const path = require('node:path');
const {pathToFileURL} = require('node:url');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
(async () => {
  const browser = await chromium.launch({headless:true, executablePath:process.env.CHROME_BIN || '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome'});
  try {
    const page = await browser.newPage({viewport:{width:1440,height:1100}});
    const errors = [], external = [];
    page.on('pageerror', e => errors.push(e.message));
    await page.route('**/*', r => { if (/^https?:/.test(r.request().url())) { external.push(r.request().url()); return r.abort(); } return r.continue(); });
    await page.goto(pathToFileURL(path.resolve(__dirname, '../../docs/flow-comparison/index.html')).href);
    await page.locator('[data-open-sequence="sequence-normal-4"]').click();
    const modal = page.locator('#diagram-zoom-dialog');
    for (const side of ['daw', 'sdk']) {
      const trigger = page.locator(`#sequence-canvas .${side} [data-diagram-open]`);
      const figure = page.locator(`#sequence-canvas .${side} .sequence-figure`);
      const before = await figure.innerHTML();
      await page.locator('[data-sequence-zoom="+"]').click();
      await trigger.scrollIntoViewIfNeeded();
      const scroll = await page.locator('#sequence-canvas').evaluate(x => [x.scrollLeft,x.scrollTop]);
      const parentZoom = await page.locator('#sequence-zoom-level').evaluate(x => x.value);
      await trigger.focus();
      await page.keyboard.press('Enter');
      assert(await modal.evaluate(x => x.open));
      assert.equal(await modal.locator('.sequence-figure').innerHTML(), before);
      const initial = await modal.locator('svg').evaluate(x => x.getBoundingClientRect().width);
      await page.locator('[data-diagram-zoom="+"]').click();
      assert(await modal.locator('svg').evaluate(x => x.getBoundingClientRect().width) > initial);
      await page.locator('[data-diagram-zoom="actual"]').click();
      assert.equal(await page.locator('#diagram-zoom-level').evaluate(x => x.value), '100%');
      await page.locator('[data-diagram-zoom="fit"]').click();
      assert(await page.locator('#diagram-zoom-viewport').evaluate(x => x.scrollWidth <= x.clientWidth + 1));
      await page.keyboard.press('Escape');
      assert(await modal.isHidden());
      assert(await page.locator('#sequence-dialog').evaluate(x => x.open));
      assert.equal(await figure.innerHTML(), before);
      assert.equal(await page.locator('#sequence-zoom-level').evaluate(x => x.value), parentZoom);
      assert.deepEqual(await page.locator('#sequence-canvas').evaluate(x => [x.scrollLeft,x.scrollTop]), scroll);
      assert(await trigger.evaluate(x => x === document.activeElement));
    }
    console.log('PASS both diagrams, keyboard entry, zoom/fit, exact SVG preservation and parent state restoration');
    await page.locator('#sequence-canvas .daw .sequence-figure svg').click({position:{x:12,y:12}});
    assert(await modal.evaluate(x => x.open));
    await page.mouse.click(2,2);
    assert(await modal.isHidden());
    const sdk = page.locator('#sequence-canvas .sdk [data-diagram-open]');
    for (const width of [320,390]) {
      await page.setViewportSize({width,height:844});
      await sdk.click();
      await page.locator('[data-diagram-zoom="actual"]').click();
      assert(await page.locator('#diagram-zoom-viewport').evaluate(x => x.scrollWidth > x.clientWidth));
      assert(await modal.evaluate(x => x.getBoundingClientRect().width <= innerWidth));
      await page.locator('#diagram-zoom-viewport').focus();
      await page.keyboard.press('ArrowRight');
      await page.waitForFunction(() => document.getElementById('diagram-zoom-viewport').scrollLeft > 0);
      await page.locator('[data-diagram-close]').click();
    }
    console.log('PASS diagram click, backdrop close and 320/390px scrolling');
    await page.setViewportSize({width:1440,height:1100});
    await sdk.click();
    await page.screenshot({path:'/tmp/flow-comparison-zoom.png'});
    await page.evaluate(() => dispatchEvent(new Event('beforeprint')));
    assert(await modal.isHidden());
    assert.equal(await page.locator('#sequence-canvas .sdk .source-message').count(),30);
    await page.evaluate(() => dispatchEvent(new Event('afterprint')));
    assert.deepEqual(errors,[]);
    assert.deepEqual(external,[]);
    console.log('PASS print restoration, all 30 SDK calls, no browser errors or external requests');
  } finally { await browser.close(); }
})().catch(e => { console.error(e); process.exitCode = 1; });
