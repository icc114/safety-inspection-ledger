const JSON_HEADERS = { 'content-type': 'application/json; charset=utf-8' };
const CORS_HEADERS = {
  'access-control-allow-origin': '*',
  'access-control-allow-methods': 'GET,POST,PUT,DELETE,OPTIONS',
  'access-control-allow-headers': 'authorization,content-type,x-team-code,x-device-id,x-hard-delete-proof',
  'access-control-max-age': '86400'
};
const MAX_CHUNK_TEXT = 760000;
const MAX_BLOB_BYTES = 32 * 1024 * 1024;
const TEAM_CODE_RE = /^[A-Z2-9]{8}$/;
const ID_RE = /^[A-Za-z0-9._:-]{1,160}$/;

export default {
  async fetch(request, env) {
    try {
      if (request.method === 'OPTIONS') return new Response(null, { status: 204, headers: CORS_HEADERS });
      const url = new URL(request.url);
      if (url.pathname === '/api/health') {
        return json({ ok: true, service: '安全检查台账免费同步', version: 3, serverTime: new Date().toISOString() });
      }
      if (url.pathname.match(/^\/api\/v1\/holidays\/\d{4}$/) && request.method === 'GET') {
        return getHolidays(Number(url.pathname.split('/').pop()), env);
      }
      if (url.pathname === '/api/v1/teams' && request.method === 'POST') return createTeam(request, env);
      if (url.pathname === '/api/v1/teams/join' && request.method === 'POST') return joinTeam(request, env);

      const auth = await authenticate(request, env);
      if (auth.response) return auth.response;
      const context = auth.context;
      await env.DB.prepare('UPDATE devices SET last_seen = ? WHERE team_code = ? AND device_id = ?')
        .bind(new Date().toISOString(), context.teamCode, context.deviceId).run();

      if (url.pathname === '/api/v1/manifest' && request.method === 'GET') return getManifest(context, env);
      if (url.pathname === '/api/v1/devices' && request.method === 'GET') return listDevices(context, env);
      if (url.pathname.match(/^\/api\/v1\/devices\/[^/]+$/) && request.method === 'DELETE') {
        return revokeDevice(decodeURIComponent(url.pathname.split('/').pop()), context, env);
      }
      if (url.pathname === '/api/v1/blobs' && request.method === 'POST') return startBlob(request, context, env);
      if (url.pathname.match(/^\/api\/v1\/blobs\/[^/]+\/chunks\/\d+$/) && request.method === 'PUT') {
        const parts = url.pathname.split('/');
        return putBlobChunk(decodeURIComponent(parts[4]), Number(parts[6]), request, context, env);
      }
      if (url.pathname.match(/^\/api\/v1\/blobs\/[^/]+\/complete$/) && request.method === 'POST') {
        return completeBlob(decodeURIComponent(url.pathname.split('/')[4]), context, env);
      }
      if (url.pathname.match(/^\/api\/v1\/blobs\/[^/]+$/) && request.method === 'GET') {
        return getBlobMeta(decodeURIComponent(url.pathname.split('/').pop()), context, env);
      }
      if (url.pathname.match(/^\/api\/v1\/blobs\/[^/]+\/chunks\/\d+$/) && request.method === 'GET') {
        const parts = url.pathname.split('/');
        return getBlobChunk(decodeURIComponent(parts[4]), Number(parts[6]), context, env);
      }
      if (url.pathname.match(/^\/api\/v1\/records\/[^/]+$/) && request.method === 'PUT') {
        return putRecord(decodeURIComponent(url.pathname.split('/').pop()), request, context, env);
      }
      if (url.pathname === '/api/v1/archives/batch' && request.method === 'POST') {
        return archiveRecordBatch(request, context, env);
      }
      if (url.pathname.match(/^\/api\/v1\/records\/[^/]+\/(trash|restore|archive)$/) && request.method === 'POST') {
        const parts = url.pathname.split('/');
        return changeRecordState(decodeURIComponent(parts[4]), parts[5], request, context, env);
      }
      if (url.pathname.match(/^\/api\/v1\/records\/[^/]+$/) && request.method === 'DELETE') {
        return hardDeleteRecord(decodeURIComponent(url.pathname.split('/').pop()), request, context, env);
      }
      if (url.pathname.match(/^\/api\/v1\/settings\/[^/]+$/) && request.method === 'PUT') {
        return putSetting(decodeURIComponent(url.pathname.split('/').pop()), request, context, env);
      }
      if (url.pathname === '/api/v1/ack' && request.method === 'POST') return acknowledge(context, env);
      return json({ ok: false, error: '接口不存在' }, 404);
    } catch (error) {
      console.error(error);
      return json({ ok: false, error: '服务器处理失败', detail: String(error && error.message || error) }, 500);
    }
  }
};

