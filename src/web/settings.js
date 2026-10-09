(function (root) {
  'use strict';
  function components(values) {
    return [...new Set((Array.isArray(values) ? values : []).filter(value => typeof value === 'string' && value.includes('/') && value.length <= 512))].sort();
  }
  function normalize(value) {
    if (value == null) return {formatVersion:1, favorites:[], autoGrant:false, autoRestore:[]};
    if (value.formatVersion !== 1) throw new Error('无法读取此版本的无障碍设置，请更新插件后重试。');
    const favorites = components(value.favorites);
    // Preserve existing opt-ins; later favorites must never acquire automatic restore.
    const autoRestore = Array.isArray(value.autoRestore) ? components(value.autoRestore) : value.autoGrant === true ? [...favorites] : [];
    return {formatVersion:1, favorites, autoGrant:false, autoRestore};
  }
  function select(services, settings, filter, query) {
    const needle = String(query || '').trim().toLocaleLowerCase();
    return services.filter(service => {
      const matches = filter === 'all' || filter === 'favorites' && settings.favorites.includes(service.component)
        || filter === 'enabled' && service.enabled || filter === 'automatic' && settings.autoRestore.includes(service.component);
      return matches && (!needle || `${service.appLabel} ${service.serviceLabel} ${service.component}`.toLocaleLowerCase().includes(needle));
    });
  }
  const model = {normalize, select};
  if (typeof module !== 'undefined' && module.exports) module.exports = model;
  else root.AccessibilitySettings = model;
})(globalThis);