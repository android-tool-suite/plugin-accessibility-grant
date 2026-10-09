const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const model = require('../web/settings.js');
const source = fs.readFileSync(path.join(__dirname,'../web/app.js'),'utf8');
class Element {
  constructor(tag) { this.tagName=tag;this.children=[];this.dataset={};this.hidden=false;this.isConnected=true;this.text=''; }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children=children;this.text=''; }
  setAttribute(name,value) {(this.attributes??={})[name]=String(value);}
  getAttribute(name) {return this.attributes?.[name]??null;}
  focus() {}
  showModal() {this.open=true;}
  close() {this.open=false;this.onclose?.();}
  set textContent(value) { this.text=String(value);this.children=[]; }
  get textContent() { return this.text+this.children.map(item=>item.textContent).join(''); }
  querySelectorAll(selector) {
    const name=selector==='[data-filter]'?'filter':'control',result=[];
    for(const child of this.children){if(child.dataset?.[name])result.push(child);if(child.querySelectorAll)result.push(...child.querySelectorAll(selector));}
    return result;
  }
}

test('all runtime UI IDs remain available in the actual page',()=>{
  const html=fs.readFileSync(path.join(__dirname,'../web/index.html'),'utf8');
  const ids=source.match(/const ids = \[([^\]]+)\]/)[1].match(/'([^']+)'/g).map(value=>value.slice(1,-1));
  for(const id of ids)assert.match(html,new RegExp(`\\bid="${id}"`),`Missing runtime element ${id}`);
});
const flush = () => new Promise(resolve=>setImmediate(resolve));
async function setup(denied = true) {
  const elements=new Map(),get=id=>{if(!elements.has(id))elements.set(id,new Element('div'));return elements.get(id);};
  get('content').hidden=true;
  const native={denied,saveError:false,toggleError:false,enabled:false,calls:[],writes:[]};
  const identity={pluginId:'test.accessibility',sessionId:'test-session'};
  const transport={onmessage:null,postMessage(raw){
    const request=JSON.parse(raw);
    native.calls.push(request);
    queueMicrotask(()=>{
      if(request.kind==='hello'){
        transport.onmessage({data:JSON.stringify({...identity,kind:'ready',payload:{permissions:[{capability:'scheduler',state:'granted'},{capability:'accessibility.manage',state:denied?'denied':'granted'}]}})});return;
      }
      let result={},error=null;
      if(request.method==='storage.dataset.openRead')result={found:false};
      if(request.method==='accessibility.getConnection'){
        if(native.denied)error={code:'PERMISSION_DENIED',message:'请在管理页允许插件权限：管理无障碍服务'};
        else result={state:'ready'};
      }
      if(request.method==='accessibility.listServices')result={services:[{component:'sample.app/Service',appLabel:'测试应用',serviceLabel:'测试服务',enabled:native.enabled}]};
      if(request.method==='storage.dataset.openWrite'){
        if(native.saveError)error={code:'INTERNAL',message:'设置保存失败'};
        else result={handle:'test-write'};
      }
      if(request.method==='accessibility.setEnabled'&&native.toggleError)error={code:'SYSTEM_REJECTED',message:'系统阻止了此服务'};
      else if(request.method==='accessibility.setEnabled')native.enabled=request.payload.enabled;
      if(request.method==='storage.dataset.write')native.writes.push(JSON.parse(Buffer.from(request.payload.bytes,'base64').toString('utf8')));
      transport.onmessage({data:JSON.stringify({...identity,kind:'response',requestId:request.requestId,ok:!error,result,error})});
    });
  }};
  const document={querySelector:selector=>({content:selector.includes('ats-plugin-id')?identity.pluginId:identity.sessionId}),getElementById:get,createElement:tag=>new Element(tag),createElementNS:(_,tag)=>new Element(tag),activeElement:null};
  const context={document,window:{atsTransport:transport},AccessibilitySettings:model,setTimeout,clearTimeout,TextEncoder,TextDecoder,Uint8Array,atob,btoa};
  vm.createContext(context);vm.runInContext(source,context);await flush();
  return {native,get,control:prefix=>get('services').querySelectorAll('[data-control]').find(item=>item.dataset.control.startsWith(prefix)),event:async(name,payload={})=>{
    transport.onmessage({data:JSON.stringify({...identity,kind:'event',event:name,payload})});await flush();
  }};
}
test('权限允许后成功读取真实服务清除旧服务读取错误',async()=>{
  const ui=await setup();
  assert.match(ui.get('message').textContent,/管理无障碍服务/);
  ui.native.denied=false;
  await ui.event('app.permissionsChanged',{capability:'accessibility.manage',granted:true});
  assert.equal(ui.get('services').children.length,1);
  assert.equal(ui.get('message').hidden,true);
});
test('设置保存错误保留，后台服务读取成功不会误清',async()=>{
  const ui=await setup(false);ui.native.saveError=true;
  await ui.control('favorite:').onclick();
  assert.equal(ui.get('message').textContent,'设置保存失败');
  await ui.event('accessibility.servicesChanged');
  assert.equal(ui.get('message').textContent,'设置保存失败');
  assert.equal(ui.get('message').hidden,false);
});
test('系统启停错误及手动刷新结果分别保留',async()=>{
  const ui=await setup(false);ui.native.toggleError=true;
  const input=ui.control('enabled:');input.checked=true;await input.onchange();
  await ui.event('accessibility.servicesChanged');
  assert.equal(ui.get('message').textContent,'系统阻止了此服务');
  await ui.get('refresh').onclick();
  assert.equal(ui.get('message').textContent,'服务状态已更新');
  await ui.event('accessibility.servicesChanged');
  assert.equal(ui.get('message').textContent,'服务状态已更新');
});

