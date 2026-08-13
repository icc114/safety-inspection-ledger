(function () {
  'use strict';

  const CONFIG_KEY = 'safety_ledger_cloud_config_v1';
  const DEVICE_ID_KEY = 'safety_ledger_device_id_v1';
  const HOLIDAY_CACHE_KEY = 'safety_ledger_online_holidays_v1';
  const PLAIN_CHUNK_SIZE = 320 * 1024;
  const TEAM_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';
  const PROVIDERS = {
    cloudflare: {
      id: 'cloudflare',
      name: '配套免费云服务（Cloudflare）',
      endpointPlaceholder: 'https://你的地址.workers.dev',
      help: '填写配套免费云同步服务部署后生成的地址'
    },
    fnos: {
      id: 'fnos',
      name: '飞牛 NAS',
      endpointPlaceholder: 'https://你的NAS同步地址',
      help: '需先在飞牛 NAS 部署配套同步服务；局域网私有地址可使用 http://'
    },
    google_drive: {
      id: 'google_drive',
      name: 'Google Drive',
      endpointPlaceholder: 'https://你的Google Drive同步网关地址',
      help: '填写已授权 Google Drive 的配套同步网关地址，不是网盘分享链接'
    },
    compatible: {
      id: 'compatible',
      name: '其他兼容同步服务',
      endpointPlaceholder: 'https://你的同步服务地址',
      help: '填写支持“安全检查台账同步协议 v3”兼容服务的地址'
    }
  };

  function CloudSyncService(options) {
    options = options || {};
    this.fetchImpl = options.fetchImpl || function () { return window.fetch.apply(window, arguments); };
    this.storage = options.storage || window.localStorage;
    this.config = this.loadConfig();
    this.running = false;
  }

  CloudSyncService.prototype.loadConfig = function () {
    try {
      const parsed = JSON.parse(this.storage.getItem(CONFIG_KEY) || 'null');
      if (!parsed || (parsed.version !== 1 && parsed.version !== 2)) return null;
      parsed.version = 2;
      parsed.providerId = normalizeProviderId(parsed.providerId || inferProviderId(parsed.endpoint));
      parsed.workspaceName = String(parsed.workspaceName || parsed.teamName || '安全检查台账');
      return parsed;
    } catch (_) {
      return null;
    }
  };

  CloudSyncService.prototype.saveConfig = function () {
    if (this.config) this.storage.setItem(CONFIG_KEY, JSON.stringify(this.config));
    else this.storage.removeItem(CONFIG_KEY);
  };

  CloudSyncService.prototype.isConfigured = function () {
    return Boolean(this.config && this.config.endpoint && this.config.teamCode && this.config.deviceToken && this.config.encryptionKey);
  };

  CloudSyncService.prototype.getSummary = function () {
    if (!this.isConfigured()) return { configured: false, status: '未设置云同步' };
    return {
      configured: true,
      endpoint: this.config.endpoint,
      providerId: normalizeProviderId(this.config.providerId),
      providerName: getProvider(this.config.providerId).name,
      workspaceName: this.config.workspaceName || this.config.teamName,
      teamCode: this.config.teamCode,
      teamName: this.config.teamName,
      deviceId: this.config.deviceId,
      deviceName: this.config.deviceName,
      platform: this.config.platform,
      role: this.config.role,
      lastSyncAt: this.config.lastSyncAt || '',
      lastError: this.config.lastError || '',
      activeDeviceCount: Number(this.config.activeDeviceCount || 0)
    };
  };

  CloudSyncService.prototype.disconnect = function () {
    this.config = null;
    this.saveConfig();
  };

  CloudSyncService.prototype.getProviders = function () {
    return Object.keys(PROVIDERS).map(function (key) { return Object.assign({}, PROVIDERS[key]); });
  };

  CloudSyncService.prototype.testConnection = async function (input) {
    const provider = getProvider(input && input.providerId);
    const endpoint = normalizeEndpoint(input && input.endpoint);
    const health = await this.checkHealth(endpoint);
    return {
      ok: true,
      providerId: provider.id,
      providerName: provider.name,
      endpoint: endpoint,
      service: health.service || '安全检查台账同步服务',
      version: Number(health.version || 0)
    };
  };

  CloudSyncService.prototype.connectProvider = async function (input) {
    input = input || {};
    const provider = getProvider(input.providerId);
    const endpoint = normalizeEndpoint(input.endpoint);
    const workspaceName = String(input.workspaceName || input.teamName || '').trim();
    if (!workspaceName) throw new Error('请填写同步空间名称');
    if (String(input.password || '').length < 8) throw new Error('同步密码请至少填写8位');
    await this.checkHealth(endpoint);
    const teamCode = await makeWorkspaceCode(workspaceName);
    const common = {
      endpoint: endpoint,
      providerId: provider.id,
      workspaceName: workspaceName,
      teamName: workspaceName,
      teamCode: teamCode,
      password: input.password,
      deviceName: input.deviceName,
      platform: input.platform,
      skipHealth: true
    };
    try {
      return await this.joinTeam(common);
    } catch (joinError) {
      if (Number(joinError && joinError.status) !== 403) throw joinError;
      try {
        return await this.createTeam(common);
      } catch (createError) {
        if (Number(createError && createError.status) === 409) {
          throw new Error('该同步空间已经存在，请核对同步空间名称和密码');
        }
        throw createError;
      }
    }
  };

  CloudSyncService.prototype.createTeam = async function (input) {
    const endpoint = normalizeEndpoint(input.endpoint);
    if (!input.skipHealth) await this.checkHealth(endpoint);
    const provider = getProvider(input.providerId);
    const teamCode = /^[A-Z2-9]{8}$/.test(String(input.teamCode || '').toUpperCase()) ? String(input.teamCode).toUpperCase() : generateTeamCode();
    const deviceId = getOrCreateDeviceId(this.storage);
    const saltBytes = randomBytes(16);
    const authVerifier = await makeAuthVerifier(teamCode, input.password);
    const encryptionKey = await deriveEncryptionKey(input.password, saltBytes);
    const platform = input.platform === 'windows' ? 'windows' : 'android';
    const result = await this.publicApi(endpoint, '/api/v1/teams', {
      method: 'POST',
      body: {
        teamCode: teamCode,
        teamName: String(input.teamName || '').trim(),
        authVerifier: authVerifier,
        encryptionSalt: bytesToBase64Url(saltBytes),
        deviceId: deviceId,
        deviceName: String(input.deviceName || '').trim(),
        platform: platform
      }
    });
    this.config = {
      version: 2,
      endpoint: endpoint,
      providerId: provider.id,
      workspaceName: String(input.workspaceName || input.teamName || result.teamName || '').trim(),
      teamCode: result.teamCode,
      teamName: result.teamName,
      deviceId: deviceId,
      deviceName: String(input.deviceName || '').trim() || (platform === 'windows' ? 'Windows电脑' : '安卓手机'),
      platform: platform,
      role: result.role || 'admin',
      deviceToken: result.deviceToken,
      encryptionSalt: result.encryptionSalt,
      encryptionKey: bytesToBase64Url(encryptionKey),
      settingsVersion: 0,
      settingsPending: true,
      settingsHash: '',
      lastSyncAt: '',
      lastError: '',
      activeDeviceCount: 1
    };
    this.saveConfig();
    return this.getSummary();
  };

  CloudSyncService.prototype.joinTeam = async function (input) {
    const endpoint = normalizeEndpoint(input.endpoint);
    if (!input.skipHealth) await this.checkHealth(endpoint);
    const provider = getProvider(input.providerId);
    const teamCode = String(input.teamCode || '').trim().toUpperCase();
    const deviceId = getOrCreateDeviceId(this.storage);
    const authVerifier = await makeAuthVerifier(teamCode, input.password);
    const platform = input.platform === 'windows' ? 'windows' : 'android';
    const result = await this.publicApi(endpoint, '/api/v1/teams/join', {
      method: 'POST',
      body: {
        teamCode: teamCode,
        authVerifier: authVerifier,
        deviceId: deviceId,
        deviceName: String(input.deviceName || '').trim(),
        platform: platform
      }
    });
    const saltBytes = base64UrlToBytes(result.encryptionSalt);
    const encryptionKey = await deriveEncryptionKey(input.password, saltBytes);
    this.config = {
      version: 2,
      endpoint: endpoint,
      providerId: provider.id,
      workspaceName: String(input.workspaceName || result.teamName || '').trim(),
      teamCode: result.teamCode,
      teamName: result.teamName,
      deviceId: deviceId,
      deviceName: String(input.deviceName || '').trim() || (platform === 'windows' ? 'Windows电脑' : '安卓手机'),
      platform: platform,
      role: result.role || 'member',
      deviceToken: result.deviceToken,
      encryptionSalt: result.encryptionSalt,
      encryptionKey: bytesToBase64Url(encryptionKey),
      settingsVersion: 0,
      settingsPending: false,
      settingsHash: '',
      lastSyncAt: '',
      lastError: '',
      activeDeviceCount: 1
    };
    this.saveConfig();
    return this.getSummary();
  };

  CloudSyncService.prototype.checkHealth = async function (endpoint) {
    const response = await this.fetchImpl(endpoint + '/api/health', { method: 'GET', cache: 'no-store' });
    if (!response.ok) throw new Error('云同步地址无法连接（HTTP ' + response.status + '）');
    const result = await response.json();
    if (!result || !result.ok || Number(result.version) < 3) throw new Error('这不是兼容的安全检查台账云端地址');
    return result;
  };

  CloudSyncService.prototype.publicApi = async function (endpoint, path, options) {
    options = options || {};
    const response = await this.fetchImpl(endpoint + path, {
      method: options.method || 'GET',
      headers: options.body ? { 'content-type': 'application/json' } : {},
      body: options.body ? JSON.stringify(options.body) : undefined,
      cache: 'no-store'
    });
    return parseApiResponse(response);
  };

  CloudSyncService.prototype.api = async function (path, options) {
    if (!this.isConfigured()) throw new Error('尚未设置云同步');
    options = options || {};
    const headers = {
      authorization: 'Bearer ' + this.config.deviceToken,
      'x-team-code': this.config.teamCode,
      'x-device-id': this.config.deviceId
    };
    if (options.body) headers['content-type'] = 'application/json';
    Object.keys(options.headers || {}).forEach(function (key) { headers[key] = options.headers[key]; });
    const response = await this.fetchImpl(this.config.endpoint + path, {
      method: options.method || 'GET',
      headers: headers,
      body: options.body ? JSON.stringify(options.body) : undefined,
      cache: 'no-store'
    });
    return parseApiResponse(response);
  };

  CloudSyncService.prototype.markSettingsPending = function () {
    if (!this.config) return;
    this.config.settingsPending = true;
    this.saveConfig();
  };

  CloudSyncService.prototype.getManifest = function () {
    return this.api('/api/v1/manifest');
  };

  CloudSyncService.prototype.sync = async function (input) {
    if (!this.isConfigured()) return { skipped: true };
    if (this.running) return { skipped: true, reason: 'running' };
    this.running = true;
    const callbacks = input.callbacks || {};
    const progress = typeof callbacks.progress === 'function' ? callbacks.progress : function () {};
    const summary = { downloaded: 0, uploaded: 0, trashed: 0, conflicts: 0, settingsChanged: false };
    try {
      progress('正在读取云端目录…', 5);
      const manifest = await this.getManifest();
      this.config.teamName = manifest.teamName || this.config.teamName;
      this.config.role = manifest.role || this.config.role;
      this.config.activeDeviceCount = Number(manifest.activeDeviceCount || 0);
      const remoteSettings = (manifest.settings || []).find(function (item) { return item.key === 'inspection-types'; });
      if (remoteSettings && Number(remoteSettings.version) > Number(this.config.settingsVersion || 0)) {
        progress('正在同步检查项目模板…', 10);
        const remoteTypes = JSON.parse(bytesToUtf8(await this.downloadBlob(remoteSettings.payloadBlobId)));
        if (typeof callbacks.applyInspectionTypes === 'function') {
          const merged = callbacks.applyInspectionTypes(remoteTypes, Boolean(this.config.settingsPending));
          if (merged) input.inspectionTypes = merged;
        }
        this.config.settingsVersion = Number(remoteSettings.version);
        this.config.settingsHash = await sha256Base64(utf8ToBytes(JSON.stringify(remoteTypes)));
        summary.settingsChanged = true;
      }

      const localMap = new Map((input.records || []).map(function (record) { return [record.id, record]; }));
      const remoteIds = new Set();
      const remoteRecords = manifest.records || [];
      for (let index = 0; index < remoteRecords.length; index++) {
        const meta = remoteRecords[index];
        remoteIds.add(meta.id);
        const local = localMap.get(meta.id);
        progress('正在比对云端记录 ' + (index + 1) + '/' + remoteRecords.length + '…', 12 + Math.round((index + 1) / Math.max(1, remoteRecords.length) * 35));
        if (meta.status === 'trash') {
          const trashed = local ? clone(local) : makeCloudPlaceholder(meta);
          applyCloudMeta(trashed, meta);
          trashed.cloudStatus = 'trash';
          trashed.deletedAt = meta.deletedAt || meta.serverChangedAt || '';
          trashed.syncPending = false;
          if (typeof callbacks.upsertRecord === 'function') await callbacks.upsertRecord(trashed);
          localMap.set(meta.id, trashed);
          summary.trashed++;
          continue;
        }
        if (meta.status === 'archived') {
          if (local && local.syncPending && !local.archiveOnly) {
            const edited = clone(local);
            edited.cloudVersion = Math.max(Number(edited.cloudVersion || 0), Number(meta.version || 0));
            edited.cloudTracked = true;
            edited.cloudStatus = 'active';
            edited.syncPending = true;
            edited.archiveBlobId = meta.archiveBlobId || edited.archiveBlobId || '';
            edited.archivePageStart = Number(meta.archivePageStart || edited.archivePageStart || 0);
            edited.archivePageCount = Number(meta.archivePageCount || edited.archivePageCount || 0);
            if (typeof callbacks.upsertRecord === 'function') await callbacks.upsertRecord(edited);
            localMap.set(meta.id, edited);
            continue;
          }
          const archived = local && !local.archiveOnly ? clone(local) : makeCloudPlaceholder(meta);
          applyCloudMeta(archived, meta);
          archived.cloudStatus = 'archived';
          archived.archiveOnly = Boolean(!local || local.archiveOnly);
          archived.syncPending = false;
          if (typeof callbacks.upsertRecord === 'function') await callbacks.upsertRecord(archived);
          localMap.set(meta.id, archived);
          continue;
        }
        const localVersion = Number(local && local.cloudVersion || 0);
        const remoteVersion = Number(meta.version || 0);
        if (!local || remoteVersion > localVersion || local.archiveOnly) {
          if (local && local.syncPending && localVersion > 0) {
            const conflict = clone(local);
            conflict.id = local.id + '-conflict-' + Date.now().toString(36);
            conflict.createdAt = new Date().toISOString();
            conflict.updatedAt = conflict.createdAt;
            conflict.cloudVersion = 0;
            conflict.cloudTracked = false;
            conflict.cloudStatus = 'active';
            conflict.syncPending = true;
            conflict.syncConflict = true;
            if (typeof callbacks.upsertRecord === 'function') await callbacks.upsertRecord(conflict);
            summary.conflicts++;
          }
          const downloaded = await this.downloadRecord(meta);
          if (typeof callbacks.upsertRecord === 'function') await callbacks.upsertRecord(downloaded);
          localMap.set(meta.id, downloaded);
          summary.downloaded++;
        } else if (local) {
          const refreshed = clone(local);
          const pending = Boolean(local.syncPending);
          applyCloudMeta(refreshed, meta);
          if (pending) refreshed.syncPending = true;
          if (typeof callbacks.upsertRecord === 'function') await callbacks.upsertRecord(refreshed);
          localMap.set(meta.id, refreshed);
        }
      }

      for (const record of Array.from(localMap.values())) {
        if (record.cloudTracked && !remoteIds.has(record.id)) {
          if (typeof callbacks.removeRecord === 'function') await callbacks.removeRecord(record.id);
          localMap.delete(record.id);
        }
      }

      const uploadRecords = Array.from(localMap.values()).filter(function (record) {
        return record && !record.archiveOnly && record.cloudStatus !== 'trash' && (record.syncPending || !record.cloudTracked);
      });
      for (let index = 0; index < uploadRecords.length; index++) {
        progress('正在上传本机记录 ' + (index + 1) + '/' + uploadRecords.length + '…', 50 + Math.round((index + 1) / Math.max(1, uploadRecords.length) * 35));
        const uploaded = await this.uploadRecord(uploadRecords[index]);
        if (typeof callbacks.upsertRecord === 'function') await callbacks.upsertRecord(uploaded);
        summary.uploaded++;
      }

      const types = input.inspectionTypes || [];
      const typesBytes = utf8ToBytes(JSON.stringify(types));
      const typesHash = await sha256Base64(typesBytes);
      if (this.config.settingsPending || !remoteSettings || (this.config.settingsHash && typesHash !== this.config.settingsHash)) {
        progress('正在上传检查项目模板…', 90);
        const version = Math.max(Number(this.config.settingsVersion || 0), Number(remoteSettings && remoteSettings.version || 0)) + 1;
        const blob = await this.uploadBytes('settings', 'inspection-types', 'application/json', typesBytes);
        await this.api('/api/v1/settings/inspection-types', { method: 'PUT', body: { version: version, updatedAt: new Date().toISOString(), payloadBlobId: blob.blobId } });
        this.config.settingsVersion = version;
        this.config.settingsHash = typesHash;
        this.config.settingsPending = false;
      }
      await this.api('/api/v1/ack', { method: 'POST', body: {} });
      this.config.lastSyncAt = new Date().toISOString();
      this.config.lastError = '';
      this.saveConfig();
      progress('同步完成', 100);
      return summary;
    } catch (error) {
      this.config.lastError = String(error && error.message || error);
      this.saveConfig();
      throw error;
    } finally {
      this.running = false;
    }
  };

  CloudSyncService.prototype.uploadRecord = async function (record) {
    const cloudRecord = clone(record);
    stripLocalCloudFields(cloudRecord);
    const bytes = utf8ToBytes(JSON.stringify(cloudRecord));
    const blob = await this.uploadBytes('record', record.id, 'application/json', bytes);
    const version = Math.max(0, Number(record.cloudVersion || 0)) + 1;
    const result = await this.api('/api/v1/records/' + encodeURIComponent(record.id), {
      method: 'PUT',
      body: {
        date: record.date,
        typeId: record.inspectionTypeId || 'default',
        typeName: record.inspectionTypeName || '',
        version: version,
        updatedAt: record.updatedAt || new Date().toISOString(),
        payloadBlobId: blob.blobId
      }
    });
    const uploaded = clone(record);
    uploaded.cloudVersion = Number(result.version || version);
    uploaded.cloudTracked = true;
    uploaded.cloudStatus = 'active';
    uploaded.sourceDeviceId = this.config.deviceId;
    uploaded.syncPending = false;
    uploaded.syncConflict = false;
    uploaded.archiveOnly = false;
    uploaded.archiveBlobId = '';
    uploaded.archivePageStart = 0;
    uploaded.archivePageCount = 0;
    return uploaded;
  };

  CloudSyncService.prototype.downloadRecord = async function (meta) {
    if (!meta.payloadBlobId) throw new Error(formatDate(meta.date) + '缺少云端记录数据');
    const bytes = await this.downloadBlob(meta.payloadBlobId);
    const record = JSON.parse(bytesToUtf8(bytes));
    applyCloudMeta(record, meta);
    record.cloudStatus = 'active';
    record.archiveOnly = false;
    record.syncPending = false;
    return record;
  };

  CloudSyncService.prototype.uploadBytes = async function (kind, ownerId, mimeType, bytes) {
    const blobId = kind + ':' + ownerId + ':' + Date.now().toString(36) + ':' + randomId(6);
    const chunks = [];
    const source = bytes instanceof Uint8Array ? bytes : new Uint8Array(bytes);
    const chunkCount = Math.max(1, Math.ceil(source.length / PLAIN_CHUNK_SIZE));
    const hash = await sha256Base64(source);
    await this.api('/api/v1/blobs', {
      method: 'POST',
      body: { blobId: blobId, kind: kind, ownerId: ownerId, mimeType: mimeType, byteLength: source.length, chunkCount: chunkCount, sha256: hash }
    });
    const key = await this.getCryptoKey();
    for (let index = 0; index < chunkCount; index++) {
      const plain = source.slice(index * PLAIN_CHUNK_SIZE, Math.min(source.length, (index + 1) * PLAIN_CHUNK_SIZE));
      const encryptedData = await encryptChunk(key, plain);
      chunks.push(encryptedData.length);
      await this.api('/api/v1/blobs/' + encodeURIComponent(blobId) + '/chunks/' + index, { method: 'PUT', body: { encryptedData: encryptedData } });
    }
    await this.api('/api/v1/blobs/' + encodeURIComponent(blobId) + '/complete', { method: 'POST', body: {} });
    return { blobId: blobId, byteLength: source.length, chunkCount: chunkCount, sha256: hash, encryptedChunkLengths: chunks };
  };

  CloudSyncService.prototype.downloadBlob = async function (blobId) {
    const result = await this.api('/api/v1/blobs/' + encodeURIComponent(blobId));
    const meta = result.blob;
    const key = await this.getCryptoKey();
    const chunks = [];
    let length = 0;
    for (let index = 0; index < Number(meta.chunkCount); index++) {
      const resultChunk = await this.api('/api/v1/blobs/' + encodeURIComponent(blobId) + '/chunks/' + index);
      const plain = await decryptChunk(key, resultChunk.encryptedData);
      chunks.push(plain);
      length += plain.length;
    }
    const bytes = concatBytes(chunks, length);
    if (bytes.length !== Number(meta.byteLength)) throw new Error('云端文件长度校验失败');
    const hash = await sha256Base64(bytes);
    if (meta.sha256 && hash !== meta.sha256) throw new Error('云端文件完整性校验失败');
    return bytes;
  };

  CloudSyncService.prototype.getCryptoKey = async function () {
    if (!this._cryptoKey) {
      this._cryptoKey = await crypto.subtle.importKey('raw', base64UrlToBytes(this.config.encryptionKey), { name: 'AES-GCM' }, false, ['encrypt', 'decrypt']);
    }
    return this._cryptoKey;
  };

  CloudSyncService.prototype.trashRecord = async function (record) {
    const version = Number(record.cloudVersion || 0) + 1;
    const now = new Date().toISOString();
    const result = await this.api('/api/v1/records/' + encodeURIComponent(record.id) + '/trash', { method: 'POST', body: { version: version, updatedAt: now } });
    const value = clone(record);
    value.cloudVersion = Number(result.version || version);
    value.cloudStatus = 'trash';
    value.cloudTracked = true;
    value.deletedAt = now;
    value.syncPending = false;
    return value;
  };

  CloudSyncService.prototype.restoreRecord = async function (record) {
    const version = Number(record.cloudVersion || 0) + 1;
    const now = new Date().toISOString();
    const result = await this.api('/api/v1/records/' + encodeURIComponent(record.id) + '/restore', { method: 'POST', body: { version: version, updatedAt: now } });
    const value = clone(record);
    value.cloudVersion = Number(result.version || version);
    value.cloudStatus = value.archiveBlobId && value.archiveOnly ? 'archived' : 'active';
    value.deletedAt = '';
    value.syncPending = false;
    return value;
  };

  CloudSyncService.prototype.hardDeleteRecord = async function (recordId, password) {
    const verifier = await makeAuthVerifier(this.config.teamCode, password);
    return this.api('/api/v1/records/' + encodeURIComponent(recordId), { method: 'DELETE', headers: { 'x-hard-delete-proof': verifier } });
  };

  CloudSyncService.prototype.archiveRecord = async function (record, pdfBytes, pageCount) {
    const values = await this.archiveRecords([record], pdfBytes, [{ recordId: record.id, pageStart: 0, pageCount: pageCount }], 'single-' + record.id);
    return values[0];
  };

  CloudSyncService.prototype.archiveRecords = async function (records, pdfBytes, ranges, batchId) {
    const ownerId = 'batch-' + String(batchId || Date.now().toString(36)).replace(/[^A-Za-z0-9._:-]/g, '-').slice(0, 140);
    const blob = await this.uploadBytes('archive', ownerId, 'application/pdf', pdfBytes);
    const now = new Date().toISOString();
    const recordMap = new Map((records || []).map(function (record) { return [record.id, record]; }));
    const items = (ranges || []).map(function (range) {
      const record = recordMap.get(range.recordId);
      if (!record) throw new Error('归档页码对应的记录不存在');
      return {
        recordId: record.id,
        version: Number(record.cloudVersion || 0) + 1,
        updatedAt: now,
        pageStart: Math.max(0, Number(range.pageStart) || 0),
        pageCount: Math.max(1, Number(range.pageCount) || 1)
      };
    });
    const result = await this.api('/api/v1/archives/batch', { method: 'POST', body: { archiveBlobId: blob.blobId, items: items } });
    const resultMap = new Map((result.items || items).map(function (item) { return [item.recordId, item]; }));
    return (records || []).map(function (record) {
      const item = resultMap.get(record.id);
      const value = clone(record);
      value.cloudVersion = Number(item && item.version || Number(record.cloudVersion || 0) + 1);
      value.cloudStatus = 'archived';
      value.cloudTracked = true;
      value.archiveBlobId = blob.blobId;
      value.archivePageStart = Number(item && item.pageStart || 0);
      value.archivePageCount = Math.max(1, Number(item && item.pageCount || 1));
      value.archiveOnly = false;
      value.syncPending = false;
      return value;
    });
  };

  CloudSyncService.prototype.releaseLocalRecord = function (record) {
    if (!record || record.cloudStatus !== 'archived' || !record.archiveBlobId) throw new Error('只有已完成云端PDF归档的记录才能释放本机空间');
    const placeholder = makeCloudPlaceholder({
      id: record.id,
      date: record.date,
      typeId: record.inspectionTypeId,
      typeName: record.inspectionTypeName,
      version: record.cloudVersion,
      updatedAt: record.updatedAt,
      status: 'archived',
      archiveBlobId: record.archiveBlobId,
      archivePageStart: record.archivePageStart,
      archivePageCount: record.archivePageCount,
      sourceDeviceId: record.sourceDeviceId
    });
    placeholder.archiveOnly = true;
    return placeholder;
  };

  CloudSyncService.prototype.getDevices = function () { return this.api('/api/v1/devices'); };
  CloudSyncService.prototype.revokeDevice = function (deviceId) { return this.api('/api/v1/devices/' + encodeURIComponent(deviceId), { method: 'DELETE' }); };

  CloudSyncService.prototype.fetchHolidayYear = async function (year) {
    if (!this.config || !this.config.endpoint) throw new Error('设置云同步后才能联网更新节假日');
    const result = await this.publicApi(this.config.endpoint, '/api/v1/holidays/' + Number(year), { method: 'GET' });
    const cache = loadHolidayCache(this.storage);
    cache[String(year)] = { data: result.data, fetchedAt: result.fetchedAt || new Date().toISOString(), source: result.source || '' };
    this.storage.setItem(HOLIDAY_CACHE_KEY, JSON.stringify(cache));
    return cache[String(year)];
  };

  CloudSyncService.prototype.getCachedHolidays = function () { return loadHolidayCache(this.storage); };

  CloudSyncService.prototype.getExportSources = async function (records, progress, cloudFilter) {
    if (!this.isConfigured()) return { sources: (records || []).map(function (record) { return { date: record.date, id: record.id, kind: 'record', record: record }; }), missingDates: [] };
    const manifest = await this.getManifest();
    const byId = new Map((records || []).map(function (record) { return [record.id, record]; }));
    const requestedIds = new Set((records || []).map(function (record) { return record.id; }));
    const sources = [];
    const missing = [];
    const archiveBytes = new Map();
    if (cloudFilter && cloudFilter.includeAllFiltered) {
      (manifest.records || []).forEach(function (meta) {
        if (meta.status !== 'trash' && matchesCloudFilter(meta, cloudFilter)) requestedIds.add(meta.id);
      });
    }
    const metas = (manifest.records || []).filter(function (meta) { return requestedIds.has(meta.id) && meta.status !== 'trash'; });
    for (let index = 0; index < metas.length; index++) {
      const meta = metas[index];
      if (typeof progress === 'function') progress('正在从云端补齐 ' + (index + 1) + '/' + metas.length + ' 条…', Math.round((index + 1) / Math.max(1, metas.length) * 100));
      const local = byId.get(meta.id);
      try {
        if (local && !local.archiveOnly && Number(local.cloudVersion || 0) >= Number(meta.version || 0)) {
          sources.push({ date: meta.date, id: meta.id, kind: 'record', record: local });
        } else if (meta.status === 'archived' && meta.archiveBlobId) {
          if (!archiveBytes.has(meta.archiveBlobId)) archiveBytes.set(meta.archiveBlobId, await this.downloadBlob(meta.archiveBlobId));
          sources.push({ date: meta.date, id: meta.id, kind: 'pdf', bytes: archiveBytes.get(meta.archiveBlobId), pageStart: Number(meta.archivePageStart || 0), pageCount: Number(meta.archivePageCount || 0) });
        } else if (meta.payloadBlobId) {
          sources.push({ date: meta.date, id: meta.id, kind: 'record', record: await this.downloadRecord(meta) });
        } else {
          missing.push(meta.date);
        }
      } catch (_) {
        missing.push(meta.date);
      }
    }
    (records || []).forEach(function (record) {
      if (!metas.some(function (meta) { return meta.id === record.id; }) && !record.cloudTracked && !record.archiveOnly) sources.push({ date: record.date, id: record.id, kind: 'record', record: record });
      else if (!metas.some(function (meta) { return meta.id === record.id; }) && record.cloudTracked) missing.push(record.date);
    });
    sources.sort(function (a, b) { return String(a.date).localeCompare(String(b.date)) || String(a.id).localeCompare(String(b.id)); });
    return { sources: sources, missingDates: Array.from(new Set(missing)).sort() };
  };

  function matchesCloudFilter(meta, filter) {
    if (filter.typeId && filter.typeId !== 'all' && meta.typeId !== filter.typeId) return false;
    const value = String(meta.date || '');
    if (!filter.mode || filter.mode === 'all') return true;
    if (filter.mode === 'day') return value === filter.day;
    if (filter.mode === 'year') return value.slice(0, 4) === String(filter.year);
    if (filter.mode === 'month') return value.slice(0, 7) === String(filter.year) + '-' + String(filter.month).padStart(2, '0');
    if (filter.mode === 'quarter') {
      const month = Number(value.slice(5, 7));
      return value.slice(0, 4) === String(filter.year) && Math.floor((month - 1) / 3) + 1 === Number(filter.quarter);
    }
    return true;
  }

  function stripLocalCloudFields(record) {
    ['cloudVersion', 'cloudTracked', 'cloudStatus', 'sourceDeviceId', 'syncPending', 'syncConflict', 'archiveOnly', 'archiveBlobId', 'archivePageStart', 'archivePageCount', 'deletedAt'].forEach(function (key) { delete record[key]; });
  }

  function applyCloudMeta(record, meta) {
    record.cloudVersion = Number(meta.version || 0);
    record.cloudTracked = true;
    record.cloudStatus = meta.status || 'active';
    record.sourceDeviceId = meta.sourceDeviceId || '';
    record.archiveBlobId = meta.archiveBlobId || '';
    record.archivePageStart = Number(meta.archivePageStart || 0);
    record.archivePageCount = Number(meta.archivePageCount || 0);
    record.deletedAt = meta.deletedAt || '';
    record.syncPending = false;
  }

  function makeCloudPlaceholder(meta) {
    return {
      id: meta.id,
      inspectionTypeId: meta.typeId || 'cloud-archive',
      inspectionTypeName: meta.typeName || '云端检查记录',
      date: meta.date,
      location: '云端PDF归档',
      items: [],
      inspectionPhotos: [],
      signatures: { inspector1: '', inspector2: '', inspected: '' },
      rectification: { opinion: '', photos: [], completed: true, completedAt: '' },
      createdAt: meta.updatedAt || '',
      updatedAt: meta.updatedAt || '',
      cloudVersion: Number(meta.version || 0),
      cloudTracked: true,
      cloudStatus: meta.status || 'archived',
      sourceDeviceId: meta.sourceDeviceId || '',
      archiveBlobId: meta.archiveBlobId || '',
      archivePageStart: Number(meta.archivePageStart || 0),
      archivePageCount: Number(meta.archivePageCount || 0),
      archiveOnly: true,
      syncPending: false,
      deletedAt: meta.deletedAt || ''
    };
  }

  async function encryptChunk(key, bytes) {
    const iv = randomBytes(12);
    const cipher = new Uint8Array(await crypto.subtle.encrypt({ name: 'AES-GCM', iv: iv }, key, bytes));
    return bytesToBase64Url(concatBytes([iv, cipher], iv.length + cipher.length));
  }

  async function decryptChunk(key, encoded) {
    const combined = base64UrlToBytes(encoded);
    if (combined.length < 29) throw new Error('加密分片损坏');
    const iv = combined.slice(0, 12);
    const cipher = combined.slice(12);
    return new Uint8Array(await crypto.subtle.decrypt({ name: 'AES-GCM', iv: iv }, key, cipher));
  }

  async function deriveEncryptionKey(password, saltBytes) {
    if (!String(password || '').trim()) throw new Error('请设置同步密码');
    const material = await crypto.subtle.importKey('raw', utf8ToBytes(String(password)), 'PBKDF2', false, ['deriveBits']);
    return new Uint8Array(await crypto.subtle.deriveBits({ name: 'PBKDF2', hash: 'SHA-256', salt: saltBytes, iterations: 180000 }, material, 256));
  }

  async function makeAuthVerifier(teamCode, password) {
    return sha256Base64(utf8ToBytes('safety-ledger-auth-v1|' + String(teamCode).toUpperCase() + '|' + String(password || '')));
  }

  async function sha256Base64(value) {
    const bytes = typeof value === 'string' ? utf8ToBytes(value) : value;
    return bytesToBase64Url(new Uint8Array(await crypto.subtle.digest('SHA-256', bytes)));
  }

  function normalizeProviderId(value) {
    const id = String(value || 'cloudflare');
    return PROVIDERS[id] ? id : 'compatible';
  }

  function getProvider(value) {
    return PROVIDERS[normalizeProviderId(value)];
  }

  function inferProviderId(endpoint) {
    const value = String(endpoint || '').toLowerCase();
    if (value.indexOf('workers.dev') >= 0) return 'cloudflare';
    return 'compatible';
  }

  async function makeWorkspaceCode(workspaceName) {
    const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', utf8ToBytes('safety-ledger-workspace-v1|' + String(workspaceName || '').trim().toLowerCase())));
    let result = '';
    for (let index = 0; index < 8; index++) result += TEAM_ALPHABET[digest[index] % TEAM_ALPHABET.length];
    return result;
  }

  function normalizeEndpoint(value) {
    const endpoint = String(value || '').trim().replace(/\/+$/, '');
    if (!endpoint) throw new Error('请填写云同步地址');
    let parsed;
    try { parsed = new URL(endpoint); } catch (_) { throw new Error('云同步地址格式不正确'); }
    if (parsed.protocol === 'https:') return endpoint;
    const host = String(parsed.hostname || '').toLowerCase();
    const privateIpv4 = /^(10\.|127\.|192\.168\.|172\.(1[6-9]|2\d|3[01])\.)/.test(host);
    const localHost = host === 'localhost' || host.endsWith('.local');
    if (parsed.protocol !== 'http:' || (!privateIpv4 && !localHost)) throw new Error('公网同步地址必须使用 https://；局域网 NAS 私有地址可以使用 http://');
    return endpoint;
  }

  function generateTeamCode() {
    const bytes = randomBytes(8);
    let result = '';
    for (let index = 0; index < 8; index++) result += TEAM_ALPHABET[bytes[index] % TEAM_ALPHABET.length];
    return result;
  }

  function getOrCreateDeviceId(storage) {
    let value = storage.getItem(DEVICE_ID_KEY);
    if (!value) {
      value = 'device-' + Date.now().toString(36) + '-' + randomId(12);
      storage.setItem(DEVICE_ID_KEY, value);
    }
    return value;
  }

  function randomId(length) {
    const bytes = randomBytes(length);
    let result = '';
    for (let index = 0; index < bytes.length; index++) result += TEAM_ALPHABET[bytes[index] % TEAM_ALPHABET.length].toLowerCase();
    return result;
  }

  function randomBytes(length) { return crypto.getRandomValues(new Uint8Array(length)); }
  function utf8ToBytes(text) { return new TextEncoder().encode(String(text)); }
  function bytesToUtf8(bytes) { return new TextDecoder().decode(bytes); }
  function concatBytes(chunks, totalLength) {
    const result = new Uint8Array(totalLength == null ? chunks.reduce(function (sum, chunk) { return sum + chunk.length; }, 0) : totalLength);
    let offset = 0;
    chunks.forEach(function (chunk) { result.set(chunk, offset); offset += chunk.length; });
    return result;
  }
  function bytesToBase64Url(bytes) {
    let binary = '';
    for (let offset = 0; offset < bytes.length; offset += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(offset, Math.min(offset + 0x8000, bytes.length)));
    return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
  }
  function base64UrlToBytes(value) {
    let base64 = String(value || '').replace(/-/g, '+').replace(/_/g, '/');
    while (base64.length % 4) base64 += '=';
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let index = 0; index < binary.length; index++) bytes[index] = binary.charCodeAt(index);
    return bytes;
  }
  function clone(value) { return JSON.parse(JSON.stringify(value)); }
  function formatDate(value) { return String(value || '未知日期'); }
  function loadHolidayCache(storage) {
    try {
      const value = JSON.parse(storage.getItem(HOLIDAY_CACHE_KEY) || '{}');
      return value && typeof value === 'object' ? value : {};
    } catch (_) { return {}; }
  }
  async function parseApiResponse(response) {
    let result;
    try { result = await response.json(); } catch (_) { result = null; }
    if (!response.ok || !result || result.ok === false) {
      const error = new Error(result && result.error ? result.error : '云同步请求失败（HTTP ' + response.status + '）');
      error.status = response.status;
      if (result && result.currentVersion != null) error.currentVersion = Number(result.currentVersion);
      throw error;
    }
    return result;
  }

  window.SafetyLedgerCloud = {
    create: function (options) { return new CloudSyncService(options); },
    CloudSyncService: CloudSyncService,
    helpers: {
      normalizeEndpoint: normalizeEndpoint,
      normalizeProviderId: normalizeProviderId,
      makeWorkspaceCode: makeWorkspaceCode,
      makeAuthVerifier: makeAuthVerifier,
      deriveEncryptionKey: deriveEncryptionKey,
      encryptChunk: encryptChunk,
      decryptChunk: decryptChunk,
      bytesToBase64Url: bytesToBase64Url,
      base64UrlToBytes: base64UrlToBytes,
      utf8ToBytes: utf8ToBytes,
      bytesToUtf8: bytesToUtf8,
      makeCloudPlaceholder: makeCloudPlaceholder,
      applyCloudMeta: applyCloudMeta
    }
  };
})();