async function createTeam(request, env) {
  const body = await readJson(request);
  const teamCode = String(body.teamCode || '').toUpperCase();
  const deviceId = String(body.deviceId || '');
  if (!TEAM_CODE_RE.test(teamCode)) return json({ ok: false, error: '同步空间标识无效' }, 400);
  if (!validId(deviceId) || !body.authVerifier || !body.encryptionSalt) return json({ ok: false, error: '创建参数不完整' }, 400);
  const existing = await env.DB.prepare('SELECT team_code FROM teams WHERE team_code = ?').bind(teamCode).first();
  if (existing) return json({ ok: false, error: '同步空间已存在' }, 409);
  const now = new Date().toISOString();
  const token = randomToken();
  const tokenHash = await sha256Base64(token);
  const verifierHash = await sha256Base64(String(body.authVerifier));
  await env.DB.batch([
    env.DB.prepare('INSERT INTO teams(team_code,name,auth_verifier,encryption_salt,created_at) VALUES(?,?,?,?,?)')
      .bind(teamCode, cleanText(body.teamName, 80) || '安全检查单位', verifierHash, String(body.encryptionSalt), now),
    env.DB.prepare('INSERT INTO devices(team_code,device_id,token_hash,name,platform,role,active,created_at,last_seen) VALUES(?,?,?,?,?,?,?,?,?)')
      .bind(teamCode, deviceId, tokenHash, cleanText(body.deviceName, 80) || '首台设备', normalizePlatform(body.platform), 'admin', 1, now, now)
  ]);
  return json({ ok: true, teamCode, teamName: cleanText(body.teamName, 80) || '安全检查单位', encryptionSalt: String(body.encryptionSalt), deviceToken: token, role: 'admin' }, 201);
}

async function joinTeam(request, env) {
  const body = await readJson(request);
  const teamCode = String(body.teamCode || '').toUpperCase();
  const deviceId = String(body.deviceId || '');
  if (!TEAM_CODE_RE.test(teamCode) || !validId(deviceId)) return json({ ok: false, error: '同步空间或设备编号无效' }, 400);
  const team = await env.DB.prepare('SELECT * FROM teams WHERE team_code = ?').bind(teamCode).first();
  const verifierHash = await sha256Base64(String(body.authVerifier || ''));
  if (team && team.locked_until && Date.parse(team.locked_until) > Date.now()) return json({ ok: false, error: '密码错误次数过多，请15分钟后再试' }, 429);
  if (!team || !safeEqual(String(team.auth_verifier), verifierHash)) {
    if (team) {
      const attempts = Number(team.failed_attempts || 0) + 1;
      const lockedUntil = attempts >= 8 ? new Date(Date.now() + 15 * 60 * 1000).toISOString() : null;
      await env.DB.prepare('UPDATE teams SET failed_attempts=?,locked_until=? WHERE team_code=?').bind(attempts >= 8 ? 0 : attempts, lockedUntil, teamCode).run();
    }
    return json({ ok: false, error: '同步空间名称或密码不正确' }, 403);
  }
  await env.DB.prepare('UPDATE teams SET failed_attempts=0,locked_until=NULL WHERE team_code=?').bind(teamCode).run();
  const now = new Date().toISOString();
  const token = randomToken();
  const tokenHash = await sha256Base64(token);
  const existing = await env.DB.prepare('SELECT role FROM devices WHERE team_code = ? AND device_id = ?').bind(teamCode, deviceId).first();
  const role = existing && existing.role === 'admin' ? 'admin' : 'member';
  await env.DB.prepare(`INSERT INTO devices(team_code,device_id,token_hash,name,platform,role,active,created_at,last_seen)
    VALUES(?,?,?,?,?,?,?,?,?)
    ON CONFLICT(team_code,device_id) DO UPDATE SET token_hash=excluded.token_hash,name=excluded.name,platform=excluded.platform,active=1,last_seen=excluded.last_seen`)
    .bind(teamCode, deviceId, tokenHash, cleanText(body.deviceName, 80) || '同步设备', normalizePlatform(body.platform), role, 1, now, now).run();
  return json({ ok: true, teamCode, teamName: team.name, encryptionSalt: team.encryption_salt, deviceToken: token, role });
}

