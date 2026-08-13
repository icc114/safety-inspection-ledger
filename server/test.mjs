import assert from 'node:assert/strict';
import worker, { __test } from './src/worker.js';

assert.equal(__test.validId('record-2026:08'), true);
assert.equal(__test.validId('../bad'), false);
assert.equal(__test.cleanText('  安全检查单位  ', 20), '安全检查单位');
assert.equal(__test.normalizePlatform('windows'), 'windows');
assert.equal(__test.normalizePlatform('unknown'), 'android');
assert.equal(__test.safeEqual('abc', 'abc'), true);
assert.equal(__test.safeEqual('abc', 'abd'), false);
assert.equal(__test.bytesToBase64Url(new Uint8Array([255, 254, 253])), '__79');

const response = await worker.fetch(new Request('https://example.test/api/health'), {});
assert.equal(response.status, 200);
const payload = await response.json();
assert.equal(payload.ok, true);
assert.equal(payload.version, 3);

const options = await worker.fetch(new Request('https://example.test/api/v1/manifest', { method: 'OPTIONS' }), {});
assert.equal(options.status, 204);
assert.equal(options.headers.get('access-control-allow-origin'), '*');

console.log('Cloud worker static and public-route tests passed.');
