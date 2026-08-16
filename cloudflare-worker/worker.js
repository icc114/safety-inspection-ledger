/**
 * Safety Ledger WebDAV-compatible Cloudflare Worker v2.
 * Storage priority: R2 binding SAFETY_LEDGER_BUCKET; fallback: existing D1 binding DB.
 * D1 fallback stores binary objects in 256 KiB chunks so the user's existing DB deployment can be reused.
 */
const CHUNK_SIZE = 256 * 1024;

export default {
  async fetch(request, env) {
    const url = new URL(request.url);
    const method = request.method.toUpperCase();
    const backend = env.SAFETY_LEDGER_BUCKET ? 'R2' : env.DB ? 'D1' : null;

    if (url.pathname === '/health' && method === 'GET') {
      return json({
        ok: Boolean(backend), service: 'Safety Ledger Sync', protocol: 'safety-ledger-webdav-v2',
        storage: backend, signal: true,
        binding: backend === 'R2' ? 'SAFETY_LEDGER_BUCKET' : backend === 'D1' ? 'DB' : null,
        error: backend ? null : '未检测到存储绑定：可绑定现有 D1 为 DB，或绑定 R2 为 SAFETY_LEDGER_BUCKET',
      }, backend ? 200 : 503);
    }
    if (!backend) return json({ ok:false, error:'云端部署不完整：需要 D1(DB) 或 R2(SAFETY_LEDGER_BUCKET) 存储绑定。' }, 503);

    const key = decodeURIComponent(url.pathname.replace(/^\/+/, ''));
    if (!(await authorized(request, env, key))) return json({ ok:false, error:'需要设备授权' }, 401, { 'WWW-Authenticate':'SafetyLedger realm="Safety Ledger"' });
    const common = { DAV:'1', 'Cache-Control':'no-store', 'X-Safety-Ledger-Protocol':'safety-ledger-webdav-v2' };

    if (method === 'OPTIONS') return new Response(null, { status:204, headers:{ ...common, Allow:'OPTIONS, PROPFIND, MKCOL, PUT, GET, HEAD, DELETE' } });
    if (key.endsWith('/.sync-signal') && method === 'GET') {
      const space = firstSegment(key);
      return json({ ok:true, revision:await getRevision(env, space) }, 200, common);
    }
    if (method === 'MKCOL') return new Response(null, { status:201, headers:common });
    if (method === 'PUT') {
      if (!key) return new Response('Path required', { status:400, headers:common });
      const bytes = new Uint8Array(await request.arrayBuffer());
      const contentType = request.headers.get('content-type') || 'application/octet-stream';
      await putObject(env, key, bytes, contentType);
      if (isContentKey(key)) await bumpRevision(env, firstSegment(key));
      return new Response(null, { status:201, headers:common });
    }
    if (method === 'GET' || method === 'HEAD') {
      const object = await getObject(env, key);
      if (!object) return new Response('Not found', { status:404, headers:common });
      const headers = new Headers(common); headers.set('etag', object.etag); headers.set('content-length', String(object.size));
      if (object.contentType) headers.set('content-type', object.contentType);
      if (object.updatedAt) headers.set('last-modified', new Date(object.updatedAt).toUTCString());
      return new Response(method === 'HEAD' ? null : object.bytes, { status:200, headers });
    }
    if (method === 'DELETE') {
      await deleteObject(env, key); if (isContentKey(key)) await bumpRevision(env, firstSegment(key));
      return new Response(null, { status:204, headers:common });
    }
    if (method === 'PROPFIND') {
      const depth=request.headers.get('depth')||'0';const normalized=key&&!key.endsWith('/')?`${key}/`:key;
      const entries=[{key:normalized,directory:true,size:0}];
      if(depth!=='0') entries.push(...await listObjects(env,normalized));
      const xml=`<?xml version="1.0" encoding="utf-8"?><d:multistatus xmlns:d="DAV:">${entries.map(e=>responseXml(e)).join('')}</d:multistatus>`;
      return new Response(xml,{status:207,headers:{...common,'Content-Type':'application/xml; charset=utf-8'}});
    }
    return new Response('Method not allowed',{status:405,headers:common});
  },
};