async function authenticate(request, env) {
  const teamCode = String(request.headers.get('x-team-code') || '').toUpperCase();
  const deviceId = String(request.headers.get('x-device-id') || '');
  const authorization = String(request.headers.get('authorization') || '');
  const token = authorization.startsWith('Bearer ') ? authorization.slice(7) : '';
  if (!TEAM_CODE_RE.test(teamCode) || !validId(deviceId) || !token) return { response: json({ ok: false, error: '需要设备授权' }, 401) };
  const tokenHash = await sha256Base64(token);
  const device = await env.DB.prepare('SELECT role,active,name,platform FROM devices WHERE team_code = ? AND device_id = ? AND token_hash = ?')
    .bind(teamCode, deviceId, tokenHash).first();
  if (!device || !device.active) return { response: json({ ok: false, error: '设备未授权或已被移除' }, 401) };
  return { context: { teamCode, deviceId, role: device.role, platform: device.platform, deviceName: device.name } };
}

async function getManifest(context, env) {
  const recordsResult = await env.DB.prepare(`SELECT record_id AS id,record_date AS date,type_id AS typeId,type_name AS typeName,version,updated_at AS updatedAt,status,payload_blob_id AS payloadBlobId,archive_blob_id AS archiveBlobId,archive_page_start AS archivePageStart,archive_page_count AS archivePageCount,source_device_id AS sourceDeviceId,deleted_at AS deletedAt,server_changed_at AS serverChangedAt FROM records WHERE team_code = ? ORDER BY record_date ASC, record_id ASC`).bind(context.teamCode).all();
  const settingsResult = await env.DB.prepare(`SELECT setting_key AS key,version,updated_at AS updatedAt,payload_blob_id AS payloadBlobId,source_device_id AS sourceDeviceId,server_changed_at AS serverChangedAt FROM shared_settings WHERE team_code = ?`).bind(context.teamCode).all();
  const team = await env.DB.prepare('SELECT name FROM teams WHERE team_code = ?').bind(context.teamCode).first();
  const deviceCountRow = await env.DB.prepare('SELECT COUNT(*) AS count FROM devices WHERE team_code = ? AND active = 1').bind(context.teamCode).first();
  return json({ ok: true, teamCode: context.teamCode, teamName: team ? team.name : '', role: context.role, records: recordsResult.results || [], settings: settingsResult.results || [], activeDeviceCount: Number(deviceCountRow && deviceCountRow.count || 0), serverTime: new Date().toISOString() });
}

async function listDevices(context, env) {
  const result = await env.DB.prepare(`SELECT device_id AS deviceId,name,platform,role,active,created_at AS createdAt,last_seen AS lastSeen,last_ack_at AS lastAckAt FROM devices WHERE team_code = ? ORDER BY active DESC, created_at ASC`).bind(context.teamCode).all();
  return json({ ok: true, currentDeviceId: context.deviceId, devices: result.results || [] });
}

async function revokeDevice(targetId, context, env) {
  if (context.role !== 'admin') return json({ ok: false, error: '只有管理员可以移除设备' }, 403);
  if (targetId === context.deviceId) return json({ ok: false, error: '不能移除当前正在使用的管理员设备' }, 400);
  await env.DB.prepare('UPDATE devices SET active = 0 WHERE team_code = ? AND device_id = ?').bind(context.teamCode, targetId).run();
  return json({ ok: true });
}

