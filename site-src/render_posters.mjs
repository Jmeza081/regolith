// Renders each poster in site-src/posters.html to site/images/posters/<name>.jpg at 2x.
// Run from the repo root after editing posters.html, then python3 site-src/build.py:
//   node site-src/render_posters.mjs
// Needs Playwright, which is not an app dependency: install it anywhere and point NODE_PATH at
// that node_modules (loaded with require, because ES imports ignore NODE_PATH).
import { createRequire } from 'module';
import { mkdirSync } from 'fs';
import { pathToFileURL } from 'url';

const { chromium } = createRequire(import.meta.url)('playwright');

mkdirSync('site/images/posters', { recursive: true });
const browser = await chromium.launch();
const page = await browser.newPage({ deviceScaleFactor: 2 });
await page.goto(pathToFileURL('site-src/posters.html').href, { waitUntil: 'networkidle' });
await page.evaluate(() => document.fonts.ready);
// The site's tiles round their own corners, so the files are square-cornered and shadowless.
await page.addStyleTag({ content: '.poster { border-radius: 0 !important; box-shadow: none !important; }' });
for (const el of await page.locator('.poster').all()) {
  const name = await el.getAttribute('data-name');
  await el.screenshot({ path: `site/images/posters/${name}.jpg`, type: 'jpeg', quality: 82 });
  console.log('wrote', name);
}
await browser.close();
