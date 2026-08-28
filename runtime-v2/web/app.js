(() => {
  const pluginId = document.querySelector('meta[name="ats-plugin-id"]')?.content;
  const sessionId = document.querySelector('meta[name="ats-session-id"]')?.content;
  const transport = window.atsTransport;
  if (!pluginId || !sessionId || !transport) return;

  let nextId = 1;
  let readyResolve;
  const ready = new Promise(resolve => { readyResolve = resolve; });
  const pending = new Map();
  transport.onmessage = event => {
    let message;
    try { message = JSON.parse(event.data); } catch (_) { return; }
    if (message.pluginId !== pluginId || message.sessionId !== sessionId) return;
    if (message.kind === 'ready') { readyResolve(message.payload); return; }
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
    return value.formatVersion === 1 ? value : {formatVersion:1,favorites:[],autoGrant:false};
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

  const state = {settings:{formatVersion:1,favorites:[],autoGrant:false},services:[],query:'',favoritesOnly:false,loading:false};
  const elements = Object.fromEntries(['refresh','connection-card','connection-title','connection-detail','query','favorites-only','auto-grant','summary','progress','message','services'].map(id => [id, document.getElementById(id)]));
  const iconPaths = {
    accessibility:'M20.5 6A10.8 10.8 0 0 1 14 3.82 2.5 2.5 0 1 0 10 3.82 10.8 10.8 0 0 1 3.5 6H2v2h1.5c1.82 0 3.57-.43 5.12-1.22L7 20h2l3-8 3 8h2L15.38 6.78A11.8 11.8 0 0 0 20.5 8H22V6h-1.5Z',
    favorite:'M12 21.35 10.55 20.03C5.4 15.36 2 12.28 2 8.5 2 5.42 4.42 3 7.5 3c1.74 0 3.41.81 4.5 2.09A6.02 6.02 0 0 1 16.5 3C19.58 3 22 5.42 22 8.5c0 3.78-3.4 6.86-8.55 11.54L12 21.35Z',
    favoriteBorder:'M16.5 3c-1.74 0-3.41.81-4.5 2.09A6.02 6.02 0 0 0 7.5 3C4.42 3 2 5.42 2 8.5c0 3.78 3.4 6.86 8.55 11.54L12 21.35l1.45-1.32C18.6 15.36 22 12.28 22 8.5 22 5.42 19.58 3 16.5 3Zm-4.4 15.55-.1.1-.1-.1C7.14 14.24 4 11.39 4 8.5 4 6.5 5.5 5 7.5 5c1.54 0 3.04.99 3.57 2.36h1.87C13.46 5.99 14.96 5 16.5 5c2 0 3.5 1.5 3.5 3.5 0 2.89-3.14 5.74-7.9 10.05Z',
    check:'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20Zm-2 15-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9Z',
    cloudOff:'M19.35 10.04A7.49 7.49 0 0 0 5.32 6.5L1.39 2.57 0 3.97l3.78 3.78A5.99 5.99 0 0 0 6 19h9.02l2 2 1.41-1.41L3.27 4.43 1.86 5.84l2.23 2.23A4 4 0 0 0 6 17h7.02L5.14 9.12A5.5 5.5 0 0 1 16.9 11.5v.5h1.5a2.5 2.5 0 0 1 1.87 4.16l1.42 1.42A4.5 4.5 0 0 0 19.35 10.04Z'
  };
  const svgIcon = name => {
    const svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24'); svg.setAttribute('aria-hidden', 'true');
    const path = document.createElementNS('http://www.w3.org/2000/svg', 'path'); path.setAttribute('d', iconPaths[name]);
    svg.append(path); return svg;
  };
  const connectionText = {
    ready:['运行正常','Shizuku 已连接，可以管理无障碍服务','check'],
    connecting:['正在连接服务','Shizuku 已授权，正在连接系统服务','cloudOff'],
    unauthorized:['等待 Shizuku 授权','请先在“Shizuku 授权”工具中批准本应用','cloudOff'],
    disconnected:['Shizuku 未连接','请先在设备上启动 Shizuku，再返回刷新','cloudOff']
  };

  function setLoading(value) { state.loading = value; elements.progress.hidden = !value; render(); }
  function showMessage(value) { elements.message.textContent = value || ''; elements.message.hidden = !value; }
  function render() {
    elements['auto-grant'].checked = !!state.settings.autoGrant;
    elements['favorites-only'].setAttribute('aria-pressed', String(state.favoritesOnly));
    const query = state.query.trim().toLowerCase();
    const favorites = new Set(state.settings.favorites);
    const visible = state.services.filter(service => (!state.favoritesOnly || favorites.has(service.component)) && (!query || `${service.appLabel} ${service.serviceLabel} ${service.component}`.toLowerCase().includes(query)));
    const enabled = state.services.filter(item => item.enabled).length;
    elements.summary.textContent = state.services.length ? `${state.services.length} 个服务 · ${enabled} 个已启用` : '等待读取设备服务';
    elements.services.replaceChildren(...visible.map(service => {
      const card = document.createElement('article'); card.className = 'service-card';
      const head = document.createElement('div'); head.className = 'service-head';
      const icon = document.createElement('div'); icon.className = 'service-icon'; icon.append(svgIcon('accessibility'));
      const name = document.createElement('div'); name.className = 'service-name';
      const app = document.createElement('strong'); app.textContent = service.appLabel;
      const label = document.createElement('span'); label.textContent = service.serviceLabel;
      name.append(app,label);
      const favorite = document.createElement('button'); favorite.type = 'button'; favorite.className = `favorite${favorites.has(service.component) ? ' active' : ''}`; favorite.append(svgIcon(favorites.has(service.component) ? 'favorite' : 'favoriteBorder')); favorite.setAttribute('aria-label', favorites.has(service.component) ? '取消收藏' : '收藏');
      favorite.onclick = async () => {
        const values = new Set(state.settings.favorites);
        values.has(service.component) ? values.delete(service.component) : values.add(service.component);
        state.settings.favorites = [...values].sort(); render();
        try {
          await writeSettings(state.settings);
          if (state.settings.autoGrant && values.has(service.component) && !service.enabled) {
            await call('accessibility.setEnabled', {component:service.component,enabled:true});
            await refresh();
          }
        } catch (error) { showMessage(error.message); }
      };
      head.append(icon,name,favorite);
      const component = document.createElement('div'); component.className = 'component'; component.textContent = service.component;
      const actions = document.createElement('div'); actions.className = 'service-actions';
      const status = document.createElement('span'); status.className = `status${service.enabled ? ' enabled' : ''}`; status.textContent = service.enabled ? '已启用' : '未启用';
      const spacer = document.createElement('span'); spacer.className = 'spacer';
      const action = document.createElement('button'); action.type = 'button'; action.className = `action${service.enabled ? '' : ' primary'}`; action.textContent = service.enabled ? '停用' : '启用'; action.disabled = state.loading;
      action.onclick = () => setEnabled(service, !service.enabled);
      actions.append(status,spacer,action); card.append(head,component,actions); return card;
    }));
    if (!state.loading && !visible.length) showMessage(state.services.length ? '没有匹配的服务。请清除搜索内容或关闭“仅看收藏”。' : elements.message.hidden ? '没有发现无障碍服务。' : elements.message.textContent);
    else if (visible.length) showMessage('');
  }

  async function refresh() {
    setLoading(true); showMessage('');
    try {
      const connection = await call('accessibility.getConnection');
      const key = connectionText[connection.state] ? connection.state : 'disconnected';
      const [title,detail,icon] = connectionText[key];
      elements['connection-title'].textContent = title; elements['connection-detail'].textContent = detail;
      elements['connection-card'].querySelector('.status-icon path').setAttribute('d', iconPaths[icon]);
      elements['connection-card'].classList.toggle('ready', key === 'ready');
      if (key !== 'ready') { state.services = []; showMessage(detail); return; }
      const result = await call('accessibility.listServices'); state.services = result.services || [];
    } catch (error) { state.services = []; showMessage(error.message || '读取服务失败'); }
    finally { setLoading(false); }
  }

  async function setEnabled(service, enabled) {
    setLoading(true);
    try { await call('accessibility.setEnabled', {component:service.component,enabled}); await refresh(); }
    catch (error) {
      const message = error.message || '操作失败';
      await refresh();
      showMessage(message);
    }
  }

  elements.refresh.onclick = refresh;
  elements.query.oninput = event => { state.query = event.target.value; render(); };
  elements['favorites-only'].onclick = () => { state.favoritesOnly = !state.favoritesOnly; render(); };
  elements['auto-grant'].onchange = async event => {
    state.settings.autoGrant = event.target.checked; render();
    try {
      await writeSettings(state.settings);
      if (state.settings.autoGrant) {
        const favorites = new Set(state.settings.favorites);
        const failures = [];
        for (const service of state.services.filter(item => favorites.has(item.component) && !item.enabled)) {
          try {
            await call('accessibility.setEnabled', {component:service.component,enabled:true});
          } catch (error) {
            failures.push(error.message || '有收藏服务未能自动启用');
          }
        }
        await refresh();
        if (failures.length) showMessage(failures[0]);
      }
    } catch (error) { showMessage(error.message || '无法保存自动授权设置'); }
  };

  (async () => { await ready; try { state.settings = await readSettings(); } catch (error) { showMessage(error.message); } render(); await refresh(); })();
})();