async function startBlob(request, context, env) {
  const body = await readJson(request);
  if (!validId(body.blobId) || !validId(body.ownerId)) return json({ ok: false, error: '云端文件编号无效' }, 400);
  if (!['record', 'archive', 'settings'].includes(body.kind)) return json({ ok: false, error: '云端文件类型无效' }, 400);
  const byteLength = Number(body.byteLength);
  const chunkCount = Number(body.chunkCount);
  if (!Number.isInteger(byteLength) || byteLength < 0 || byteLength > MAX_BLOB_BYTES || !Number.isInteger(chunkCount) || chunkCount < 1 || chunkCount > 160) return json({ ok: false, error: '云端文件过大或分片数无效' }, 413);
  const existingBlob = await env.DB.prepare('SELECT blob_id FROM cloud_blobs WHERE team_code=? AND blob_id=?').bind(context.teamCode, String(body.blobId)).first();
  if (existingBlob) return json({ ok: false, error: '云端文件编号已存在，请重新生成' }, 409);
  await env.DB.prepare('INSERT INTO cloud_blobs(team_code,blob_id,kind,owner_id,mime_type,byte_length,chunk_count,sha256,complete,created_at) VALUES(?,?,?,?,?,?,?,?,0,?)')
    .bind(context.teamCode, String(body.blobId), body.kind, String(body.ownerId), cleanText(body.mimeType, 100) || 'application/octet-stream', byteLength, chunkCount, cleanText(body.sha256, 120), new Date().toISOString()).run();
  return json({ ok: true });
}

async function putBlobChunk(blobId, chunkIndex, request, context, env) {
  if (!validId(blobId) || !Number.isInteger(chunkIndex) || chunkIndex < 0) return json({ ok: false, error: '分片编号无效' }, 400);
  const body = await readJson(request);
  const encryptedData = String(body.encryptedData || '');
  if (!encryptedData || encryptedData.length > MAX_CHUNK_TEXT) return json({ ok: false, error: '分片为空或过大' }, 413);
  const blob = await env.DB.prepare('SELECT chunk_count,complete FROM cloud_blobs WHERE team_code = ? AND blob_id = ?').bind(context.teamCode, blobId).first();
  if (!blob || blob.complete) return json({ ok: false, error: '云端文件不存在或已完成' }, 404);
  if (chunkIndex >= Number(blob.chunk_count)) return json({ ok: false, error: '分片序号超出范围' }, 400);
  await env.DB.prepare(`INSERT INTO cloud_blob_chunks(team_code,blob_id,chunk_index,encrypted_data) VALUES(?,?,?,?) ON CONFLICT(team_code,blob_id,chunk_index) DO UPDATE SET encrypted_data=excluded.encrypted_data`)
    .bind(context.teamCode, blobId, chunkIndex, encryptedData).run();
  return json({ ok: true, chunkIndex });
}

async function completeBlob(blobId, context, env) {
  const blob = await env.DB.prepare('SELECT chunk_count FROM cloud_blobs WHERE team_code = ? AND blob_id = ?').bind(context.teamCode, blobId).first();
  if (!blob) return json({ ok: false, error: '云端文件不存在' }, 404);
  const countRow = await env.DB.prepare('SELECT COUNT(*) AS count FROM cloud_blob_chunks WHERE team_code = ? AND blob_id = ?').bind(context.teamCode, blobId).first();
  if (Number(countRow && countRow.count || 0) !== Number(blob.chunk_count)) return json({ ok: false, error: '云端文件分片不完整' }, 409);
  await env.DB.prepare('UPDATE cloud_blobs SET complete = 1 WHERE team_code = ? AND blob_id = ?').bind(context.teamCode, blobId).run();
  return json({ ok: true });
}

async function getBlobMeta(blobId, context, env) {
  const blob = await env.DB.prepare(`SELECT blob_id AS blobId,kind,owner_id AS ownerId,mime_type AS mimeType,byte_length AS byteLength,chunk_count AS chunkCount,sha256,complete,created_at AS createdAt FROM cloud_blobs WHERE team_code = ? AND blob_id = ? AND complete = 1`).bind(context.teamCode, blobId).first();
  return blob ? json({ ok: true, blob }) : json({ ok: false, error: '云端文件不存在或不完整' }, 404);
}

