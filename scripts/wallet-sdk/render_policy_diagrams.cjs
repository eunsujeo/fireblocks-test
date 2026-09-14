// Render editorial Mermaid sources with the SDK's existing offline runtime.
const fs = require('fs'), path = require('path'), {pathToFileURL} = require('url');
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const root = path.resolve(__dirname, '../../docs/wallet-sdk');
(async () => {
  const browser = await chromium.launch({headless:true, ...(process.env.CHROME_BIN ? {executablePath:process.env.CHROME_BIN} : {})});
  try {
    const page = await browser.newPage();
    await page.route('**/*', r => /^https?:/.test(r.request().url()) ? r.abort() : r.continue());
    await page.goto(pathToFileURL(path.join(root, 'policy/signing-gate/index.html')).href);
    await page.waitForFunction(() => document.documentElement.dataset.offlineReady === 'true' && customElements.get('blume-mermaid'));
    const out = path.join(root, '_guide/diagrams');
    fs.mkdirSync(out, {recursive:true});
    for (const name of ['policy-evaluation', 'policy-signing']) {
      const source = fs.readFileSync(path.join(__dirname, name + '.mmd'), 'utf8');
      await page.evaluate(source => {
        document.querySelector('#editorial-diagram')?.remove();
        const el = document.createElement('blume-mermaid');
        el.id = 'editorial-diagram';
        el.dataset.source = source;
        document.body.append(el);
      }, source);
      await page.waitForFunction(() => document.querySelector('#editorial-diagram svg'));
      const svg = await page.locator('#editorial-diagram svg').evaluate(el => {
        el.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
        return new XMLSerializer().serializeToString(el);
      });
      fs.writeFileSync(path.join(out, name + '.svg'), svg);
      console.log('Rendered', name);
    }
  } finally { await browser.close(); }
})().catch(error => { console.error(error); process.exitCode = 1; });
