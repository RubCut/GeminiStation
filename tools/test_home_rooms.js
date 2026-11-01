// Test the real panel script with a minimal DOM and a fake room API; no dependencies.
const assert = require('node:assert/strict'), fs = require('node:fs'), vm = require('node:vm');
const script = fs.readFileSync('app/src/main/assets/index.html', 'utf8').match(/<script>([\s\S]*?)<\/script>/)[1];
class Element {
  constructor(tag='div') { this.tag=tag; this.children=[]; this.handlers={}; this.style={setProperty(){}}; this.value=''; this._text=''; this.checked=false; }
  set textContent(v) { this._text=v; this.children=[]; }
  get textContent() { return this._text + this.children.map(x=>x.textContent).join(''); }
  addEventListener(name, fn) { this.handlers[name]=fn; }
  appendChild(node) { this.children.push(node); }
  append(...nodes) { this.children.push(...nodes); }
  setAttribute() {} removeAttribute() {} contains() { return false; }
  querySelectorAll(selector) { assert.equal(selector,'.room-check:checked');
    const found=[]; const visit=n=>{if(n.className==='room-check' && n.checked)found.push(n); for(const child of n.children) visit(child)}; visit(this); return found; }
}
const nodes = new Map(), element = id=>{if(!nodes.has(id))nodes.set(id,new Element());return nodes.get(id)};
const fixtures = [
  {id:'one',homeId:'h1',home:'Дом <img src=x>',room:'Кухня',selected:true},
  {id:'two',homeId:'h1',home:'Дом <img src=x>',room:'Спальня',selected:false},
  {id:'three',homeId:'h2',home:'Дача',room:'Комната',selected:true}
];
let saved;
const context = vm.createContext({
  document:{getElementById:element,createElement:tag=>new Element(tag),createTextNode:text=>{const n=new Element('#text');n.textContent=text;return n},addEventListener(){}},
  window:{getSelection:()=>null}, navigator:{}, URL:{}, setTimeout(){},setInterval(){},confirm(){return true},
  fetch:async(path,opts)=>{
    if(path==='/api/home/rooms' && opts.method==='POST') {saved=JSON.parse(opts.body);return {ok:true,json:async()=>({message:'Сохранено'})};}
    if(path==='/api/home/rooms') return {ok:true,json:async()=>({rooms:fixtures,revision:'account-revision'})};
    return {ok:true,json:async()=>({plugins:[],engines:[],voices:[],logs:[],apiKeys:[]})};
  }
});
vm.runInContext(script,context);
(async()=>{
  await vm.runInContext('loadHomeRooms()',context);
  assert.equal(element('homeRooms').children.length,2);
  assert.equal(element('homeRooms').children[0].tag,'div');
  const [first,second]=element('homeRooms').children;
  const master=first.children[0].children[0], kitchen=first.children[1].children[0].children[0], bedroom=first.children[1].children[1].children[0];
  assert.equal(master.indeterminate,true); assert.equal(kitchen.checked,true); assert.equal(bedroom.checked,false);
  assert.match(first.textContent,/<img src=x>/); // treated as a text node, not HTML
  master.checked=true; master.handlers.change(); assert.equal(kitchen.checked,true); assert.equal(bedroom.checked,true);
  kitchen.checked=false; kitchen.handlers.change(); assert.equal(master.indeterminate,true);
  await element('homeRoomsSave').handlers.click();
  assert.deepEqual(saved,{rooms:['two','three'],revision:'account-revision'});
  assert.equal(element('homeRoomsMessage').textContent,'Сохранено');
  assert.equal(second.children[0].children[0].checked,true);
  console.log('Home room selection UI test passed');
})().catch(e=>{console.error(e);process.exit(1)});