test('optional service search closes cleanly and grouping retains independent controls',async()=>{
  const ui=await setup(false);
  assert.equal(ui.get('query-box').hidden,true);assert.equal(ui.get('services').children[0].className,'service-group');
  assert.ok(ui.control('enabled:'));assert.ok(ui.control('automatic:'));assert.ok(ui.control('favorite:'));
  ui.get('search-toggle').onclick();assert.equal(ui.get('query-box').hidden,false);
  ui.get('query').oninput({target:{value:'missing'}});assert.equal(ui.get('services').children.length,0);
  ui.get('search-close').onclick();assert.equal(ui.get('query-box').hidden,true);assert.equal(ui.get('services').children.length,1);
});

test('revoked background permission keeps an existing restore rule removable',async()=>{
  const ui=await setup(false);await ui.control('automatic:').onclick();
  await ui.event('app.permissionsChanged',{capability:'scheduler',granted:false});
  assert.equal(ui.control('automatic:').getAttribute('aria-pressed'),'true');assert.equal(ui.control('automatic:').disabled,false);
  await ui.control('automatic:').onclick();
  assert.equal(ui.control('automatic:').getAttribute('aria-pressed'),'false');assert.equal(ui.control('automatic:').disabled,true);
});

for(const mode of ['pause','once'])test(`stopping an automatically restored service requires an explicit ${mode} choice`,async()=>{
  const ui=await setup(false);ui.native.enabled=true;await ui.event('accessibility.servicesChanged');
  await ui.control('automatic:').onclick();
  const toggle=ui.control('enabled:');toggle.checked=false;await toggle.onchange();
  assert.equal(ui.get('stop-service').open,true);assert.equal(ui.native.enabled,true);
  ui.get(mode==='pause'?'stop-pause':'stop-once').onclick();await flush();
  assert.equal(ui.native.enabled,false);assert.equal(ui.control('automatic:').getAttribute('aria-pressed'),String(mode==='once'));
  const requests=ui.native.calls.map(request=>request.method);if(mode==='pause')assert.ok(requests.lastIndexOf('storage.dataset.commit')<requests.lastIndexOf('accessibility.setEnabled'));
});
test('a failed pause does not disable the service or silently alter its restore rule',async()=>{
  const ui=await setup(false);ui.native.enabled=true;await ui.event('accessibility.servicesChanged');
  await ui.control('automatic:').onclick();ui.native.saveError=true;
  const toggle=ui.control('enabled:');toggle.checked=false;await toggle.onchange();ui.get('stop-pause').onclick();await flush();
  assert.equal(ui.native.enabled,true);assert.equal(ui.control('automatic:').getAttribute('aria-pressed'),'true');assert.equal(ui.native.calls.filter(request=>request.method==='accessibility.setEnabled').length,0);assert.match(ui.get('message').textContent,/设置保存失败/);
});
test('a rejected stop reports the already saved pause without claiming that the service stopped',async()=>{
  const ui=await setup(false);ui.native.enabled=true;await ui.event('accessibility.servicesChanged');
  await ui.control('automatic:').onclick();ui.native.toggleError=true;
  const toggle=ui.control('enabled:');toggle.checked=false;await toggle.onchange();ui.get('stop-pause').onclick();await flush();
  assert.equal(ui.native.enabled,true);assert.equal(ui.control('automatic:').getAttribute('aria-pressed'),'false');assert.match(ui.get('message').textContent,/自动恢复已暂停，服务未完成停用/);
});
