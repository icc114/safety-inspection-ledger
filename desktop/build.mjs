import { copyFile, mkdir, readFile, writeFile } from 'node:fs/promises';
import { join } from 'node:path';

const root = new URL('.', import.meta.url);
const dist = new URL('./dist/', root);
await mkdir(dist, { recursive: true });
await mkdir(new URL('./vendor/', dist), { recursive: true });
await mkdir(new URL('./fonts/', dist), { recursive: true });

const sourceFiles = [
  'index.html', 'app.js', 'cloud-sync.js', 'app.css',
  '打开安全检查台账.bat', '使用说明.txt', '版本说明.txt',
  '先看这里-云同步.txt', '云服务提供商说明.txt',
];
for (const name of sourceFiles) await copyFile(new URL(name, root), new URL(name, dist));

const dependencies = [
  ['node_modules/pdf-lib/dist/pdf-lib.min.js', 'vendor/pdf-lib.min.js'],
  ['node_modules/@pdf-lib/fontkit/dist/fontkit.umd.min.js', 'vendor/fontkit.umd.min.js'],
  ['node_modules/lunar-javascript/lunar.js', 'vendor/lunar.js'],
];
for (const [from, to] of dependencies) await copyFile(new URL(from, root), new URL(to, dist));

const fontPath = new URL('node_modules/@fontsource/noto-sans-sc/files/noto-sans-sc-chinese-simplified-400-normal.woff', root);
const font = await readFile(fontPath);
await copyFile(fontPath, new URL('fonts/NotoSansHans-Regular.woff', dist));
const base64 = font.toString('base64');
const chunks = [];
for (let offset = 0; offset < base64.length; offset += 900_000) chunks.push(base64.slice(offset, offset + 900_000));
await writeFile(new URL('font-data.js', dist), `(function(){window.__CAR_SHED_PDF_FONT_CHUNKS=${JSON.stringify(chunks)};})();\n`);

console.log(`Windows offline package built at ${join(new URL(dist).pathname)}`);
