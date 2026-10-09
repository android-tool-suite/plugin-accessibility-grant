globalThis.atsWorkerMain = async function (_input, ats) {
  const opened = await ats.call('storage.dataset.openRead', {datasetId:'accessibility-settings'});
  if (!opened.found) return {enabled:0,reason:'no-settings'};
  const chunks = [];
  let offset = 0;
  try {
    while (true) {
      const part = await ats.call('storage.dataset.read', {handle:opened.handle,offset,maxBytes:131072});
      const bytes = ats.decodeBase64(part.bytes); chunks.push(bytes); offset += bytes.length;
      if (part.eof) break;
    }
  } finally {
    await ats.call('storage.dataset.abort', {handle:opened.handle}).catch(() => {});
  }
  const bytes = new Uint8Array(chunks.reduce((sum, item) => sum + item.length, 0));
  let cursor = 0; for (const chunk of chunks) { bytes.set(chunk, cursor); cursor += chunk.length; }
  const settings = JSON.parse(ats.decodeUtf8(bytes));
  if (settings.formatVersion !== 1) throw new Error('Unsupported accessibility settings format');
  // An explicit empty list must override the legacy favorites-based opt-in.
  const configured = Array.isArray(settings.autoRestore) ? settings.autoRestore
    : settings.autoGrant === true && Array.isArray(settings.favorites) ? settings.favorites : [];
  const automatic = new Set(configured.filter(value => typeof value === 'string' && value.includes('/') && value.length <= 512));
  if (!automatic.size) {
    return {enabled:0,reason:'disabled'};
  }
  const connection = await ats.call('accessibility.getConnection', {});
  if (connection.state !== 'ready') throw Object.assign(new Error(`accessibility provider is ${connection.state}`), {retryable:true});
  const result = await ats.call('accessibility.listServices', {});
  let enabled = 0;
  const failed = [];
  for (const service of result.services || []) {
    if (automatic.has(service.component) && !service.enabled) {
      try {
        await ats.call('accessibility.setEnabled', {component:service.component,enabled:true});
        enabled++;
      } catch (error) {
        failed.push({component:service.component,message:String(error?.message || '系统未允许启用')});
      }
    }
  }
  return {enabled,failed};
};
