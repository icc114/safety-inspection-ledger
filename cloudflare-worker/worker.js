/**
 * Safety Ledger WebDAV-compatible Cloudflare Worker.
 * Bind an R2 bucket as SAFETY_LEDGER_BUCKET. By default sync spaces self-provision
 * from an app-derived proof; optional SYNC_TOKEN or Basic secrets can lock it down.
 */
export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method.toUpperCase();

    // Public diagnostics endpoint. It exposes no user data or credentials, only whether
    // the Worker was deployed with the storage binding required by the Android client.
    if (url.pathname === '/health' && method === 'GET') {
      const ready = Boolean(env.SAFETY_LEDGER_BUCKET);
      return new Response(JSON.stringify({
        ok: ready,
        service: 'Safety Ledger Sync',
        protocol: 'safety-ledger-webdav-v1',
        storage: 'R2',
        binding: 'SAFETY_LEDGER_BUCKET',
        error: ready ? null : '未绑定 R2：请将私有 R2 bucket 绑定为 SAFETY_LEDGER_BUCKET',
      }), {
        status: ready ? 200 : 503,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
      });
    }

    if (!env.SAFETY_LEDGER_BUCKET) {
      return new Response(JSON.stringify({
        ok: false,
        error: '云端部署不完整：当前 APK 需要 R2，绑定名必须为 SAFETY_LEDGER_BUCKET；旧版 D1 env.DB Worker 不兼容。',
      }), {
        status: 503,
        headers: { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' },
      });
    }

    const key = decodeURIComponent(url.pathname.replace(/^\/+/, ''));
    if (!(await authorized(request, env, key))) {
      return new Response(JSON.stringify({ ok: false, error: '需要设备授权' }), {
        status: 401,
        headers: {
          'Content-Type': 'application/json; charset=utf-8',
          'WWW-Authenticate': 'SafetyLedger realm="Safety Ledger"',
        },
      });
    }

    const common = { 'DAV': '1', 'Cache-Control': 'no-store' };

    if (method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: { ...common, 'Allow': 'OPTIONS, PROPFIND, MKCOL, PUT, GET, HEAD, DELETE' },
      });
    }
    if (method === 'MKCOL') return new Response(null, { status: 201, headers: common });
    if (method === 'PUT') {
      if (!key) return new Response('Path required', { status: 400, headers: common });
      await env.SAFETY_LEDGER_BUCKET.put(key, request.body, {
        httpMetadata: { contentType: request.headers.get('content-type') || 'application/octet-stream' },
      });
      return new Response(null, { status: 201, headers: common });
    }
    if (method === 'GET' || method === 'HEAD') {
      const object = await env.SAFETY_LEDGER_BUCKET.get(key);
      if (!object) return new Response('Not found', { status: 404, headers: common });
      const headers = new Headers(common);
      object.writeHttpMetadata(headers);
      headers.set('etag', object.httpEtag);
      headers.set('content-length', String(object.size));
      return new Response(method === 'HEAD' ? null : object.body, { headers });
    }
    if (method === 'DELETE') {
      await env.SAFETY_LEDGER_BUCKET.delete(key);
      return new Response(null, { status: 204, headers: common });
    }
    if (method === 'PROPFIND') {
      const depth = request.headers.get('depth') || '0';
      const normalized = key && !key.endsWith('/') ? `${key}/` : key;
      const entries = [{ key: normalized, directory: true, size: 0 }];
      if (depth !== '0') {
        let cursor;
        do {
          const page = await env.SAFETY_LEDGER_BUCKET.list({ prefix: normalized, cursor });
          for (const object of page.objects) entries.push({
            key: object.key,
            directory: false,
            size: object.size,
          });
          cursor = page.truncated ? page.cursor : undefined;
        } while (cursor);
      }
      const xml = `<?xml version="1.0" encoding="utf-8"?>`
        + `<d:multistatus xmlns:d="DAV:">${entries.map(entry => responseXml(url, entry)).join('')}</d:multistatus>`;
      return new Response(xml, {
        status: 207,
        headers: { ...common, 'Content-Type': 'application/xml; charset=utf-8' },
      });
    }
    return new Response('Method not allowed', { status: 405, headers: common });
  },
};

async function authorized(request, env, key) {
  const header = request.headers.get('authorization') || '';
  if (env.SYNC_TOKEN && header === `Bearer ${env.SYNC_TOKEN}`) return true;
  if (env.SYNC_USERNAME && env.SYNC_PASSWORD) {
    const expected = btoa(`${env.SYNC_USERNAME}:${env.SYNC_PASSWORD}`);
    if (header === `Basic ${expected}`) return true;
  }

  // Passwordless-looking app pairing: the password itself never leaves the device.
  // The Android app sends a SHA-256 proof scoped to the sync-space name. On first
  // use the proof claims that namespace; later devices must present the same proof.
  if (env.DISABLE_SELF_PROVISION === 'true') return false;
  const space = request.headers.get('x-safety-ledger-space') || '';
  const prefix = 'SafetyLedger ';
  if (!space || space === '_safety_auth' || !header.startsWith(prefix)) return false;
  const proof = header.slice(prefix.length);
  if (!/^[A-Za-z0-9_-]{43}$/.test(proof)) return false;
  const firstPathSegment = key.split('/').filter(Boolean)[0] || '';
  if (firstPathSegment && firstPathSegment !== space) return false;

  const authKey = `_safety_auth/${await sha256Url(space)}.txt`;
  const existing = await env.SAFETY_LEDGER_BUCKET.get(authKey);
  if (!existing) {
    await env.SAFETY_LEDGER_BUCKET.put(authKey, proof, {
      httpMetadata: { contentType: 'text/plain; charset=utf-8' },
      customMetadata: { protocol: 'safety-ledger-auth-v1' },
    });
    return true;
  }
  return constantTimeEqual((await existing.text()).trim(), proof);
}

async function sha256Url(value) {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(value));
  let binary = '';
  for (const byte of new Uint8Array(digest)) binary += String.fromCharCode(byte);
  return btoa(binary).replaceAll('+', '-').replaceAll('/', '_').replaceAll('=', '');
}

function constantTimeEqual(left, right) {
  if (left.length !== right.length) return false;
  let difference = 0;
  for (let index = 0; index < left.length; index += 1) {
    difference |= left.charCodeAt(index) ^ right.charCodeAt(index);
  }
  return difference === 0;
}

function responseXml(url, entry) {
  const path = '/' + entry.key.split('/').filter(Boolean).map(encodeURIComponent).join('/')
    + (entry.directory ? '/' : '');
  return `<d:response><d:href>${escapeXml(path)}</d:href><d:propstat><d:prop>`
    + `<d:displayname>${escapeXml(entry.key.split('/').filter(Boolean).pop() || 'root')}</d:displayname>`
    + (entry.directory ? `<d:resourcetype><d:collection/></d:resourcetype>`
      : `<d:resourcetype/><d:getcontentlength>${entry.size}</d:getcontentlength>`)
    + `</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`;
}

function escapeXml(value) {
  return value.replaceAll('&', '&amp;').replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;').replaceAll('"', '&quot;');
}
