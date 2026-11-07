// Exercise the panel's actual weather handlers with a fake same-origin API.
const assert=require('node:assert/strict'),fs=require('node:fs'),vm=require('node:vm');
const html=fs.readFileSync('app/src/main/assets/index.html','utf8');
const code=html.slice(html.indexOf("let weatherSearchedQuery='';"),html.indexOf("el('refresh').addEventListener"));
const nodes={};for(const id of ['weatherLocation','weatherQuery','weatherSearch','weatherClear','weatherSave','weatherResults','weatherResultsRow','weatherMessage'])nodes[id]={value:'',textContent:'',hidden:false,disabled:false,children:[],handlers:{},addEventListener(t,f){this.handlers[t]=f},replaceChildren(){this.children=[]},appendChild(c){this.children.push(c);if(this.children.length===1)this.value=c.value}};
let saved=null;const writes=[];
const ctx={el:id=>nodes[id],document:{createElement:()=>({})},request:async(path,opt)=>{
 if(!opt)return {location:saved};const data=JSON.parse(opt.body);writes.push({path,data});
 if(path.endsWith('/search'))return {results:[{id:1,label:'Тест, Россия'},{id:2,label:'Тест, Франция'}]};
 if(data.action==='clear'){saved=null;return {message:'Город удалён'}}
 saved=data.id===2?'Тест, Франция':'Тест, Россия';return {message:'Город сохранён',location:saved};
}};
vm.runInNewContext(code,ctx);
(async()=>{
 await new Promise(r=>setImmediate(r));assert.equal(nodes.weatherLocation.textContent,'Город не выбран');
 nodes.weatherQuery.value='Тест';await nodes.weatherSearch.handlers.click();assert.equal(nodes.weatherResults.children.length,2);assert.equal(nodes.weatherResultsRow.hidden,false);
 nodes.weatherResults.value='2';await nodes.weatherSave.handlers.click();assert.deepEqual(writes[1].data,{query:'Тест',id:2});assert.equal(nodes.weatherLocation.textContent,'Тест, Франция');
 await nodes.weatherClear.handlers.click();assert.equal(nodes.weatherLocation.textContent,'Город не выбран');
 nodes.weatherQuery.value='Новый';nodes.weatherQuery.handlers.input();assert.equal(nodes.weatherResultsRow.hidden,true);
 assert.match(html,/координаты выбранного города/);assert.match(html,/город погоды/);
 console.log('Weather panel test passed');
})().catch(e=>{console.error(e);process.exitCode=1});
