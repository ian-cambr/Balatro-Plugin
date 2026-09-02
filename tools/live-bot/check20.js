// R224 #16：权限实施验证（LuckPerms 无权组拒绝 + 对照组 + Tab 补全空）
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) { results.push({ name, ok, detail }); log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`); }

function balCount(bot, r = 16) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.position && e !== bot.entity && e.position.distanceTo(bot.entity.position) <= r && (e.name === 'interaction' || e.name === 'text_display')) out.push(e);
  }
  return out.length;
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 130)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { const s = json.toString(); if (s) log(`CHAT<${username}> ${s.slice(0, 110)}`); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 40000); });
  return bot;
}
function tabComplete(bot, text) {
  return new Promise((resolve) => {
    const timeout = setTimeout(() => resolve(null), 3000);
    bot.once('tabComplete', (r) => { clearTimeout(timeout); resolve(r); });
    bot.tabComplete(text);
  });
}

(async () => {
  // 管理员假人建组授权
  const admin = await makeBot('BalBot');
  await sleep(1500);
  admin.chat('/lp creategroup noplay');
  await sleep(1500);
  admin.chat('/lp group noplay permission set balatro.play false');
  await sleep(1800);
  admin.chat('/lp user NoPlay1 parent add noplay');
  await sleep(2500);

  // 无权假人
  const np1 = await makeBot('NoPlay1');
  await sleep(1500);
  let deniedMsg = false;
  const onMsg = (j) => { if (/权限/.test(j.toString())) deniedMsg = true; };
  np1.on('message', onMsg);
  np1.chat('/balatro play DENYTEST');
  await sleep(3000);
  const ents1 = balCount(np1);
  const tab1 = await tabComplete(np1, '/balatro ');
  step('perm_denied_command', deniedMsg, `拒绝消息=${deniedMsg}，实体=${ents1}（应为 0）`);
  step('perm_denied_no_session', ents1 === 0, '无权者不产生牌桌实体');
  const tabEmpty = tab1 === null || (tab1.matches && tab1.matches.length === 0);
  step('perm_denied_tab_empty', tabEmpty, `Tab 补全=${JSON.stringify(tab1 && tab1.matches || tab1)}（应空）`);

  // 对照组：无组新假人（balatro.play 默认 true）
  const np2 = await makeBot('NoPlay2');
  await sleep(1500);
  np2.chat('/balatro play CTRLTEST');
  await sleep(3500);
  const ents2 = balCount(np2);
  const tab2 = await tabComplete(np2, '/balatro ');
  step('perm_allowed_control', ents2 > 5, `对照组实体=${ents2}（默认 true 可玩）`);
  step('perm_allowed_tab', tab2 && tab2.matches && tab2.matches.length > 0, `对照组 Tab=${tab2 && tab2.matches && tab2.matches.length} 项`);

  // 再验证：撤销后中途是否影响已开局面（NoPlay2 加组后命令被拒但已开局面保留/或清理——只观察行为不设硬断言）
  admin.chat('/lp user NoPlay2 parent add noplay');
  await sleep(2500);
  let denied2 = false;
  const onMsg2 = (j) => { if (/权限/.test(j.toString())) denied2 = true; };
  np2.on('message', onMsg2);
  np2.chat('/balatro status');
  await sleep(2000);
  log('NoPlay2 加组后 status 被拒=', denied2, '实体仍=', balCount(np2));
  step('perm_revoked_midgame', true, `局中撤销：命令拒绝=${denied2}，实体=${balCount(np2)}（行为记录）`);

  np2.chat('/balatro quit'); await sleep(800);
  np2.quit(); np1.quit(); admin.quit();
  await sleep(1500);
  fs.writeFileSync(__dirname + '/check20-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check20-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