async function authorized(request, env, key) {
  const header=request.headers.get('authorization')||'';
  if(env.SYNC_TOKEN&&header===`Bearer ${env.SYNC_TOKEN}`)return true;
  if(env.SYNC_USERNAME&&env.SYNC_PASSWORD&&header===`Basic ${btoa(`${env.SYNC_USERNAME}:${env.SYNC_PASSWORD}`)}`)return true;
  if(env.DISABLE_SELF_PROVISION==='true')return false;
  const space=request.headers.get('x-safety-ledger-space')||'',prefix='SafetyLedger ';
  if(!space||space==='_safety_auth'||!header.startsWith(prefix))return false;
  const proof=header.slice(prefix.length);if(!/^[A-Za-z0-9_-]{43}$/.test(proof))return false;
  const first=firstSegment(key);if(first&&first!==space)return false;
  const authId=`auth:${await sha256Url(space)}`;const existing=await metaGet(env,authId);
  if(!existing){await metaSet(env,authId,proof);return true;}return constantTimeEqual(existing.trim(),proof);
}

function isContentKey(key){return /(^|\/)devices\/.*\.safetydata$/i.test(key)&&!key.includes('.safety-pc-probe-');}
function firstSegment(key){return key.split('/').filter(Boolean)[0]||'';}
async function getRevision(env,space){return (await metaGet(env,`rev:${space}`))||'0';}
async function bumpRevision(env,space){if(space)await metaSet(env,`rev:${space}`,`${Date.now()}-${crypto.randomUUID()}`);}

async function ensureD1(db){
  await db.batch([
    db.prepare('CREATE TABLE IF NOT EXISTS dav_objects (key TEXT PRIMARY KEY, size INTEGER NOT NULL, etag TEXT NOT NULL, content_type TEXT, updated_at INTEGER NOT NULL, chunks INTEGER NOT NULL)'),
    db.prepare('CREATE TABLE IF NOT EXISTS dav_chunks (key TEXT NOT NULL, idx INTEGER NOT NULL, data BLOB NOT NULL, PRIMARY KEY(key, idx))'),
    db.prepare('CREATE TABLE IF NOT EXISTS dav_meta (name TEXT PRIMARY KEY, value TEXT NOT NULL)')
  ]);
}

async function metaGet(env,name){
  if(env.SAFETY_LEDGER_BUCKET){const o=await env.SAFETY_LEDGER_BUCKET.get(`_safety_meta/${await sha256Url(name)}.txt`);return o?await o.text():null;}
  await ensureD1(env.DB);const row=await env.DB.prepare('SELECT value FROM dav_meta WHERE name=?').bind(name).first();return row?String(row.value):null;
}
async function metaSet(env,name,value){
  if(env.SAFETY_LEDGER_BUCKET){await env.SAFETY_LEDGER_BUCKET.put(`_safety_meta/${await sha256Url(name)}.txt`,String(value));return;}
  await ensureD1(env.DB);await env.DB.prepare('INSERT INTO dav_meta(name,value) VALUES(?,?) ON CONFLICT(name) DO UPDATE SET value=excluded.value').bind(name,String(value)).run();
}

