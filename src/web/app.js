(() => {
  const pluginId = document.querySelector('meta[name="ats-plugin-id"]')?.content;
  const sessionId = document.querySelector('meta[name="ats-session-id"]')?.content;
  const transport = window.atsTransport;
  if (!pluginId || !sessionId || !transport) return;

  let nextId = 1;
  let readyResolve, readyReject;
  const ready = new Promise((resolve,reject) => { readyResolve = resolve; readyReject = reject; });
  const readyTimeout = setTimeout(() => readyReject(new Error('无法连接宿主，请返回后重新打开工具。')),5000);
  const pending = new Map();
  transport.onmessage = event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message.pluginId !== pluginId || message.sessionId !== sessionId) return;
    if (message.kind === 'ready') { clearTimeout(readyTimeout); readyResolve(message.payload); return; }
    if (message.kind === 'event') { runtimeEvent(message); return; }
    if (message.kind !== 'response') return;
    const request = pending.get(message.requestId);
    if (!request) return;
    pending.delete(message.requestId);
    clearTimeout(request.timeout);
    if (message.ok) request.resolve(message.result || {});
    else request.reject(Object.assign(new Error(message.error?.message || '操作失败'), message.error || {}));
  };
  transport.postMessage(JSON.stringify({protocol:'2.0',kind:'hello',pluginId,sessionId,requestId:'0',payload:{supportedProtocols:['2.0'],features:[]}}));

  async function call(method, payload = {}, deadlineMs = 30000) {
    await ready;
    const requestId = String(nextId++);
    return new Promise((resolve, reject) => {
      const timeout = setTimeout(() => { pending.delete(requestId); reject(new Error('操作超时')); }, deadlineMs);
      pending.set(requestId, {resolve, reject, timeout});
      transport.postMessage(JSON.stringify({protocol:'2.0',kind:'request',pluginId,sessionId,requestId,method,payload,deadlineMs}));
    });
  }

  const toBase64 = bytes => {
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 0x8000) {
      binary += String.fromCharCode(...bytes.subarray(offset, offset + 0x8000));
    }
    return btoa(binary);
  };
  const fromBase64 = value => Uint8Array.from(atob(value), character => character.charCodeAt(0));

  async function readSettings() {
    const opened = await call('storage.dataset.openRead', {datasetId:'accessibility-settings'});
    if (!opened.found) return {formatVersion:1,favorites:[],autoGrant:false};
    const chunks = [];
    let offset = 0;
    try {
      while (true) {
        const part = await call('storage.dataset.read', {handle:opened.handle,offset,maxBytes:131072});
        chunks.push(fromBase64(part.bytes));
        offset += chunks[chunks.length - 1].length;
        if (part.eof) break;
      }
    } finally {
      await call('storage.dataset.abort', {handle:opened.handle}).catch(() => {});
    }
    const bytes = new Uint8Array(chunks.reduce((sum, item) => sum + item.length, 0));
    let cursor = 0;
    for (const chunk of chunks) { bytes.set(chunk, cursor); cursor += chunk.length; }
    const value = JSON.parse(new TextDecoder().decode(bytes));
    return value;
  }

  async function writeSettings(value) {
    const opened = await call('storage.dataset.openWrite', {datasetId:'accessibility-settings'});
    try {
      const bytes = new TextEncoder().encode(JSON.stringify(value));
      for (let offset = 0; offset < bytes.length; offset += 131072) {
        await call('storage.dataset.write', {handle:opened.handle,bytes:toBase64(bytes.subarray(offset, offset + 131072))});
      }
      await call('storage.dataset.commit', {handle:opened.handle});
    } catch (error) {
      await call('storage.dataset.abort', {handle:opened.handle}).catch(() => {});
      throw error;
    }
  }

  const model = globalThis.AccessibilitySettings;
  const state = {settings:model.normalize(null),settingsReady:false,services:[],query:'',searchOpen:false,filter:'all',loading:false,connection:'unknown',background:false,manage:false,busy:new Set(),saving:false,message:'',messageSource:'',expanded:new Set(),visible:true};
  let stopRequest=null;
  const ids = ['content','refresh','connection-card','connection-title','connection-detail','resolve-connection','background-notice','open-permissions','query','query-box','search-toggle','search-close','filters','summary','progress','message','services','settings-error','retry-settings','empty','empty-title','empty-detail','reset-filter'];
  const elements = Object.fromEntries(ids.map(id => [id, document.getElementById(id)]));
  const iconPaths = {
    accessibility:'M20.5 6A10.8 10.8 0 0 1 14 3.82 2.5 2.5 0 1 0 10 3.82 10.8 10.8 0 0 1 3.5 6H2v2h1.5c1.82 0 3.57-.43 5.12-1.22L7 20h2l3-8 3 8h2L15.38 6.78A11.8 11.8 0 0 0 20.5 8H22V6h-1.5Z',
    restore:'M17.65 6.35A7.95 7.95 0 0 0 12 4a8 8 0 1 0 7.75 10h-2.09A6 6 0 1 1 12 6c1.66 0 3.14.69 4.22 1.78L13 11h8V3l-3.35 3.35Z',
    star:'m12 2 3.09 6.26L22 9.27l-5 4.87 1.18 6.88L12 17.77l-6.18 3.25L7 14.14 2 9.27l6.91-1.01L12 2z',
    starBorder:'m12 6.52 1.79 3.63 4.01.58-2.9 2.83.69 3.99L12 15.66l-3.59 1.89.69-3.99-2.9-2.83 4.01-.58L12 6.52M12 2 8.91 8.26 2 9.27l5 4.87-1.18 6.88L12 17.77l6.18 3.25L17 14.14l5-4.87-6.91-1.01L12 2z'
  };
  function node(tag, className, text) {
    const item = document.createElement(tag);
    if (className) item.className = className;
    if (text != null) item.textContent = text;
    return item;
  }
  function icon(name) {
    const svg = document.createElementNS('http://www.w3.org/2000/svg','svg');
    svg.setAttribute('viewBox','0 0 24 24'); svg.setAttribute('aria-hidden','true');
    const path = document.createElementNS('http://www.w3.org/2000/svg','path');
    path.setAttribute('d',iconPaths[name]); svg.append(path); return svg;
  }
  const connectionText = {
    offline:['系统连接暂不可用','先启动设备上的 Shizuku，再返回检查。'],
    disconnected:['系统连接暂不可用','先启动设备上的 Shizuku，再返回检查。'],
    unauthorized:['等待允许本应用','前往 Shizuku 授权，确认本应用的连接请求。'],
    connecting:['正在建立系统连接','前往 Shizuku 授权，重新连接系统服务。'],
    denied:['尚未允许管理无障碍服务','允许此插件的管理权限后，可以读取和调整服务。'],
    error:['无法读取服务','检查连接和权限后重新检查；已有设置不会被清空。'],
    unknown:['连接状态尚未确认','重新检查后可以管理服务。']
  };
  let progressTimer, refreshPromise, refreshPending = false;
  function showMessage(text,source = 'feedback') {
    state.message = text || ''; state.messageSource = text ? source : ''; renderMessage();
  }
  function renderMessage() { elements.message.textContent = state.message; elements.message.hidden = !state.message;elements.message.className='notice'+(state.messageSource&&state.messageSource!=='feedback'?' warning':''); }
  function setLoading(value) {
    state.loading = value; clearTimeout(progressTimer);
    if (value && !elements.content.hidden) progressTimer = setTimeout(() => { elements.progress.hidden = false; },200);
    else elements.progress.hidden = true;
    elements.refresh.disabled = value;
  }
  function permissionSnapshot(permissions) {
    if (!Array.isArray(permissions)) return;
    state.background = permissions.some(item => item.capability === 'scheduler' && item.state === 'granted');
    state.manage = permissions.some(item => item.capability === 'accessibility.manage' && item.state === 'granted');
  }
  function switchControl(service, title, checked, disabled, key, onChange) {
    const label = node('label','service-control'); label.append(node('span','',title));
    const box = node('span','switch'), input = node('input'); input.type = 'checkbox'; input.checked = checked; input.disabled = disabled;
    input.setAttribute('aria-label',`${service.appLabel} · ${service.serviceLabel} · ${title}`);
    input.dataset.control = key + ':' + service.component;
    input.onchange = () => onChange(input.checked);
    const track = node('span'); track.setAttribute('aria-hidden','true'); box.append(input,track); label.append(box); return label;
  }
  function serviceRow(service,compact=false) {
    const favorite = state.settings.favorites.includes(service.component), automatic = state.settings.autoRestore.includes(service.component);
    const busy = state.busy.has(service.component);
    const row = node('article','service-row'), head = node('div','service-head');
    const name = node('button','service-name');name.type='button';name.dataset.control='info:'+service.component;name.setAttribute('aria-label',service.appLabel+' · '+service.serviceLabel+' · 服务详情');name.append(node('strong','',service.serviceLabel));
    if (busy) name.append(node('small','status','处理中'));
    head.append(name);
    const star = node('button',`favorite${favorite ? ' selected' : ''}`);
    star.type = 'button'; star.setAttribute('aria-pressed',String(favorite)); star.setAttribute('aria-label',`${favorite ? '取消收藏' : '收藏'}${service.appLabel} · ${service.serviceLabel}`);
    star.dataset.control = 'favorite:' + service.component; star.disabled = !state.settingsReady || state.saving;
    star.append(icon(favorite ? 'star' : 'starBorder'));
    star.onclick = () => saveMembership(service,'favorites',!favorite);
    const restore = node('button',`restore${automatic ? ' selected' : ''}`);
    restore.type='button';restore.setAttribute('aria-pressed',String(automatic));
    restore.setAttribute('aria-label',`${automatic ? '关闭' : '开启'}自动恢复 · ${service.appLabel} · ${service.serviceLabel}`);
    restore.title=automatic?'自动恢复已开启，点击关闭':'自动恢复已关闭，点击开启';
    restore.dataset.control='automatic:'+service.component;
    restore.disabled=busy || state.saving || !state.settingsReady || (!state.background&&!automatic);
    restore.append(icon('restore'));restore.onclick=()=>saveMembership(service,'autoRestore',!automatic);
    head.append(star,restore,switchControl(service,'启用',service.enabled,busy || state.loading || state.saving || (!state.settingsReady&&service.enabled),'enabled',value => setEnabled(service,value)));
    const info = node('button','service-info','详情');info.type='button';info.dataset.control='info:'+service.component;
    info.setAttribute('aria-label',`${service.appLabel} · ${service.serviceLabel} · 服务详情`);
    info.onclick=()=>{
      document.getElementById('service-detail-title').textContent=service.appLabel===service.serviceLabel?service.serviceLabel:service.appLabel+' · '+service.serviceLabel;
      document.getElementById('service-component').textContent=service.component;
      document.getElementById('service-detail').showModal();
      call('app.setBackNavigation',{enabled:true}).catch(()=>{});
    };
    name.onclick=info.onclick;row.append(head);
    if(automatic&&!service.enabled)name.append(node('small','auto-badge',state.background?'等待检查恢复':'恢复规则已保存'));
    return row;
  }
  function render() {
    const activeKey = document.activeElement?.dataset?.control;
    const connected = state.connection === 'ready';
    elements['connection-card'].hidden = connected;
    const [title,detail] = connectionText[state.connection] || connectionText.unknown;
    elements['connection-title'].textContent = title; elements['connection-detail'].textContent = detail;
    elements['resolve-connection'].textContent = state.connection === 'denied' ? '查看权限' : '处理连接';
    elements['background-notice'].hidden = state.background;
    elements['settings-error'].hidden = state.settingsReady;
    for (const button of elements.filters.querySelectorAll('[data-filter]')) button.setAttribute('aria-pressed',String(button.dataset.filter === state.filter));
    const visible = connected ? model.select(state.services,state.settings,state.filter,state.query) : [];
    elements.summary.textContent = connected ? `${visible.length} 个服务 · ${visible.filter(service=>service.enabled).length} 个已启用` : '连接就绪后显示服务';
    elements['query-box'].hidden=!state.searchOpen&&!state.query;
    elements['search-toggle'].setAttribute('aria-expanded',String(state.searchOpen||!!state.query));
    const groups=new Map();for(const service of visible){const key=service.component.split('/')[0];if(!groups.has(key))groups.set(key,[]);groups.get(key).push(service);}
    elements.services.replaceChildren(...[...groups.values()].map(services=>{
      const group=node('section','service-group'),header=node('div','service-group-header'),mark=node('span','service-icon');mark.append(icon('accessibility'));
      const application=services[0].appLabel===services[0].component.split('/')[0]&&services.length===1?services[0].serviceLabel:services[0].appLabel;
      header.append(mark,node('h2','',application));if(services.length>1)header.append(node('span','group-count',services.length+' 个服务'));
      if(services.length===1&&application===services[0].serviceLabel)group.append(serviceRow(services[0],true));else group.append(header,...services.map(service=>serviceRow(service)));return group;
    }));
    elements.empty.hidden = visible.length > 0;
    elements['empty-title'].textContent = !connected ? '先完成准备条件' : state.services.length ? '没有匹配的服务' : '没有发现无障碍服务';
    elements['empty-detail'].textContent = !connected ? '服务的原有设置不会因暂时无法读取而被清空。' : state.services.length ? '清除搜索或切换列表范围。' : '安装提供无障碍服务的应用后，重新检查。';
    elements['reset-filter'].hidden = !connected || (!state.query && state.filter === 'all');
    renderMessage();
    if (activeKey) [...elements.services.querySelectorAll('[data-control]')].find(item => item.dataset.control === activeKey)?.focus({preventScroll:true});
  }
  async function loadSettings() {
    try { state.settings = model.normalize(await readSettings()); state.settingsReady = true; }
    catch (error) { state.settingsReady = false; showMessage(error.message || '无法读取设置','settings-read'); }
  }
  async function refresh(userInitiated = false) {
    if (refreshPromise) { refreshPending = true; return refreshPromise; }
    do {
      refreshPending = false;
      refreshPromise = (async () => {
        setLoading(true);
        try {
          const connection = await call('accessibility.getConnection',{},5000);
          state.connection = connection.state;
          if (connection.state === 'ready') {
            const result = await call('accessibility.listServices');
            state.services = Array.isArray(result.services) ? result.services : [];
            state.manage = true;
            if (state.messageSource === 'services-read') showMessage('');
            if (userInitiated) showMessage('服务状态已更新');
          }
        } catch (error) {
          state.connection = error.code === 'PERMISSION_DENIED' ? 'denied' : 'error';
          showMessage(error.message || '读取服务失败','services-read');
        } finally { setLoading(false); render(); }
      })();
      try { await refreshPromise; } finally { refreshPromise = null; }
      // A completed restore may arrive while this query still contains the old state.
    } while (refreshPending && state.visible);
  }
  async function saveMembership(service,key,checked) {
    if (!state.settingsReady || state.saving) return;
    state.saving = true; render();
    const values = new Set(state.settings[key]); checked ? values.add(service.component) : values.delete(service.component);
    const candidate = model.normalize({...state.settings,[key]:[...values]});
    try {
      await writeSettings(candidate); state.settings = candidate;
      showMessage(key === 'favorites' ? checked ? '已收藏服务' : '已取消收藏' : checked ? '已加入自动恢复，下一次检查会尝试启用' : '已关闭此服务的自动恢复');
    } catch (error) { showMessage(error.message || '无法保存设置，原设置已保留','settings-write'); }
    finally { state.saving = false; render(); }
  }
  async function setEnabled(service,enabled,stopMode=null) {
    if(state.busy.has(service.component)||state.saving)return;
    if(!enabled&&!state.settingsReady){showMessage('尚未读取自动恢复规则，请先重新读取设置','settings-read');render();return;}
    if(!enabled&&state.settings.autoRestore.includes(service.component)&&!stopMode){stopRequest=service;render();document.getElementById('stop-service-name').textContent=service.appLabel+' · '+service.serviceLabel;document.getElementById('stop-service').showModal();call('app.setBackNavigation',{enabled:true}).catch(()=>{});return;}
    if (state.busy.has(service.component)) return;
    state.busy.add(service.component); render();
    let paused=false;
    try {
      if(stopMode==='pause'){state.saving=true;render();const candidate=model.normalize({...state.settings,autoRestore:state.settings.autoRestore.filter(component=>component!==service.component)});await writeSettings(candidate);state.settings=candidate;paused=true;}
      await call('accessibility.setEnabled',{component:service.component,enabled});
      await refresh();
      showMessage(enabled ? '服务已启用' : state.settings.autoRestore.includes(service.component) ? '服务已停用；自动恢复仍开启，下一次检查可能重新启用' : '服务已停用');
    } catch (error) {
      await refresh();showMessage((paused?'自动恢复已暂停，服务未完成停用：':'')+(error.message || '系统未完成操作'),'service-change');
    } finally {state.saving=false;state.busy.delete(service.component);render();}
  }
  async function navigate(method,payload = {}) {
    try { await call(method,payload,5000); } catch (error) { showMessage(error.message || '无法打开页面','navigation'); }
  }
  const stopDialog=document.getElementById('stop-service');
  stopDialog.onclose=()=>{stopRequest=null;call('app.setBackNavigation',{enabled:document.getElementById('service-detail').open}).catch(()=>{});};
  document.getElementById('stop-cancel').onclick=()=>stopDialog.close();
  for(const [id,mode] of [['stop-once','once'],['stop-pause','pause']])document.getElementById(id).onclick=()=>{const service=stopRequest;stopDialog.close();if(service)setEnabled(service,false,mode);};
  document.getElementById('service-detail').onclose=()=>call('app.setBackNavigation',{enabled:false}).catch(()=>{});
  document.getElementById('service-detail-close').onclick=()=>document.getElementById('service-detail').close();
  elements['search-toggle'].onclick=()=>{state.searchOpen=!state.searchOpen;if(!state.searchOpen){state.query='';elements.query.value='';}render();if(state.searchOpen)elements.query.focus();};
  elements['search-close'].onclick=()=>{state.searchOpen=false;state.query='';elements.query.value='';render();};
  elements.refresh.onclick = () => refresh(true);
  elements.query.oninput = event => { state.query = event.target.value; render(); };
  elements.filters.onclick = event => { const button = event.target.closest('[data-filter]'); if (button) { state.filter = button.dataset.filter; render(); } };
  elements['reset-filter'].onclick = () => { state.query = ''; state.filter = 'all'; elements.query.value = ''; render(); };
  elements['open-permissions'].onclick = () => navigate('app.openPermissions');
  elements['resolve-connection'].onclick = () => state.connection === 'denied' ? navigate('app.openPermissions') : navigate('app.openTool',{pluginId:'shizuku_auth',toolId:'main'});
  elements['retry-settings'].onclick = async () => { await loadSettings(); render(); };
  function runtimeEvent(message) {
    const payload = message.payload || {};
    if(message.event==='app.backRequested'&&stopDialog.open){stopDialog.close();return;}
    if(message.event==='app.backRequested'&&document.getElementById('service-detail').open){document.getElementById('service-detail').close();return;}
    if (message.event === 'app.permissionsChanged') {
      if (payload.capability === 'scheduler') state.background = payload.granted === true;
      if (payload.capability === 'accessibility.manage') {
        state.manage = payload.granted === true;
        if (!state.manage) { state.connection = 'denied'; state.services = []; }
      }
      render(); if (state.visible) refresh();
    }
    if (message.event === 'app.visibilityChanged') {
      state.visible = payload.visible !== false;
      if (state.visible) refresh();
    }
    if (message.event === 'accessibility.connectionChanged' || message.event === 'shizuku.connectionChanged') {
      state.connection = payload.state || 'unknown'; render();
      if (state.visible) refresh();
    }
    if (message.event === 'accessibility.servicesChanged' && state.visible) refresh();
    if (message.event === 'scheduler.runChanged' && payload.taskId === 'auto-grant'
        && ['succeeded','failed','cancelled'].includes(payload.status) && state.visible) refresh();
  }
  (async () => {
    try { const session = await ready; permissionSnapshot(session?.permissions); await Promise.all([loadSettings(),refresh()]); }
    catch (error) { state.connection = 'error'; showMessage(error.message || '无法连接宿主','runtime'); }
    finally { render(); elements.content.hidden = false; }
  })();
})();
