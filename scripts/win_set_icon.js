// 把 windows-app/assets/icon.ico 写进 Electron 打包出来的 exe 图标资源。
// Linux 上没有 wine，rcedit 跑不起来，所以用纯 JS 的 resedit 直接改 PE 资源。
// 依赖：windows-app 里需要装 resedit（没写进 package.json）：cd windows-app && npm i resedit
// 用法: node scripts/win_set_icon.js <exe路径> [ico路径]
const fs = require('fs');
const path = require('path');
const { createRequire } = require('module');
// resedit 装在 windows-app/node_modules 下，且是 ESM 包（无 main 字段），
// 所以按名字从 windows-app 目录解析，别用绝对路径 require 整个包目录。
const req = createRequire(path.join(__dirname, '..', 'windows-app', 'package.json'));
const ResEdit = req('resedit');

const exePath = process.argv[2];
const icoPath = process.argv[3] || path.join(__dirname, '..', 'windows-app', 'assets', 'icon.ico');
if (!exePath || !fs.existsSync(exePath)) {
  console.error('用法: node scripts/win_set_icon.js <exe路径> [ico路径]');
  process.exit(2);
}
const ico = ResEdit.Data.IconFile.from(fs.readFileSync(icoPath));
const exe = ResEdit.NtExecutable.from(fs.readFileSync(exePath));
const res = ResEdit.NtExecutableResource.from(exe);

const groups = ResEdit.Resource.IconGroupEntry.fromEntries(res.entries);
console.log('exe 原有图标组:', groups.map(function (g) { return 'id=' + g.id + ' lang=' + g.lang + ' n=' + g.icons.length; }).join(' | ') || '(无)');

const id = groups.length ? groups[0].id : 1;
const lang = groups.length ? groups[0].lang : 1033;
ResEdit.Resource.IconGroupEntry.replaceIconsForResource(res.entries, id, lang, ico.icons.map(function (i) { return i.data; }));
res.outputResource(exe);
fs.writeFileSync(exePath, Buffer.from(exe.generate()));

const check = ResEdit.NtExecutableResource.from(ResEdit.NtExecutable.from(fs.readFileSync(exePath)));
const after = ResEdit.Resource.IconGroupEntry.fromEntries(check.entries);
console.log('写入后图标组:', after.map(function (g) { return 'id=' + g.id + ' lang=' + g.lang + ' 尺寸=' + g.icons.map(function (i) { return i.width + 'x' + i.height; }).join(','); }).join(' | '));
console.log('OK  icon baked:', exePath);