async function getBlobChunk(blobId, chunkIndex, context, env) {
  const chunk = await env.DB.prepare(`SELECT c.encrypted_data AS encryptedData FROM cloud_blob_chunks c JOIN cloud_blobs b ON b.team_code=c.team_code AND b.blob_id=c.blob_id WHERE c.team_code=? AND c.blob_id=? AND c.chunk_index=? AND b.complete=1`).bind(context.teamCode, blobId, chunkIndex).first();
  return chunk ? json({ ok: true, chunkIndex, encryptedData: chunk.encryptedData }) : json({ ok: false, error: '云端分片不存在' }, 404);
}

async function putRecord(recordId, request, context, env) {
  if (!validId(recordId)) return json({ ok: false, error: '记录编号无效' }, 400);
  const body = await readJson(request);
  const version = Number(body.version);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(String(body.date || '')) || !Number.isInteger(version) || version < 1 || !validId(body.payloadBlobId)) return json({ ok: false, error: '记录元数据不完整' }, 400);
  const blob = await env.DB.prepare('SELECT complete,kind,owner_id FROM cloud_blobs WHERE team_code = ? AND blob_id = ?').bind(context.teamCode, String(body.payloadBlobId)).first();
  if (!blob || !blob.complete || blob.kind !== 'record' || blob.owner_id !== recordId) return json({ ok: false, error: '记录数据未完整上传' }, 409);
  const existing = await env.DB.prepare('SELECT version,payload_blob_id AS payloadBlobId,archive_blob_id AS archiveBlobId FROM records WHERE team_code = ? AND record_id = ?').bind(context.teamCode, recordId).first();
  if (existing && version <= Number(existing.version)) return json({ ok: false, error: '云端已有更新版本', currentVersion: Number(existing.version) }, 409);
  const now = new Date().toISOString();
  await env.DB.prepare(`INSERT INTO records(team_code,record_id,record_date,type_id,type_name,version,updated_at,status,payload_blob_id,archive_blob_id,archive_page_start,archive_page_count,source_device_id,deleted_at,server_changed_at)
    VALUES(?,?,?,?,?,?,?,?,?,NULL,0,0,?,NULL,?)
    ON CONFLICT(team_code,record_id) DO UPDATE SET record_date=excluded.record_date,type_id=excluded.type_id,type_name=excluded.type_name,version=excluded.version,updated_at=excluded.updated_at,status='active',payload_blob_id=excluded.payload_blob_id,archive_blob_id=NULL,archive_page_start=0,archive_page_count=0,source_device_id=excluded.source_device_id,deleted_at=NULL,server_changed_at=excluded.server_changed_at`)
    .bind(context.teamCode, recordId, String(body.date), cleanText(body.typeId, 120), cleanText(body.typeName, 120), version, cleanText(body.updatedAt, 60) || now, 'active', String(body.payloadBlobId), context.deviceId, now).run();
  if (existing && existing.payloadBlobId && existing.payloadBlobId !== body.payloadBlobId) await deleteBlob(context.teamCode, existing.payloadBlobId, env);
  if (existing && existing.archiveBlobId) await deleteBlobIfUnreferenced(context.teamCode, existing.archiveBlobId, env);
  return json({ ok: true, version, serverChangedAt: now });
}

