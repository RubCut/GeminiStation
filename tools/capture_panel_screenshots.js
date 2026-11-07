// Capture the real panel against an entirely local, fictional API.
// Requires playwright-core and @sparticuz/chromium (see docs/SCREENSHOTS.md).
const { chromium } = require('playwright-core');
const chrome = require('@sparticuz/chromium');
const fs = require('fs');
const root=require('path').resolve(__dirname,'..');
fs.mkdirSync(root+'/docs/screenshots',{recursive:true});
const data={model:'gemini-3.5-flash-lite',voice:'Kore',voices:['Kore','Puck','Aoede'],logs:['12:00:00  Приложение запущено','12:00:02  Настройки загружены','12:01:14  Голосовая активация выключена'],buttonEnabled:false,audioDuckEnabled:true,buttonServiceConnected:false,wakeEnabled:false,wakeEngine:'microwakeword_selected_tts',wakeSampleCount:0,wakeFilter:true,wakeAmplify:true,wakeVad:true,wakePersonal:false,wakeStatus:'Выключена',wakeSensitivity:'50',wakeRms:-1,wakePeak:-1,dnsEnabled:true,dnsBypassVpn:true,dnsServer:'https://d.webshiftvpn.ru/dns-query',vpnActive:false,systemPrompt:'Ты — голосовой помощник. Отвечай ясно и по делу.',defaultSystemPrompt:'Ты — голосовой помощник. Отвечай ясно и по делу.',apiKeys:[],voiceTuning:{wakeMinMs:200,wakeRecentMs:400,wakeResetMs:1200,silenceMs:5000,finalMs:800,interimMs:1800,preconnect:true,earlyCapture:true}};
const responses={'/api/logs':data,'/api/plugins':{plugins:[{name:'Пример плагина',id:'example',version:'1.0',enabled:true,shell:false,sha256:'0123456789abcdef'.repeat(4)}]},'/api/plugin-details':{config:{language:'ru',volume:5},logs:['12:00:00 Запуск инструмента','12:00:01 Выполнение завершено']},'/api/panel-auth':{enabled:false},'/api/wake-samples':{count:0,max:5},'/api/tts':{engine:'gemini',engines:[{id:'gemini',name:'Gemini TTS'}]},'/api/led/animations':{choices:{listening:'new',thinking:'new',speaking:'new'},custom:{listening:false,thinking:false,speaking:false}},'/api/led/output':{root:false},'/api/ring/output':{enabled:false,monochrome:false}};
(async()=>{
 for(const [mode,viewport] of Object.entries({desktop:{width:1365,height:900},mobile:{width:390,height:844}})){
  const browser=await chromium.launch({headless:true,executablePath:await chrome.executablePath(),args:chrome.args});
  try{
   const context=await browser.newContext({viewport,deviceScaleFactor:1,locale:'ru-RU'});
   await context.route('**/*',async route=>{
    const path=new URL(route.request().url()).pathname;
    if(path==='/')return route.fulfill({status:200,contentType:'text/html; charset=utf-8',body:fs.readFileSync(root+'/app/src/main/assets/index.html')});
    if(path==='/api/fonts/google-sans.ttf')return route.fulfill({status:200,contentType:'font/ttf',body:fs.readFileSync(root+'/app/src/main/assets/fonts/google_sans.ttf')});
    if(path.startsWith('/api/led/preview/')){const state=path.split('/').pop();return route.fulfill({status:200,contentType:'image/gif',body:fs.readFileSync(root+'/app/src/main/assets/led/'+state+'.gif')});}
    if(responses[path])return route.fulfill({status:200,contentType:'application/json',body:JSON.stringify(responses[path])});
    console.log('Unexpected request:',path);return route.fulfill({status:404,contentType:'application/json',body:'{"message":"Демо"}'});
   });
   const page=await context.newPage();page.on('pageerror',e=>console.error('PAGE ERROR:',e));
   await page.goto('https://demo.local/',{waitUntil:'networkidle'});await page.evaluate(()=>document.fonts.ready);
   await page.waitForFunction(()=>document.getElementById('overviewModel').textContent==='gemini-3.5-flash-lite');
   for(const section of ['summary','assistant','activation','editor','display','smartHome','plugins','journal']){
    await page.evaluate(id=>showPage(id),section); await page.evaluate(()=>window.scrollTo(0,0));
    const sizes=await page.evaluate(()=>({scroll:document.documentElement.scrollWidth,visible:document.documentElement.clientWidth}));
    if(sizes.scroll>sizes.visible)throw Error(`${mode} ${section}: horizontal overflow ${JSON.stringify(sizes)}`);
    await page.screenshot({path:`${root}/docs/screenshots/${mode}-${section}.jpg`,type:'jpeg',quality:82,animations:'disabled'});
    if(section==='plugins'){
      await page.locator('#pluginList details summary').click();
      await page.waitForFunction(()=>document.querySelector('#pluginList textarea')?.value.includes('volume'));
      await page.locator('#pluginList details').scrollIntoViewIfNeeded();
      await page.screenshot({path:`${root}/docs/screenshots/${mode}-plugins-details.jpg`,type:'jpeg',quality:82,animations:'disabled'});
    }
   }
  }finally{await browser.close()}
 }
})().catch(e=>{console.error(e);process.exitCode=1});
