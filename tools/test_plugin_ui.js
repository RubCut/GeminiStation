// Exercise the actual plugin UI with a fake panel API and DOM.
const assert = require('node:assert/strict'), fs=require('node:fs'), vm=require('node:vm');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const script=html.slice(html.indexOf('async function refreshPlugins(){'),html.indexOf('let ttsLoaded='));
const nodes={};
function node(tag='div') {return {tag,children:[],attrs:{},handlers:{},style:{},value:'',disabled:false,open:false,
  appendChild(child){this.children.push(child)}, setAttribute(k,v){this.attrs[k]=v},
  addEventListener(k,fn){this.handlers[k]=fn},
  set textContent(v){this._text=v;this.children=[]},get textContent(){return this._text||''}}}
function el(id){return nodes[id]||(nodes[id]=node())}
let enabled=true, config={volume:5}, logs=['hello'], calls=[];
const plugin={name:'Пример',id:'example',version:'1',enabled:true,shell:false,sha256:'digest'};
async function request(path,options={}){
 if(path==='/api/plugins'&&!options.method)return {plugins:[{...plugin,enabled}]};
 if(path.startsWith('/api/plugin-details?'))return {config:{...config},logs:[...logs]};
 const data=JSON.parse(options.body);calls.push(data);
 if(data.action==='saveConfig')config=data.config;
 if(data.action==='resetConfig')config={volume:5};
 if(data.action==='clearLogs')logs=[];
 if(data.action==='disable')enabled=false;
 return {message:'OK'};
}
const ctx=vm.createContext({document:{createElement:tag=>node(tag)},el,request,confirm:()=>true,TextEncoder,Uint8Array,btoa:s=>s});
vm.runInContext(script,ctx);
(async()=>{
 await vm.runInContext('refreshPlugins()',ctx);
 let row=el('pluginList').children[0], actions=row.children[2], details=row.children[3];
 assert.equal(actions.children[0].textContent,'Отключить');
 details.open=true; await details.handlers.toggle(); // event schedules async fetch
 await new Promise(resolve=>setImmediate(resolve));
 const editor=details.children[2], save=details.children[3].children[0], reset=details.children[3].children[1];
 const logActions=details.children[6], log=details.children[7];
 assert.equal(JSON.parse(editor.value).volume,5);assert.match(log.textContent,/hello/);
 editor.value='{"volume":8}';await save.onclick();assert.equal(config.volume,8);assert.equal(calls.at(-1).sha256,'digest');
 editor.value='[]';await save.onclick();assert.equal(config.volume,8);assert.match(details.children[4].textContent,/JSON/);
 editor.value='{"unsaved":1}';logs=['updated'];await logActions.children[0].onclick();assert.equal(editor.value,'{"unsaved":1}');assert.match(log.textContent,/updated/);
 await logActions.children[1].onclick();assert.equal(logs.length,0);
 await reset.onclick();assert.equal(JSON.parse(editor.value).volume,5);
 await actions.children[0].onclick();row=el('pluginList').children[0];assert.equal(row.children[2].children[0].textContent,'Включить');
 await row.children[2].children[0].onclick();assert.equal(calls.at(-1).action,'enable');
 console.log('Plugin UI test passed');
})().catch(e=>{console.error(e);process.exitCode=1});