async function archiveRecordBatch(request, context, env) {
  const body = await readJson(request);
  const archiveBlobId = String(body.archiveBlobId || '');
  const items = Array.isArray(body.items) ? body.items : [];
  if (!validId(archiveBlobId) || !items.length || items.length > 20) return json({ ok: false, error: 'PDF批量归档参数无效' }, 400);
  const archive = await env.DB.prepare('SELECT complete,kind,owner_id FROM cloud_blobs WHERE team_code=? AND blob_id=?').bind(context.teamCode, archiveBlobId).first();
  if (!archive || !archive.complete || archive.kind !== 'archive') return json({ ok: false, error: '批量PDF归档尚未完整上传' }, 409);
  const now = new Date().toISOString();
  const statements = [];
  const oldPayloads = [];
  const oldArchives = [];
  const resultItems = [];
  const seen = new Set();
  for (const rawItem of items) {
    const recordId = String(rawItem && rawItem.recordId || '');
    const version = Number(rawItem && rawItem.version);
    const pageStart = Number(rawItem && rawItem.pageStart);
    const pageCount = Number(rawItem && rawItem.pageCount);
    if (!validId(recordId) || seen.has(recordId) || !Number.isInteger(version) || version < 1 || !Number.isInteger(pageStart) || pageStart < 0 || !Number.isInteger(pageCount) || pageCount < 1) return json({ ok: false, error: '归档记录页码或版本无效' }, 400);
    seen.add(recordId);
    const existing = await env.DB.prepare('SELECT version,payload_blob_id AS payloadBlobId,archive_blob_id AS archiveBlobId FROM records WHERE team_code=? AND record_id=?').bind(context.teamCode, recordId).first();
    if (!existing) return json({ ok: false, error: '归档记录不存在：' + recordId }, 404);
    if (version <= Number(existing.version)) return json({ ok: false, error: '云端已有更新版本', recordId, currentVersion: Number(existing.version) }, 409);
    if (existing.payloadBlobId) oldPayloads.push(existing.payloadBlobId);
    if (existing.archiveBlobId && existing.archiveBlobId !== archiveBlobId) oldArchives.push(existing.archiveBlobId);
    statements.push(env.DB.prepare(`UPDATE records SET status='archived',version=?,updated_at=?,archive_blob_id=?,archive_page_start=?,archive_page_count=?,payload_blob_id=NULL,source_device_id=?,deleted_at=NULL,server_changed_at=? WHERE team_code=? AND record_id=?`)
      .bind(version, cleanText(rawItem.updatedAt, 60) || now, archiveBlobId, pageStart, pageCount, context.deviceId, now, context.teamCode, recordId));
    resultItems.push({ recordId, version, pageStart, pageCount });
  }
  await env.DB.batch(statements);
  for (const payloadBlobId of oldPayloads) await deleteBlob(context.teamCode, payloadBlobId, env);
  for (const oldArchiveBlobId of new Set(oldArchives)) await deleteBlobIfUnreferenced(context.teamCode, oldArchiveBlobId, env);
  return json({ ok: true, archiveBlobId, items: resultItems, serverChangedAt: now });
}

async function changeRecordState(recordId, action, request, context, env) {
  if (!validId(recordId)) return json({ ok: false, error: '记录编号无效' }, 400);
  const body = await readJson(request);
  const existing = await env.DB.prepare('SELECT * FROM records WHERE team_code = ? AND record_id = ?').bind(context.teamCode, recordId).first();
  if (!existing) return json({ ok: false, error: '记录不存在' }, 404);
  const version = Number(body.version);
  if (!Number.isInteger(version) || version <= Number(existing.version)) return json({ ok: false, error: '云端已有更新版本', currentVersion: Number(existing.version) }, 409);
  const now = new Date().toISOString();
  if (action === 'trash') {
    await env.DB.prepare(`UPDATE records SET status='trash',version=?,updated_at=?,source_device_id=?,deleted_at=?,server_changed_at=? WHERE team_code=? AND record_id=?`)
      .bind(version, cleanText(body.updatedAt, 60) || now, context.deviceId, now, now, context.teamCode, recordId).run();
  } else if (action === 'restore') {
    const status = existing.archive_blob_id && !existing.payload_blob_id ? 'archived' : 'active';
    await env.DB.prepare(`UPDATE records SET status=?,version=?,updated_at=?,source_device_id=?,deleted_at=NULL,server_changed_at=? WHERE team_code=? AND record_id=?`)
      .bind(status, version, cleanText(body.updatedAt, 60) || now, context.deviceId, now, context.teamCode, recordId).run();
  } else {
    const archiveBlobId = String(body.archiveBlobId || '');
    const archive = await env.DB.prepare('SELECT complete,kind,owner_id FROM cloud_blobs WHERE team_code=? AND blob_id=?').bind(context.teamCode, archiveBlobId).first();
    if (!archive || !archive.complete || archive.kind !== 'archive' || archive.owner_id !== recordId) return json({ ok: false, error: 'PDF归档尚未完整上传' }, 409);
    const oldPayload = existing.payload_blob_id;
    await env.DB.prepare(`UPDATE records SET status='archived',version=?,updated_at=?,archive_blob_id=?,archive_page_start=?,archive_page_count=?,payload_blob_id=NULL,source_device_id=?,deleted_at=NULL,server_changed_at=? WHERE team_code=? AND record_id=?`)
      .bind(version, cleanText(body.updatedAt, 60) || now, archiveBlobId, Math.max(0, Number(body.pageStart) || 0), Math.max(1, Number(body.pageCount) || 1), context.deviceId, now, context.teamCode, recordId).run();
    if (oldPayload) await deleteBlob(context.teamCode, oldPayload, env);
    if (existing.archive_blob_id && existing.archive_blob_id !== archiveBlobId) await deleteBlobIfUnreferenced(context.teamCode, existing.archive_blob_id, env);
  }
  return json({ ok: true, version, serverChangedAt: now });
}

