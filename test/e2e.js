const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage({ acceptDownloads: true });
  await p.goto('http://127.0.0.1:8080/');
  await p.setInputFiles('#pick', ['build/t/big.bin', 'build/t/phone.bin']);
  await p.fill('#t', 'https://example.com/ünï'); await p.press('#t', 'Enter');
  await p.waitForFunction(() => [...document.querySelectorAll('#ups small')].length == 3 && [...document.querySelectorAll('#ups small')].every(s => s.textContent.includes('sent')), null, { timeout: 20000 });
  console.log('list:', await p.locator('#list').innerText());
  const [d] = await Promise.all([p.waitForEvent('download'), p.click('text=Download')]);
  await d.saveAs('build/t/dl/' + d.suggestedFilename()); console.log('downloaded as', d.suggestedFilename());
  await p.screenshot({ path: 'build/t/page.png' });
  await b.close();
})().catch(e => { console.error(e); process.exit(1); });
