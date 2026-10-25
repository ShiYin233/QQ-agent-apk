import { build } from 'esbuild';
import fs from 'node:fs/promises';
import { patchGoogleOAuth } from './google-oauth-patch.mjs';
const result = await build({ entryPoints:['sdk-entry.mjs'], outfile:'../app/src/main/assets/runtime/subscription-sdk.mjs', bundle:true,metafile:true,
  platform:'node', target:'node22', format:'esm', minify:true, legalComments:'eof', packages:'bundle',
  banner:{js:"import {createRequire as __sdkCreateRequire} from 'node:module'; const require = __sdkCreateRequire(import.meta.url);"},
  plugins:[{name:'persist-google-grant-before-setup',setup(builder){builder.onLoad({filter:/pi-google-legacy[\\/]dist[\\/]utils[\\/]oauth[\\/]google-gemini-cli\.js$/},async args=>({contents:patchGoogleOAuth(await fs.readFile(args.path,'utf8')),loader:'js'}));}}] });
const licenses = [...new Set(Object.keys(result.metafile.inputs).flatMap(input => {
  const match = /^(.*node_modules\/(?:@[^/]+\/)?[^/]+)/.exec(input);
  return match ? [match[1]] : [];
}))].sort();
const out = [];
for (const name of licenses) {
  const dir = name;
  const files = await fs.readdir(dir);
  const license = files.find(f => /^licen[sc]e(?:\.|$)/i.test(f));
  const pkg = JSON.parse(await fs.readFile(`${dir}/package.json`,'utf8'));
  let contents = license ? await fs.readFile(`${dir}/${license}`,'utf8') : '';
  if (!contents && ['@earendil-works/pi-ai','@mariozechner/pi-ai'].includes(pkg.name)) contents = await fs.readFile(`licenses/pi-ai-${pkg.version}-LICENSE.txt`,'utf8');
  if (!contents && pkg.name==='data-uri-to-buffer') {
    const readme=await fs.readFile(`${dir}/README.md`,'utf8');contents=readme.slice(readme.indexOf('(The MIT License)')).replace(/\n\[rfc\]:[\s\S]*$/,'');
  }
  if (!contents) throw Error(`Missing bundled dependency license: ${pkg.name}@${pkg.version}`);
  out.push(`${pkg.name}@${pkg.version} (${pkg.license || 'See package'})\n${contents}`);
}
await fs.writeFile('../app/src/main/assets/runtime/subscription-sdk-LICENSES.txt',out.join('\n\n--------------------\n\n'));