async function putObject(env,key,bytes,contentType){
  if(env.SAFETY_LEDGER_BUCKET){await env.SAFETY_LEDGER_BUCKET.put(key,bytes,{httpMetadata:{contentType}});return;}
  await ensureD1(env.DB);const etag=`"${await sha256UrlBytes(bytes)}"`,updated=Date.now(),chunks=Math.ceil(bytes.length/CHUNK_SIZE);
  const statements=[env.DB.prepare('DELETE FROM dav_chunks WHERE key=?').bind(key),env.DB.prepare('DELETE FROM dav_objects WHERE key=?').bind(key)];
  for(let i=0;i<chunks;i++){const part=bytes.slice(i*CHUNK_SIZE,Math.min(bytes.length,(i+1)*CHUNK_SIZE));statements.push(env.DB.prepare('INSERT INTO dav_chunks(key,idx,data) VALUES(?,?,?)').bind(key,i,part.buffer));}
  statements.push(env.DB.prepare('INSERT INTO dav_objects(key,size,etag,content_type,updated_at,chunks) VALUES(?,?,?,?,?,?)').bind(key,bytes.length,etag,contentType,updated,chunks));
  await env.DB.batch(statements);
}
async function getObject(env,key){
  if(env.SAFETY_LEDGER_BUCKET){const o=await env.SAFETY_LEDGER_BUCKET.get(key);if(!o)return null;const bytes=await o.arrayBuffer();return{bytes,size:o.size,etag:o.httpEtag,contentType:o.httpMetadata?.contentType||'application/octet-stream',updatedAt:o.uploaded?o.uploaded.getTime():Date.now()};}
  await ensureD1(env.DB);const meta=await env.DB.prepare('SELECT size,etag,content_type,updated_at,chunks FROM dav_objects WHERE key=?').bind(key).first();if(!meta)return null;
  const rows=(await env.DB.prepare('SELECT idx,data FROM dav_chunks WHERE key=? ORDER BY idx').bind(key).all()).results||[];const out=new Uint8Array(Number(meta.size));let offset=0;
  for(const row of rows){let part;if(row.data instanceof ArrayBuffer)part=new Uint8Array(row.data);else if(ArrayBuffer.isView(row.data))part=new Uint8Array(row.data.buffer);else part=new Uint8Array(row.data||[]);out.set(part,offset);offset+=part.length;}
  return{bytes:out,size:Number(meta.size),etag:String(meta.etag),contentType:String(meta.content_type||'application/octet-stream'),updatedAt:Number(meta.updated_at)};
}
async function deleteObject(env,key){
  if(env.SAFETY_LEDGER_BUCKET){await env.SAFETY_LEDGER_BUCKET.delete(key);return;}
  await ensureD1(env.DB);await env.DB.batch([env.DB.prepare('DELETE FROM dav_chunks WHERE key=?').bind(key),env.DB.prepare('DELETE FROM dav_objects WHERE key=?').bind(key)]);
}
async function listObjects(env,prefix){
  if(env.SAFETY_LEDGER_BUCKET){const out=[];let cursor;do{const page=await env.SAFETY_LEDGER_BUCKET.list({prefix,cursor});for(const o of page.objects)if(!o.key.startsWith('_safety_'))out.push({key:o.key,directory:false,size:o.size});cursor=page.truncated?page.cursor:undefined;}while(cursor);return out;}
  await ensureD1(env.DB);const rows=(await env.DB.prepare('SELECT key,size FROM dav_objects WHERE substr(key,1,?)=? ORDER BY key').bind(prefix.length,prefix).all()).results||[];return rows.map(r=>({key:String(r.key),directory:false,size:Number(r.size)}));
}

function responseXml(entry){const path='/'+entry.key.split('/').filter(Boolean).map(encodeURIComponent).join('/')+(entry.directory?'/':'');return `<d:response><d:href>${escapeXml(path)}</d:href><d:propstat><d:prop><d:displayname>${escapeXml(entry.key.split('/').filter(Boolean).pop()||'root')}</d:displayname>${entry.directory?'<d:resourcetype><d:collection/></d:resourcetype>':`<d:resourcetype/><d:getcontentlength>${entry.size}</d:getcontentlength>`}</d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>`;}
function json(value,status=200,extra={}){return new Response(JSON.stringify(value),{status,headers:{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store',...extra}});}
async function sha256Url(value){return sha256UrlBytes(new TextEncoder().encode(value));}
async function sha256UrlBytes(bytes){const digest=await crypto.subtle.digest('SHA-256',bytes);let binary='';for(const b of new Uint8Array(digest))binary+=String.fromCharCode(b);return btoa(binary).replaceAll('+','-').replaceAll('/','_').replaceAll('=','');}
function constantTimeEqual(left,right){if(left.length!==right.length)return false;let d=0;for(let i=0;i<left.length;i++)d|=left.charCodeAt(i)^right.charCodeAt(i);return d===0;}
function escapeXml(value){return value.replaceAll('&','&amp;').replaceAll('<','&lt;').replaceAll('>','&gt;').replaceAll('"','&quot;');}
