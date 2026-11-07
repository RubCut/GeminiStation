// Conceptual render of MainActivity.buildUi, not a capture from Android or a station.
// With local dependencies: NODE_PATH=/tmp/gemini-screenshots/node_modules node tools/capture_tv_mock.js
const fs = require('node:fs'), path = require('node:path');
const {chromium} = require('playwright-core'), chrome = require('@sparticuz/chromium');
const root = path.resolve(__dirname, '..');
const font = fs.readFileSync(path.join(root, 'app/src/main/assets/fonts/google_sans.ttf')).toString('base64');
const out = path.join(root, 'docs/tv-screenshots'); fs.mkdirSync(out, {recursive:true});
const html = `<!doctype html><html lang="ru"><head><meta charset="utf-8"><style>
@font-face{font-family:GoogleSans;src:url(data:font/ttf;base64,${font})}
*{box-sizing:border-box}html,body{margin:0;width:100%;height:100%;overflow:hidden}
body{font-family:GoogleSans,Arial,sans-serif;background:radial-gradient(ellipse at 75% 20%,#40375e 0%,#272743 25%,#141d31 53%,#0d1521 100%)}
/* Generic demonstration background, not an approximation of Yandex's launcher. */
body:before{content:"";position:absolute;inset:0;background:radial-gradient(ellipse at 16% 55%,#31517166,transparent 42%),linear-gradient(110deg,transparent 15%,#8170a412 64%,transparent 80%)}
.demo{position:absolute;right:26px;top:20px;font:10px/1.3 GoogleSans,Arial,sans-serif;letter-spacing:.1em;color:#d6d2ec90}
.bubble{position:absolute;bottom:16px;left:16px;display:flex;align-items:center;padding:8px 12px;border:1px solid #4b4655;border-radius:28px;background:rgba(39,37,48,.949);color:white}
.frame{width:42px;height:42px;flex:0 0 42px;position:relative;display:flex;align-items:center;justify-content:center}
.star{width:32px;height:32px}
.indicator{position:absolute;inset:0;width:42px;height:42px;display:none}.thinking .indicator{display:block}
.thinking .star{transform:rotate(97.2deg)}
.listening .star{transform:scale(1.12)}.answer .star{transform:scale(1.18)}
.text{margin-left:12px;padding:8px 4px 8px 2px;font-size:16px;line-height:1.22;max-width:420px;max-height:calc(4 * 1.22em + 16px);overflow:hidden;white-space:pre-wrap}
</style></head><body><div class="demo">ДЕМОНСТРАЦИЯ · УСЛОВНЫЙ ФОН</div><div class="bubble"><div class="frame">
<svg class="indicator" viewBox="0 0 42 42"><circle cx="21" cy="21" r="19.75" fill="none" stroke="#4d4658" stroke-width="2.5"/><circle cx="21" cy="21" r="19.75" fill="none" stroke="#d0bcff" stroke-width="2.5" stroke-linecap="round" stroke-dasharray="33.2 91" transform="rotate(39.6 21 21)"/></svg>
<svg class="star" viewBox="0 0 64 64"><defs><linearGradient id="gemini" gradientUnits="userSpaceOnUse" x1="5" y1="58" x2="57" y2="6"><stop stop-color="#4285F4"/><stop offset=".52" stop-color="#B187F4"/><stop offset="1" stop-color="#EEA8C2"/></linearGradient></defs><path fill="url(#gemini)" d="M32,3 C35,18 46,29 61,32 C46,35 35,46 32,61 C29,46 18,35 3,32 C18,29 29,18 32,3 Z"/></svg>
</div><div class="text"></div></div></body></html>`;
const states = [
 ['connecting', 'Подключаюсь…'],
 ['listening', 'Слушаю…'],
 ['thinking', 'Какая погода сегодня?'],
 ['answer', 'Сегодня облачно, около 18 градусов. Возможен небольшой дождь.']
];
(async()=>{
 const browser = await chromium.launch({headless:true, executablePath:await chrome.executablePath(), args:chrome.args});
 try {
  const page = await browser.newPage({viewport:{width:960,height:540},deviceScaleFactor:2});
  await page.setContent(html); await page.evaluate(()=>document.fonts.ready);
  for(const [state,text] of states){
   await page.evaluate(({state,text})=>{document.body.className=state;document.querySelector('.text').textContent=text}, {state,text});
   await page.screenshot({path:path.join(out,state+'.png'), animations:'disabled'});
   const box=await page.locator('.bubble').boundingBox();
   if(!box || box.x<0 || box.y<0 || box.x+box.width>960 || box.y+box.height>540)throw Error('Invalid bubble layout');
   // The same pixels, cropped at 2x device scale: no fake high-resolution rendering.
   await page.screenshot({path:path.join(out,state+'-detail.png'),animations:'disabled',clip:{x:Math.max(0,box.x-8),y:Math.max(0,box.y-8),width:Math.min(960-box.x+8,box.width+16),height:Math.min(540-box.y+8,box.height+16)}});
  }
 }finally{await browser.close()}
})().catch(e=>{console.error(e);process.exitCode=1});
