import fs from 'node:fs';
import { OneBotClient } from '/opt/agent/src/onebot.js';

const link = JSON.parse(fs.readFileSync('/qqagent-state/protocol-link.json', 'utf8'));
const report = { schema: 1, executedOn: 'ANDROID_APPLICATION_PROCESS', timestamp: Date.now(),
  loginVerified: false, contactsPassed: false, qqMessagesSent: 0, gatePassed: false };
const client = new OneBotClient({ wsUrl: 'ws://127.0.0.1:3001', httpUrl: 'http://127.0.0.1:3000', accessToken: link.accessToken });
try {
  // Wait for the account to be ready after its private runtime starts.
  let info;
  const until = Date.now() + 90000;
  while (Date.now() < until) {
    try { info = await client.call('get_login_info'); if (String(info?.user_id) === String(link.userId)) break; }
    catch { /* account readiness is bounded; no message is sent */ }
    await new Promise(r => setTimeout(r, 1000));
  }
  if (String(info?.user_id) !== String(link.userId)) throw Error('QQ account did not become ready within 90 seconds');
  report.loginVerified = true;
  const friends = await client.call('get_friend_list');
  const groups = await client.call('get_group_list');
  if (!Array.isArray(friends) || !Array.isArray(groups)) throw Error('Unexpected contact list response');
  // Full lists stay on the phone. Desktop summaries contain counts only.
  fs.writeFileSync('/qqagent-state/contacts-private.json', JSON.stringify({ self: info, friends, groups }), { mode: 0o600 });
  report.friendCount = friends.length;
  report.groupCount = groups.length;
  const unauthorized = await fetch('http://127.0.0.1:3000/get_login_info', {
    method: 'POST', headers: { 'content-type': 'application/json', authorization: 'Bearer invalid-token' },
    body: '{}', signal: AbortSignal.timeout(10000) });
  report.unauthorizedStatus = unauthorized.status;
  report.contactsPassed = unauthorized.status === 401 || unauthorized.status === 403;
  if (!report.contactsPassed) throw Error('OneBot HTTP endpoint failed to reject an invalid token');
  report.marker = 'QQAGENT_CONTACTS_OK';
} catch (error) {
  report.error = String(error?.message || error).split(link.accessToken).join('[REDACTED]').slice(0, 1500);
} finally {
  fs.writeFileSync('/qqagent-state/contacts-report.json', JSON.stringify(report, null, 2), { mode: 0o600 });
  console.log(JSON.stringify(report));
  process.exitCode = report.contactsPassed ? 0 : 1;
}
