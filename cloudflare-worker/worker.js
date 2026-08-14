/**
 * Safety Ledger WebDAV-compatible Cloudflare Worker.
 * Bind an R2 bucket as SAFETY_LEDGER_BUCKET and configure either SYNC_TOKEN,
 * or SYNC_USERNAME + SYNC_PASSWORD as encrypted Worker secrets.
 */
export default {
  async fetch(request, env) {
    if (!authorized(request, env)) {
      return new Response('Unauthorized', {
        status: 401,
        headers: { 'WWW-Authenticate': 'Basic realm="Safety Ledger"' },
      });
    }

    const url = new URL(request.url);
    const key = decodeURIComponent(url.pathname.replace(/^\/+/, ''));
    const method = request.method.toUpperCase();
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

function authorized(request, env) {
  const header = request.headers.get('authorization') || '';
  if (env.SYNC_TOKEN && header === `Bearer ${env.SYNC_TOKEN}`) return true;
  if (env.SYNC_USERNAME && env.SYNC_PASSWORD) {
    const expected = btoa(`${env.SYNC_USERNAME}:${env.SYNC_PASSWORD}`);
    return header === `Basic ${expected}`;
  }
  return false;
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
