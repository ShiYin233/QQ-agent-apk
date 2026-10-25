const fs = require('node:fs');
const path = '/opt/QQ/resources/app/package.json';
const original = path + '.qqagent-original';
if (!fs.existsSync(original)) fs.copyFileSync(path, original, fs.constants.COPYFILE_EXCL);
const config = JSON.parse(fs.readFileSync(original, 'utf8'));
config.main = './loadNapCat.js';
fs.writeFileSync(path, JSON.stringify(config, null, 2));
fs.writeFileSync('/opt/QQ/resources/app/loadNapCat.js', "(async () => { await import('file:///opt/napcat/napcat.mjs'); })();\n");
console.log('QQAGENT_NAPCAT_ENTRY_OK');
