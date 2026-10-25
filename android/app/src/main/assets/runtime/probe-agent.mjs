import crypto from 'node:crypto';
import { updateConfig } from '/opt/agent/src/config.js';
import { createApp } from '/opt/agent/src/app.js';

// A dedicated empty profile: no model key, no permitted chats, no protocol link.
const token = crypto.randomBytes(32).toString('hex');
updateConfig({ server: { port: 3310, token }, allowAllWhenEmpty: false,
  allow: { groups: [], privates: [] }, extensions: { hotReload: false },
  snowluma: { autoLaunch: false, wsUrl: 'ws://127.0.0.1:65530', httpUrl: 'http://127.0.0.1:65530', accessToken: '' } });
const app = createApp({ log: () => {} });
try {
  await app.start();
  const address = app.server.address();
  if (address.address !== '127.0.0.1') throw Error('Core must bind loopback');
  const url = `http://127.0.0.1:${address.port}/api/status`;
  const denied = await fetch(url, { headers: { 'x-console-token': 'invalid-token' }, signal: AbortSignal.timeout(15000) });
  const accepted = await fetch(url, { headers: { 'x-console-token': token }, signal: AbortSignal.timeout(15000) });
  const status = await accepted.json();
  if (denied.status !== 401 || accepted.status !== 200 || !status || typeof status !== 'object') throw Error('Core authentication test failed');
  console.log(JSON.stringify({ marker: 'QQAGENT_ORIGINAL_CORE_OK', bindAddress: address.address,
    authenticatedStatus: accepted.status, unauthorizedStatus: denied.status, sourceVersion: '0.4.0',
    modelInvoked: false, qqMessagesSent: 0 }));
  await app.stop();
  process.exit(0);
} catch (error) {
  console.error(error.message);
  await app.stop().catch(() => {});
  process.exit(1);
}
