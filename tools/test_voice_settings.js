// Exercise the real page's voice UI with a fake settings server, without browser dependencies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const markup = html.split('<script>')[0];
const ids = [...markup.matchAll(/\bid="([^"]+)"/g)].map(match => match[1]);
assert.equal(new Set(ids).size, ids.length, 'HTML IDs must be unique');
for (const [, id] of script.matchAll(/\bel\('([^']+)'\)/g)) {
  assert.ok(ids.includes(id), `missing DOM element: ${id}`);
}
for (const [, id] of markup.matchAll(/(?:href|for|aria-describedby|aria-labelledby)="#?([\w-]+)"/g)) {
  assert.ok(ids.includes(id), `missing anchor or label target: ${id}`);
}
assert.match(markup, /<input type="range" id="wakeSensitivity" min="1" max="100" step="1"/);
assert.match(markup, /<input type="number" id="sensitivityNumber" min="1" max="100" step="1"/);
const saved = {model:'gemini-3.5-flash-lite', voice:'Kore', voices:['Kore','Puck','Aoede'], logs:[], buttonEnabled:false, audioDuckEnabled:true, buttonServiceConnected:false, mediaAccessGranted:false, wakeEnabled:false, wakeStatus:"Выключена", wakeSensitivity:"normal", wakeRms:-1, wakePeak:-1, dnsEnabled:true, dnsBypassVpn:true, dnsServer:"https://d.webshiftvpn.ru/dns-query", vpnActive:false, systemPrompt:"Старый промпт", defaultSystemPrompt:"Исходный промпт", apiKeys:[{id:"11111111-1111-4111-8111-111111111111"}]};
const writes = [], samples = [];
let sampleError = false, blockPlayback = false, revoked = [];
let copyAllowed=true, copied='', clipboardAllowed=false, pendingLogFetch=null;
function page() {
  const nodes = new Map(), docHandlers={};
  let selection=null, focused=null;
  function element(id) {
    if (!nodes.has(id)) {
      const node = {value:'',_text:'',disabled:false,children:[],handlers:{},attributes:{},style:{setProperty(name,value){this[name]=value}},
        setAttribute(name,value){this.attributes[name]=value}, addEventListener(type, fn){this.handlers[type]=fn},
        appendChild(child){this.children.push(child);if(id==='keyList'&&this.children.length===1)this.value=child.value},
        removeAttribute() {}, focus(){this.focused=true;focused=this}, select(){this.selected=true},
        contains(other){return other===this}, play:async()=>{if(blockPlayback)throw Error('blocked')}};
      Object.defineProperty(node,'textContent',{get(){return this._text},set(value){this._text=value;this.children=[];if(id==='keyList')this.value=''}});
      nodes.set(id,node);
    }
    return nodes.get(id);
  }
  const context = vm.createContext({document:{getElementById:element,createElement:()=>({}),
      addEventListener(type,fn){docHandlers[type]=fn},execCommand(command){assert.equal(command,'copy');if(copyAllowed){copied=focused.value;return true}return false}},
    window:{getSelection:()=>selection},navigator:{clipboard:{writeText:async(text)=>{if(!clipboardAllowed)throw Error('blocked');copied=text}}},
    fetch:async(path, options)=>{
      if(path==='/api/logs' && pendingLogFetch)return new Promise(resolve=>pendingLogFetch.push(resolve));
      if(path==='/api/config') {const body=JSON.parse(options.body); writes.push(body); saved.model=body.model; saved.voice=body.voice; saved.buttonEnabled=body.buttonEnabled; saved.audioDuckEnabled=body.audioDuckEnabled; saved.wakeEnabled=body.wakeEnabled; saved.wakeSensitivity=body.wakeSensitivity;}
      if(path==='/api/network') {const body=JSON.parse(options.body); writes.push({network:body}); saved.dnsEnabled=body.enabled; saved.dnsBypassVpn=body.bypassVpn; saved.dnsServer=body.server;}
      if(path==='/api/prompt') {const body=JSON.parse(options.body); writes.push({prompt:body}); saved.systemPrompt=body.reset?saved.defaultSystemPrompt:body.prompt;}
      if(path==='/api/keys') {
        const body=JSON.parse(options.body);
        if(body.action==='add') saved.apiKeys.push({id:'22222222-2222-4222-8222-222222222222'});
        else saved.apiKeys=saved.apiKeys.filter(key=>key.id!==body.id);
      }
      if(path==='/api/voice/sample') {
        samples.push({body:JSON.parse(options.body),headers:options.headers});
        return sampleError ? {ok:false,json:async()=>({message:'Нет ключа'})} : {ok:true,blob:async()=>({audio:true})};
      }
      return {ok:true,json:async()=>({...saved,voices:[...saved.voices]})};
    }, URL:{createObjectURL:()=>`blob:sample-${samples.length}`,revokeObjectURL:url=>revoked.push(url)},
    setInterval:()=>{}, setTimeout:()=>{}, confirm:()=>true});
  vm.runInContext(script, context);
  return {element, context, docHandlers, selectLog(value){selection=value?{isCollapsed:false,anchorNode:element('logs'),focusNode:element('logs')}:null}};
}
(async()=>{
  let p=page(); await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Kore');
  assert.equal(p.element('voice').disabled,false);
  assert.equal(p.element('audioDuckEnabled').checked,true);
  assert.match(p.element('mediaStatus').textContent,/не разрешён/);
  saved.mediaAccessGranted=true;await vm.runInContext('refresh()',p.context);
  assert.match(p.element('mediaStatus').textContent,/разрешён/);
  p.element('audioDuckEnabled').checked=false;p.element('audioDuckEnabled').handlers.change();
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('audioDuckEnabled').checked,false,'poll must preserve unsaved audio-focus choice');
  await p.element('saveDuckSettings').handlers.click();
  assert.equal(saved.audioDuckEnabled,false);
  assert.equal(p.element('duckMessage').textContent,'Настройки сохранены');

  assert.equal(p.element('keyCount').textContent,'Сохранено ключей: 1');
  assert.equal(p.element('dnsEnabled').checked,true);
  assert.equal(p.element('dnsBypassVpn').checked,true);
  assert.equal(p.element('dnsServer').value,'https://d.webshiftvpn.ru/dns-query');
  assert.equal(p.element('systemPrompt').value,'Старый промпт');
  saved.vpnActive=true;await vm.runInContext('refresh()',p.context);
  assert.match(p.element('dnsStatus').textContent,/VPN активен.*системный DNS/);
  p.element('dnsServer').value='1.1.1.1';p.element('dnsServer').handlers.input();
  p.element('dnsBypassVpn').checked=false;p.element('dnsBypassVpn').handlers.change();
  p.element('systemPrompt').value='Моя инструкция';p.element('systemPrompt').handlers.input();
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('dnsServer').value,'1.1.1.1','poll must preserve unsaved DNS');
  assert.equal(p.element('systemPrompt').value,'Моя инструкция','poll must preserve unsaved prompt');
  await p.element('saveDns').handlers.click();
  assert.equal(saved.dnsServer,'1.1.1.1');assert.equal(saved.dnsBypassVpn,false);
  assert.match(p.element('dnsStatus').textContent,/VPN активен.*свой DNS/);
  await p.element('savePrompt').handlers.click();
  assert.equal(saved.systemPrompt,'Моя инструкция');
  await p.element('resetPrompt').handlers.click();
  assert.equal(saved.systemPrompt,'Исходный промпт');assert.equal(p.element('systemPrompt').value,'Исходный промпт');
  p.element('dnsEnabled').checked=false;p.element('dnsEnabled').handlers.change();
  await p.element('saveDns').handlers.click();assert.equal(saved.dnsEnabled,false);
  saved.vpnActive=false;await vm.runInContext('refresh()',p.context);
  assert.match(p.element('dnsStatus').textContent,/системный DNS/);

  p.element('key').value='new-secret';
  await p.element('addKey').handlers.click();
  assert.equal(p.element('key').value,'');
  assert.equal(saved.apiKeys.length,2);
  assert.equal(p.element('keyCount').textContent,'Сохранено ключей: 2');
  p.element('keyList').value=saved.apiKeys[1].id;
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('keyList').value,saved.apiKeys[1].id,'poll must preserve selected key');
  await p.element('delete').handlers.click();
  assert.equal(saved.apiKeys.length,1);
  assert.equal(p.element('keyMessage').textContent,'Ключ удалён');
  p.element('key').value='unsaved-secret';
  await vm.runInContext('save(false)',p.context);
  assert.equal(writes.at(-1).key,'','settings save must not silently submit a key');
  assert.equal(p.element('key').value,'unsaved-secret');
  p.element('voice').value='Puck'; p.element('voice').handlers.change();
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Puck', 'poll must not discard an unsaved choice');
  await vm.runInContext('sampleVoice()',p.context);
  assert.equal(samples.at(-1).body.voice,'Puck');
  assert.equal(samples.at(-1).headers['X-Gemini-Station'],'1');
  assert.equal(saved.voice,'Kore','preview must not change saved settings');
  assert.equal(p.element('sampleAudio').src,'blob:sample-1');
  assert.equal(p.element('sampleAudio').hidden,false);
  blockPlayback=true; await vm.runInContext('sampleVoice()',p.context); blockPlayback=false;
  assert.deepEqual(revoked,['blob:sample-1']);
  assert.match(p.element('sampleStatus').textContent,/Нажмите/);
  sampleError=true; await vm.runInContext('sampleVoice()',p.context); sampleError=false;
  assert.equal(p.element('sampleStatus').textContent,'Нет ключа');
  assert.equal(p.element('sampleVoice').disabled,false);
  assert.equal(p.element('wakeSensitivity').value,'50','old normal setting migrates to 50%');
  assert.equal(p.element('sensitivityNumber').value,'50');
  p.element('wakeSensitivity').value='100'; p.element('wakeSensitivity').handlers.input();
  assert.equal(p.element('sensitivityNumber').value,'100');
  assert.equal(p.element('wakeSensitivity').attributes['aria-valuetext'],'100%');
  p.element('wakeEnabled').checked=true; p.element('wakeEnabled').handlers.change();
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('wakeEnabled').checked,true,'poll must not discard an unsaved wake setting');
  assert.equal(p.element('wakeSensitivity').value,'100','poll must not discard sensitivity edits');
  p.element('buttonEnabled').checked=true; p.element('buttonEnabled').handlers.change();
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('buttonEnabled').checked,true,'poll must not discard an unsaved button setting');
  await vm.runInContext('save(false)',p.context);
  assert.equal(writes.at(-1).buttonEnabled,true);
  assert.equal(writes.at(-1).wakeEnabled,true);
  assert.equal(writes.at(-1).wakeSensitivity,'100');
  assert.equal(writes.at(-1).voice,'Puck');
  assert.equal(saved.voice,'Puck');
  p=page(); await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Puck', 'reload restores the saved voice');
  assert.equal(p.element('audioDuckEnabled').checked,false, 'reload restores audio focus setting');
  assert.equal(p.element('buttonEnabled').checked,true, 'reload restores the button setting');
  assert.equal(p.element('wakeEnabled').checked,true, 'reload restores the wake setting');
  assert.equal(p.element('wakeSensitivity').value,'100', 'reload restores sensitivity');
  assert.equal(p.element('sensitivityNumber').value,'100');
  assert.equal(p.element('wakeSensitivity').style['--progress'],'100%');
  saved.wakeRms=123; saved.wakePeak=456; await vm.runInContext('refresh()',p.context);
  assert.match(p.element('wakeLevel').textContent,/RMS 123 · пик 456/);
  assert.notEqual(p.element('wakeMeter').style.width,'0%');
  assert.equal(p.element('connectionStatus').textContent,'Станция на связи');
  saved.voice='Aoede'; await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Aoede', 'other saved changes update an unedited selector');
  for (const value of ['', '0', '101', '1.5', '-1']) {
    p.element('sensitivityNumber').value=value;p.element('sensitivityNumber').handlers.input();
    const count=writes.length;await vm.runInContext('save(false, "wakeMessage")',p.context);
    assert.equal(writes.length,count,'invalid manual value must not be saved');
    assert.match(p.element('sensitivityError').textContent,/от 1 до 100/);
    assert.equal(p.element('sensitivityNumber').attributes['aria-invalid'],'true');
    await vm.runInContext('refresh()',p.context);
    assert.equal(p.element('sensitivityNumber').value,value,'poll must not discard invalid in-progress edits');
  }
  p.element('sensitivityNumber').value='73';p.element('sensitivityNumber').handlers.input();
  assert.equal(p.element('wakeSensitivity').value,'73','manual input moves slider');
  assert.equal(p.element('sensitivityError').textContent,'');
  await vm.runInContext('save(false, "wakeMessage")',p.context);
  assert.equal(writes.at(-1).wakeSensitivity,'73');
  assert.equal(p.element('wakeMessage').textContent,'Настройки сохранены');
  for (let percent=1;percent<=100;percent++) {
    p.element('wakeSensitivity').value=String(percent);p.element('wakeSensitivity').handlers.input();
    assert.equal(p.element('sensitivityNumber').value,String(percent));
    await vm.runInContext('save(false)',p.context);
    assert.equal(writes.at(-1).wakeSensitivity,String(percent));
  }
  saved.wakeSensitivity='high';p=page();await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('sensitivityNumber').value,'100','old high setting migrates to 100%');
  saved.wakeSensitivity='low';p=page();await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('sensitivityNumber').value,'1','old low setting migrates to 1%');
  await vm.runInContext('save(true)',p.context);
  assert.equal(writes.at(-1).voice,'Aoede', 'deleting the key preserves the selected voice');
  // The complete snapshot is available even after many entries, with HTTP-friendly copy fallback.
  saved.logs=Array.from({length:40},(_,i)=>`line ${i}`);p=page();await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('logs').textContent.split('\n').length,40);
  await p.element('copyLogs').handlers.click();
  assert.equal(copied,saved.logs.join('\n'));assert.equal(p.element('logCopyFallback').hidden,true);
  assert.match(p.element('logMessage').textContent,/40 записей/);
  p.selectLog(true);saved.logs.push('line 40');await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('logs').textContent.split('\n').length,40,'selection must survive poll updates');
  p.selectLog(false);p.docHandlers.selectionchange();assert.equal(p.element('logs').textContent.split('\n').length,41);
  p.element('logs').handlers.pointerdown();saved.logs.push('line 41');await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('logs').textContent.split('\n').length,41,'drag start must prevent replacement');
  p.docHandlers.pointerup();assert.equal(p.element('logs').textContent.split('\n').length,42);
  p.element('pauseLogs').handlers.click();saved.logs.push('line 42');await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('logs').textContent.split('\n').length,42,'pause holds visible snapshot');
  await p.element('copyLogs').handlers.click();assert.equal(copied.split('\n').length,42,'pause copies visible snapshot');
  p.element('pauseLogs').handlers.click();assert.equal(p.element('logs').textContent.split('\n').length,43);
  copyAllowed=false;clipboardAllowed=false;await p.element('copyLogs').handlers.click();
  assert.equal(p.element('logCopyFallback').hidden,false);assert.equal(p.element('logCopyFallback').value,saved.logs.join('\n'));
  assert.equal(p.element('logCopyFallback').selected,true);assert.match(p.element('logMessage').textContent,/Ctrl\+C/);
  clipboardAllowed=true;await p.element('copyLogs').handlers.click();assert.equal(copied,saved.logs.join('\n'));
  assert.equal(p.element('logCopyFallback').hidden,true);copyAllowed=true;clipboardAllowed=false;
  pendingLogFetch=[];
  const older=vm.runInContext('refresh()',p.context), newer=vm.runInContext('refresh()',p.context);
  pendingLogFetch[1]({ok:true,json:async()=>({...saved,logs:['newer']})});await newer;
  pendingLogFetch[0]({ok:true,json:async()=>({...saved,logs:['older']})});await older;
  assert.equal(p.element('logs').textContent,'newer','late response must not replace a newer log snapshot');
  pendingLogFetch=null;
  console.log('Voice settings UI test passed');
})().catch(e=>{console.error(e);process.exitCode=1});
