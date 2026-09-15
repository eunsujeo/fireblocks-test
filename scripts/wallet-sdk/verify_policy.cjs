// Run after verify_references.py to test the actual extracted sharing archive.
const fs = require('fs'), path = require('path'), assert = require('node:assert/strict');
const {pathToFileURL} = require('url');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const {root} = JSON.parse(fs.readFileSync('/tmp/wallet-reference-test.json'));
const definitions = JSON.parse(fs.readFileSync(path.join(__dirname, 'policy-terms.json')));
const url = pathToFileURL(path.join(root, '_guide/policy.html')).href;
(async () => {
  const browser = await chromium.launch({headless:true, ...(process.env.CHROME_BIN ? {executablePath:process.env.CHROME_BIN} : {})});
  const errors = [], external = [];
  try {
    const context = await browser.newContext({viewport:{width:1440,height:1000}, reducedMotion:'reduce'});
    await context.route('**/*', r => {
      if (/^https?:/.test(r.request().url())) {external.push(r.request().url()); return r.abort();}
      return r.continue();
    });
    const page = await context.newPage();
    page.on('pageerror', e => errors.push(e.message));
    page.on('requestfailed', r => errors.push(r.url() + ' ' + r.failure().errorText));
    await page.goto(url);
    await page.waitForFunction(() => document.documentElement.dataset.guideReady === 'true');
    assert.equal(await page.locator('main > section').first().getAttribute('id'), 'roles');
    assert.equal(await page.locator('#roles tbody tr').count(), 4);
    const tip = page.locator('#policy-term-explanation');
    for (const [word, definition] of Object.entries(definitions)) {
      const term = page.locator('#roles .policy-term').filter({hasText:new RegExp(`^${word}$`)}).first();
      await term.hover();
      assert.equal(await tip.innerText(), definition);
      assert.equal(await term.getAttribute('aria-describedby'), 'policy-term-explanation');
      await tip.hover();
      assert(await tip.isVisible());
      await page.keyboard.press('Escape');
      assert(await tip.isHidden());
      await page.locator('.search-open').focus();
      await term.focus();
      assert(await tip.isVisible());
      await page.keyboard.press('Escape');
      assert(await tip.isHidden());
      assert(await term.evaluate(x => x === document.activeElement));
      for (const key of ['Enter', 'Space']) {
        await page.keyboard.press(key);
        assert(await tip.isVisible());
        await page.keyboard.press('Escape');
      }
      await page.keyboard.press('Tab');
    }
    console.log('PASS four term definitions, hover, tooltip hover, focus, Enter/Space and Escape');
    const rule = JSON.parse(await page.locator('#withdrawal-policy-json').innerText());
    const source = fs.readFileSync(path.join(root,'policy/examples.md'),'utf8').match(/```json\n([\s\S]*?)\n```/)[1];
    assert.deepEqual(rule, JSON.parse(source));
    assert.equal(await page.locator('pre .policy-term').count(), 0);
    assert.equal(await page.locator('#signing-example tbody tr').count(), 6);
    for (const id of ['withdrawal-example','signing-example']) {
      assert.equal(await page.locator(`.mobile-toc a[href="#${id}"]`).count(), 1);
      assert.equal(await page.locator(`.toc a[href="#${id}"]`).count(), 1);
    }
    console.log('PASS exact source rule JSON, signing examples and both tables of contents');
    await page.locator('#withdrawal-example .source').first().click();
    await page.waitForFunction(() => document.querySelector('#reference-dialog').open && document.querySelector('.reference-loading').hidden);
    assert((await page.locator('#reference-frame').getAttribute('src')).includes('policy--examples.html'));
    await page.locator('[data-reference-close]').click();
    await page.locator('.search-open').click();
    await page.locator('#query').fill('Orchestrator');
    await page.locator('#results a[href$="/_guide/policy.html"]').waitFor();
    await page.locator('#search-close').click();
    console.log('PASS example reference modal and updated guide search');
    await page.locator('#withdrawal-example').scrollIntoViewIfNeeded();
    await page.screenshot({path:'/tmp/wallet-policy-desktop.png'});
    for (const width of [320,390]) {
      await page.setViewportSize({width,height:844});
      const term = page.locator('#roles .policy-term').first();
      await term.click();
      assert(await tip.isVisible());
      const b = await tip.boundingBox();
      assert(b.x >= 10 && b.x + b.width <= width - 10 && b.y >= 10 && b.y + b.height <= 834);
      assert(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth));
      await page.screenshot({path:`/tmp/wallet-policy-${width}.png`});
      await page.locator('h1').click();
      assert(await tip.isHidden());
    }
    console.log('PASS 320/390px tooltip bounds, touch/click dismissal and no page overflow');
    const offline = await browser.newContext({javaScriptEnabled:false});
    const fallback = await offline.newPage();
    await fallback.goto(url);
    for (const [word,definition] of Object.entries(definitions)) {
      assert.equal(await fallback.locator('#roles .policy-term').filter({hasText:new RegExp(`^${word}$`)}).first().getAttribute('title'),definition);
    }
    assert(await fallback.locator('#withdrawal-policy-json').isVisible());
    console.log('PASS no-JavaScript definitions and examples');
    assert.deepEqual(errors,[]);
    assert.deepEqual(external,[]);
  } finally {await browser.close();}
})().catch(e => {console.error(e); process.exitCode = 1;});