async function hardDeleteRecord(recordId, request, context, env) {
  if (context.role !== 'admin') return json({ ok: false, error: '只有管理员可以彻底删除所有设备上的记录' }, 403);
  const proof = String(request.headers.get('x-hard-delete-proof') || '');
  const team = await env.DB.prepare('SELECT auth_verifier FROM teams WHERE team_code = ?').bind(context.teamCode).first();
  const proofHash = await sha256Base64(proof);
  if (!team || !safeEqual(String(team.auth_verifier), proofHash)) return json({ ok: false, error: '同步密码校验失败' }, 403);
  const record = await env.DB.prepare('SELECT payload_blob_id,archive_blob_id FROM records WHERE team_code=? AND record_id=?').bind(context.teamCode, recordId).first();
  if (!record) return json({ ok: true });
  await env.DB.prepare('DELETE FROM records WHERE team_code=? AND record_id=?').bind(context.teamCode, recordId).run();
  if (record.payload_blob_id) await deleteBlobIfUnreferenced(context.teamCode, record.payload_blob_id, env);
  if (record.archive_blob_id) await deleteBlobIfUnreferenced(context.teamCode, record.archive_blob_id, env);
  return json({ ok: true });
}

async function putSetting(settingKey, request, context, env) {
  if (!validId(settingKey)) return json({ ok: false, error: '设置编号无效' }, 400);
  const body = await readJson(request);
  const version = Number(body.version);
  const blobId = String(body.payloadBlobId || '');
  if (!Number.isInteger(version) || version < 1 || !validId(blobId)) return json({ ok: false, error: '设置数据不完整' }, 400);
  const blob = await env.DB.prepare('SELECT complete,kind,owner_id FROM cloud_blobs WHERE team_code=? AND blob_id=?').bind(context.teamCode, blobId).first();
  if (!blob || !blob.complete || blob.kind !== 'settings' || blob.owner_id !== settingKey) return json({ ok: false, error: '设置文件尚未完整上传' }, 409);
  const existing = await env.DB.prepare('SELECT version,payload_blob_id AS payloadBlobId FROM shared_settings WHERE team_code=? AND setting_key=?').bind(context.teamCode, settingKey).first();
  if (existing && version <= Number(existing.version)) return json({ ok: false, error: '云端已有更新设置', currentVersion: Number(existing.version) }, 409);
  const now = new Date().toISOString();
  await env.DB.prepare(`INSERT INTO shared_settings(team_code,setting_key,version,updated_at,payload_blob_id,source_device_id,server_changed_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(team_code,setting_key) DO UPDATE SET version=excluded.version,updated_at=excluded.updated_at,payload_blob_id=excluded.payload_blob_id,source_device_id=excluded.source_device_id,server_changed_at=excluded.server_changed_at`)
    .bind(context.teamCode, settingKey, version, cleanText(body.updatedAt, 60) || now, blobId, context.deviceId, now).run();
  if (existing && existing.payloadBlobId && existing.payloadBlobId !== blobId) await deleteBlobIfUnreferenced(context.teamCode, existing.payloadBlobId, env);
  return json({ ok: true, version });
}

async function acknowledge(context, env) {
  await env.DB.prepare('UPDATE devices SET last_ack_at=? WHERE team_code=? AND device_id=?').bind(new Date().toISOString(), context.teamCode, context.deviceId).run();
  return json({ ok: true });
}

