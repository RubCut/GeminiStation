// Exercise the real page's voice UI with a fake settings server, without browser dependencies.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const html = fs.readFileSync('app/src/main/assets/index.html', 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const saved = {model:'gemini-3.5-flash-lite', voice:'Kore', voices:['Kore','Puck','Aoede'], logs:[]};
const writes = [], samples = [];
let sampleError = false, blockPlayback = false, revoked = [];
function page() {
  const nodes = new Map();
  function element(id) {
    if (!nodes.has(id)) nodes.set(id, {value:'',textContent:'',disabled:false,children:[],handlers:{},
      addEventListener(type, fn){this.handlers[type]=fn}, appendChild(child){this.children.push(child)},
      removeAttribute() {}, play:async()=>{if(blockPlayback)throw Error('blocked')}});
    return nodes.get(id);
  }
  const context = vm.createContext({document:{getElementById:element,createElement:()=>({})},
    fetch:async(path, options)=>{
      if(path==='/api/config') {const body=JSON.parse(options.body); writes.push(body); saved.model=body.model; saved.voice=body.voice;}
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
  await vm.runInContext('save(false)',p.context);
  assert.equal(writes.at(-1).voice,'Puck');
  assert.equal(saved.voice,'Puck');
  p=page(); await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Puck', 'reload restores the saved voice');
  saved.voice='Aoede'; await vm.runInContext('refresh()',p.context);
  assert.equal(p.element('voice').value,'Aoede', 'other saved changes update an unedited selector');
  await vm.runInContext('save(true)',p.context);
  assert.equal(writes.at(-1).voice,'Aoede', 'deleting the key preserves the selected voice');
  console.log('Voice settings UI test passed');
})().catch(e=>{console.error(e);process.exitCode=1});
