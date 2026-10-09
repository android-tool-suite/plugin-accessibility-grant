const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const model = require('../web/settings.js');
const a = 'one.app/ServiceA', b = 'two.app/ServiceB';
const services = [{component:a,appLabel:'应用一',serviceLabel:'手势服务',enabled:false},{component:b,appLabel:'应用二',serviceLabel:'朗读服务',enabled:true}];
async function run(settings, options = {}) {
  const calls = [];
  const scope = {}; vm.createContext(scope);
  vm.runInContext(fs.readFileSync(path.join(__dirname,'../workers/auto-grant.js'),'utf8'),scope);
  const bytes = Buffer.from(JSON.stringify(settings));
  const result = await scope.atsWorkerMain({},{
    decodeBase64:value => Uint8Array.from(Buffer.from(value,'base64')),
    decodeUtf8:value => Buffer.from(value).toString('utf8'),
    call:async (method,payload) => {
      calls.push({method,payload});
      if (method === 'storage.dataset.openRead') return {found:true,handle:'settings'};
      if (method === 'storage.dataset.read') return {bytes:bytes.toString('base64'),eof:true};
      if (method === 'storage.dataset.abort') return {};
      if (method === 'accessibility.getConnection') return {state:options.connection || 'ready'};
      if (method === 'accessibility.listServices') return {services:options.services || services};
      if (method === 'accessibility.setEnabled') {
        if (payload.component === options.fail) throw new Error('系统阻止了此服务');
        return {enabled:true};
      }
      throw new Error('Unexpected call: ' + method);
    }
  });
  return {result:JSON.parse(JSON.stringify(result)),calls};
}
test('旧自动启用选择迁移后，新增收藏不产生新的自动恢复',() => {
  const migrated = model.normalize({formatVersion:1,autoGrant:true,favorites:[a]});
  const updated = model.normalize({...migrated,favorites:[a,b]});
  assert.deepEqual(updated.autoRestore,[a]); assert.equal(updated.autoGrant,false);
});
test('明确清空自动恢复优先于旧开关，未开启的旧收藏不自动恢复',() => {
  assert.deepEqual(model.normalize({formatVersion:1,autoGrant:true,favorites:[a],autoRestore:[]}).autoRestore,[]);
  assert.deepEqual(model.normalize({formatVersion:1,autoGrant:false,favorites:[a]}).autoRestore,[]);
});
test('无效格式不被覆盖成空设置',() => {
  assert.throws(() => model.normalize({formatVersion:4,favorites:[a]}),/无法读取/);
});
test('筛选与搜索组合，自动恢复范围独立于收藏和启用',() => {
  const settings = model.normalize({formatVersion:1,favorites:[b],autoRestore:[a]});
  assert.deepEqual(model.select(services,settings,'automatic','ONE.APP'),[services[0]]);
  assert.deepEqual(model.select(services,settings,'favorites','朗读'),[services[1]]);
  assert.deepEqual(model.select(services,settings,'enabled','手势'),[]);
  assert.deepEqual(settings.autoRestore,[a]);
});
test('Worker 仅恢复显式选中的未启用服务',async () => {
  const {calls,result} = await run({formatVersion:1,favorites:[b],autoGrant:true,autoRestore:[a]});
  assert.deepEqual(calls.filter(item => item.method === 'accessibility.setEnabled').map(item => item.payload.component),[a]);
  assert.equal(result.enabled,1);
});
test('Worker 保留旧自动恢复规则，但显式空列表禁止旧逻辑重新启用',async () => {
  assert.equal((await run({formatVersion:1,favorites:[a],autoGrant:true})).result.enabled,1);
  const empty = await run({formatVersion:1,favorites:[a],autoGrant:true,autoRestore:[]});
  assert.equal(empty.result.reason,'disabled');
  assert.equal(empty.calls.some(item => item.method.startsWith('accessibility.')),false);
});
test('连接失效时不调用设置写入，并返回可重试失败',async () => {
  await assert.rejects(run({formatVersion:1,autoRestore:[a]}, {connection:'offline'}), error => error.retryable === true);
});
test('单个服务被系统拒绝不阻断其他恢复，也不伪造成功',async () => {
  const {result} = await run({formatVersion:1,autoRestore:[a,b]}, {services:services.map(item => ({...item,enabled:false})),fail:a});
  assert.equal(result.enabled,1); assert.deepEqual(result.failed,[{component:a,message:'系统阻止了此服务'}]);
});