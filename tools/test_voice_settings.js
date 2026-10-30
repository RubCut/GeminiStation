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
assert.match(markup, /<input type="range" id="wakeSensitivity" min="0" max="2" step="1"/);
const saved = {model:'gemini-3.5-flash-lite', voice:'Kore', voices:['Kore','Puck','Aoede'], logs:[], buttonEnabled:false, buttonServiceConnected:false, wakeEnabled:false, wakeStatus:"Выключена", wakeSensitivity:"normal", wakeRms:-1, wakePeak:-1, apiKeys:[{id:"11111111-1111-4111-8111-111111111111"}]};
const writes = [], samples = [];
let sampleError = false, blockPlayback = false, revoked = [];
function page() {
  const nodes = new Map();
  function element(id) {
    if (!nodes.has(id)) {
      const node = {value:'',_text:'',disabled:false,children:[],handlers:{},attributes:{},style:{setProperty(name,value){this[name]=value}},
        setAttribute(name,value){this.attributes[name]=value}, addEventListener(type, fn){this.handlers[type]=fn},
        appendChild(child){this.children.push(child);if(id==='keyList'&&this.children.length===1)this.value=child.value},
        removeAttribute() {}, play:async()=>{if(blockPlayback)throw Error('blocked')}};
      Object.defineProperty(node,'textContent',{get(){return this._text},set(value){this._text=value;this.children=[];if(id==='keyList')this.value=''}});
      nodes.set(id,node);
    }
    return nodes.get(id);
  }
  const context = vm.createContext({document:{getElementById:element,createElement:()=>({})},
    fetch:async(path, options)=>{
      if(path==='/api/config') {const body=JSON.parse(options.body); writes.push(body); saved.model=body.model; saved.voice=body.voice; saved.buttonEnabled=body.buttonEnabled; saved.wakeEnabled=body.wakeEnabled; saved.wakeSensitivity=body.wakeSensitivity;}
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
  return {element, context};
}
(async()=>{
  let p=page(); await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Kore');
  assert.equal(p.element('voice').disabled,false);
  assert.equal(p.element('keyCount').textContent,'Сохранено ключей: 1');
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
  p.element('wakeSensitivity').value='2'; p.element('wakeSensitivity').handlers.input();
  assert.equal(p.element('sensitivityValue').textContent,'Высокая');
  assert.equal(p.element('wakeSensitivity').attributes['aria-valuetext'],'Высокая');
  p.element('wakeEnabled').checked=true; p.element('wakeEnabled').handlers.change();
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('wakeEnabled').checked,true,'poll must not discard an unsaved wake setting');
  assert.equal(p.element('wakeSensitivity').value,'2','poll must not discard sensitivity edits');
  p.element('buttonEnabled').checked=true; p.element('buttonEnabled').handlers.change();
  await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('buttonEnabled').checked,true,'poll must not discard an unsaved button setting');
  await vm.runInContext('save(false)',p.context);
  assert.equal(writes.at(-1).buttonEnabled,true);
  assert.equal(writes.at(-1).wakeEnabled,true);
  assert.equal(writes.at(-1).wakeSensitivity,'high');
  assert.equal(writes.at(-1).voice,'Puck');
  assert.equal(saved.voice,'Puck');
  p=page(); await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Puck', 'reload restores the saved voice');
  assert.equal(p.element('buttonEnabled').checked,true, 'reload restores the button setting');
  assert.equal(p.element('wakeEnabled').checked,true, 'reload restores the wake setting');
  assert.equal(p.element('wakeSensitivity').value,'2', 'reload restores sensitivity');
  assert.equal(p.element('sensitivityValue').textContent,'Высокая');
  assert.equal(p.element('wakeSensitivity').style['--progress'],'100%');
  saved.wakeRms=123; saved.wakePeak=456; await vm.runInContext('refresh()',p.context);
  assert.match(p.element('wakeLevel').textContent,/RMS 123 · пик 456/);
  assert.notEqual(p.element('wakeMeter').style.width,'0%');
  assert.equal(p.element('connectionStatus').textContent,'Станция на связи');
  saved.voice='Aoede'; await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Aoede', 'other saved changes update an unedited selector');
  p.element('wakeSensitivity').value='0'; p.element('wakeSensitivity').handlers.input();
  await vm.runInContext('save(false, \"wakeMessage\")',p.context);
  assert.equal(writes.at(-1).wakeSensitivity,'low');
  assert.equal(p.element('sensitivityValue').textContent,'Низкая');
  assert.equal(p.element('wakeMessage').textContent,'Настройки сохранены');
  p.element('wakeSensitivity').value='1'; p.element('wakeSensitivity').handlers.change();
  await vm.runInContext('save(false)',p.context);
  assert.equal(writes.at(-1).wakeSensitivity,'normal');
  await vm.runInContext('save(true)',p.context);
  assert.equal(writes.at(-1).voice,'Aoede', 'deleting the key preserves the selected voice');
  console.log('Voice settings UI test passed');
})().catch(e=>{console.error(e);process.exitCode=1});