async function getHolidays(year, env) {
  if (year < 2004 || year > 2100) return json({ ok: false, error: '年份超出范围' }, 400);
  const cached = await env.DB.prepare('SELECT payload,fetched_at AS fetchedAt,source_url AS sourceUrl FROM holiday_cache WHERE year=?').bind(year).first();
  const freshFor = 24 * 60 * 60 * 1000;
  if (cached && Date.now() - Date.parse(cached.fetchedAt) < freshFor) return json({ ok: true, year, data: JSON.parse(cached.payload), fetchedAt: cached.fetchedAt, source: cached.sourceUrl, cached: true });
  const sourceUrl = `https://cdn.jsdelivr.net/npm/chinese-days@latest/dist/years/${year}.json`;
  try {
    const response = await fetch(sourceUrl, { cf: { cacheEverything: true, cacheTtl: 86400 } });
    if (!response.ok) throw new Error('尚未发布该年度安排');
    const data = await response.json();
    if (!data || typeof data.holidays !== 'object' || typeof data.workdays !== 'object') throw new Error('节假日数据格式错误');
    const fetchedAt = new Date().toISOString();
    await env.DB.prepare(`INSERT INTO holiday_cache(year,payload,source_url,fetched_at) VALUES(?,?,?,?) ON CONFLICT(year) DO UPDATE SET payload=excluded.payload,source_url=excluded.source_url,fetched_at=excluded.fetched_at`)
      .bind(year, JSON.stringify(data), sourceUrl, fetchedAt).run();
    return json({ ok: true, year, data, fetchedAt, source: sourceUrl, cached: false });
  } catch (error) {
    if (cached) return json({ ok: true, year, data: JSON.parse(cached.payload), fetchedAt: cached.fetchedAt, source: cached.sourceUrl, cached: true, stale: true });
    return json({ ok: false, error: String(error && error.message || error) }, 404);
  }
}

async function deleteBlobIfUnreferenced(teamCode, blobId, env) {
  if (!blobId) return;
  const reference = await env.DB.prepare(`SELECT ((SELECT COUNT(*) FROM records WHERE team_code=? AND (payload_blob_id=? OR archive_blob_id=?)) + (SELECT COUNT(*) FROM shared_settings WHERE team_code=? AND payload_blob_id=?)) AS count`)
    .bind(teamCode, blobId, blobId, teamCode, blobId).first();
  if (Number(reference && reference.count || 0) === 0) await deleteBlob(teamCode, blobId, env);
}

async function deleteBlob(teamCode, blobId, env) {
  if (!blobId) return;
  await env.DB.prepare('DELETE FROM cloud_blob_chunks WHERE team_code=? AND blob_id=?').bind(teamCode, blobId).run();
  await env.DB.prepare('DELETE FROM cloud_blobs WHERE team_code=? AND blob_id=?').bind(teamCode, blobId).run();
}

async function readJson(request) {
  const contentLength = Number(request.headers.get('content-length') || 0);
  if (contentLength > 1000000) throw new Error('请求内容过大');
  const value = await request.json();
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new Error('请求格式无效');
  return value;
}

function json(payload, status = 200) {
  return new Response(JSON.stringify(payload), { status, headers: { ...JSON_HEADERS, ...CORS_HEADERS, 'cache-control': 'no-store' } });
}

function validId(value) { return ID_RE.test(String(value || '')); }
function cleanText(value, maxLength) { return String(value == null ? '' : value).trim().slice(0, maxLength || 160); }
function normalizePlatform(value) { return value === 'windows' ? 'windows' : 'android'; }
function safeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}
function randomToken() {
  const bytes = crypto.getRandomValues(new Uint8Array(32));
  return bytesToBase64Url(bytes);
}
async function sha256Base64(text) {
  const bytes = new TextEncoder().encode(String(text));
  const digest = new Uint8Array(await crypto.subtle.digest('SHA-256', bytes));
  return bytesToBase64Url(digest);
}
function bytesToBase64Url(bytes) {
  let binary = '';
  for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode(...bytes.subarray(i, Math.min(i + 0x8000, bytes.length)));
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
}

export const __test = { validId, cleanText, normalizePlatform, safeEqual, bytesToBase64Url, MAX_CHUNK_TEXT, MAX_BLOB_BYTES };
