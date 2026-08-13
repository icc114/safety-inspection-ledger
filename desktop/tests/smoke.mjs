import { readFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import assert from 'node:assert/strict';

const required = ['index.html', 'app.js', 'app.css', 'cloud-sync.js', 'package.json', 'build.mjs'];
for (const path of required) assert.ok(existsSync(new URL(`../${path}`, import.meta.url)), `missing ${path}`);

const html = await readFile(new URL('../index.html', import.meta.url), 'utf8');
const app = await readFile(new URL('../app.js', import.meta.url), 'utf8');
const sync = await readFile(new URL('../cloud-sync.js', import.meta.url), 'utf8');

for (const text of ['安全检查台账', '检查填报', '云同步', '多选导出']) assert.ok(html.includes(text), `UI missing ${text}`);
for (const text of ['季度', '年度', '回收站', '法定节假日']) assert.ok(app.includes(text), `logic missing ${text}`);
for (const text of ['testConnection', 'prototype.sync', 'hardDeleteRecord']) assert.ok(sync.includes(text), `sync missing ${text}`);

for (const path of [
  'dist/index.html', 'dist/app.js', 'dist/cloud-sync.js', 'dist/font-data.js',
  'dist/fonts/NotoSansHans-Regular.woff', 'dist/vendor/pdf-lib.min.js',
  'dist/vendor/fontkit.umd.min.js', 'dist/vendor/lunar.js',
]) assert.ok(existsSync(new URL(`../${path}`, import.meta.url)), `build missing ${path}`);

console.log('Desktop static source smoke tests passed.');
