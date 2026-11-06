// Exercise the real LED editor script using a fake server, without browser dependencies.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const script=html.match(/<script>([\s\S]*?)<\/script>/)[1];
class Element {
 constructor(){this.value='';this.files=[];this.handlers={};this.disabled=false;this.children=[];this.customOption={disabled:false};this.style={setProperty(){}}}
 set textContent(v){this._text=v;this.children=[]}get textContent(){return this._text||''}
 addEventListener(type,fn){this.handlers[type]=fn}setAttribute(){}removeAttribute(){}appendChild(c){this.children.push(c)}
 querySelector(s){assert.equal(s,'option[value="custom"]');return this.customOption}
 contains(){return false}
}
const nodes=new Map();function el(id){if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id)}
let rootOutput=false, outputWrites=[], allowConfirm=true;
let conf={choices:{listening:'classic',thinking:'hybrid',speaking:'new'},custom:{listening:false,thinking:false,speaking:false}},writes=[],reject=false;
const c=vm.createContext({document:{getElementById:el,createElement:()=>new Element(),addEventListener(){}},window:{getSelection:()=>null},navigator:{},
 setInterval(){},setTimeout(){},confirm:()=>allowConfirm,URL:{},btoa:s=>Buffer.from(s,'binary').toString('base64'),
 fetch:async(path,opts)=>{
  if(path==='/api/led/output'){
   if(opts.method==='POST'){const data=JSON.parse(opts.body);outputWrites.push(data);rootOutput=data.root;return {ok:true,json:async()=>({message:'Сохранено'})}}
   return {ok:true,json:async()=>({root:rootOutput})};
  }
  if(path==='/api/led/animations'){
   if(opts.method==='POST'){
    const body=JSON.parse(opts.body);writes.push(body);
    if(reject)return {ok:false,json:async()=>({message:'Недопустимый GIF'})};
    if(body.action==='select')conf.choices[body.mode]=body.style;
    if(body.action==='upload'){conf.custom[body.mode]=true;conf.choices[body.mode]='custom'}
    return {ok:true,json:async()=>({message:'Сохранено'})};
   }
   return {ok:true,json:async()=>JSON.parse(JSON.stringify(conf))};
  }
  return {ok:true,json:async()=>({plugins:[],engines:[],voices:[],apiKeys:[],logs:[]})};
 }
});
vm.runInContext(script,c);
(async()=>{
 await vm.runInContext('loadLedAnimations()',c);
 await vm.runInContext('loadLedOutput()',c);
 assert.equal(el('ledRoot').checked,false,'root output is off by default');
 el('ledRoot').checked=true;allowConfirm=false;await el('saveLedOutput').handlers.click();assert.equal(outputWrites.length,0,'confirmation is required');
 allowConfirm=true;await el('saveLedOutput').handlers.click();assert.deepEqual(outputWrites[0],{root:true});
 el('ledRoot').checked=false;await el('saveLedOutput').handlers.click();assert.deepEqual(outputWrites[1],{root:false});
 assert.equal(el('ledStyle-listening').value,'classic');assert.equal(el('ledStyle-thinking').value,'hybrid');
 assert.equal(el('ledStyle-speaking').customOption.disabled,true);
 assert.equal(el('ledPreview-listening').src,'/api/led/preview/classic/listening');
 el('ledStyle-listening').value='new';await el('ledStyle-listening').handlers.change();
 assert.deepEqual(writes[0],{action:'select',mode:'listening',style:'new'});
 assert.equal(el('ledPreview-listening').src,'/api/led/preview/new/listening');
 reject=true;el('ledStyle-listening').value='hybrid';await el('ledStyle-listening').handlers.change();
 assert.equal(el('ledStyle-listening').value,'new','failed write must revert');
 reject=false;const data=Uint8Array.from({length:20},(_,i)=>i);el('ledFile-thinking').files=[{size:data.length,arrayBuffer:async()=>data.buffer}];
 await el('ledUpload-thinking').handlers.click();
 assert.equal(writes.at(-1).action,'upload');assert.equal(writes.at(-1).mode,'thinking');
 assert.equal(Buffer.from(writes.at(-1).gif,'base64').length,20);assert.equal(el('ledStyle-thinking').value,'custom');
 assert.equal(el('ledStyle-thinking').customOption.disabled,false);assert.match(el('ledPreview-thinking').src,/^\/api\/led\/preview\/custom\/thinking\?v=/);
 const before=writes.length;el('ledFile-listening').files=[{size:600000}];await el('ledUpload-listening').handlers.click();assert.equal(writes.length,before);
 assert.match(el('ledAnimationMessage').textContent,/512 КБ/);
 console.log('LED animation editor UI test passed');
})().catch(e=>{console.error(e);process.exit(1)});
