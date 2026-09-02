// R221 补测：带活动局的 /reload 清理验证（#9）
const mineflayer = require('mineflayer');
const fs = require('fs');
const { rconCommand } = require('./rcon.js');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const RCON = (cmd) => rconCommand('127.0.0.1', 25575, 'balatro220', cmd, 15000);
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}
function balCount(bot, r = 16) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (e.position.distanceTo(bot.entity.position) <= r && (e.name === 'interaction' || e.name === 'text_display')) out.push(e);
  }
  return out.length;
}
function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+)/);
      if (m) v = { phase: m[3] };
      if (/没有进行中/.test(s)) v = v || { phase: 'NOSESSION' };
    };
    bot.on('message', onMsg);
    bot.chat('/balatro status');
    await sleep(waitMs);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
}
(async () => {
  const b1 = await makeBot('BalBot');
  function onMsg(j) { const s = j.toString(); if (s && !/^\[Interaction\]/.test(s)) log(`CHAT ${s.slice(0, 110)}`); }
  b1.on('message', onMsg);
  b1.chat('/balatro play RELOADT');
  await sleep(3500);
  const pre = balCount(b1);
  log('entities pre-reload:', pre);

  const rl = await RCON('reload').catch(e => 'ERR ' + e.message);
  log('RCON reload:', String(rl).slice(0, 60));
  await sleep(15000);
  const post = balCount(b1);
  const st = await statusSnapshot(b1);
  step('reload_live_cleanup', post === 0 && st && st.phase === 'NOSESSION', `活动局 reload：实体 ${pre}->${post}，status=${st && st.phase}`);

  b1.chat('/balatro play AFTER2');
  await sleep(3500);
  const st2 = await statusSnapshot(b1);
  const ents2 = balCount(b1);
  step('reload_reusable', st2 && st2.phase !== 'NOSESSION' && ents2 > 5, `reload 后再开局 entities=${ents2} phase=${st2 && st2.phase}`);

  // 服务器日志无 Balatro 异常
  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check12-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
  async function makeBot(u) {
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: u, version: VER, auth: 'offline' });
    bot.on('kicked', r => log(`${u} KICKED:`, JSON.stringify(r).slice(0, 150)));
    bot.on('error', e => log(`${u} ERROR:`, e.message));
    await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
    return bot;
  }
})().catch(ex => { log('FATAL', ex); process.exit(2); });
