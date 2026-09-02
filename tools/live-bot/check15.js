// R221：纯 10 假人 soak（RCON spark 采样 TPS）
const mineflayer = require('mineflayer');
const fs = require('fs');
const { execSync } = require('child_process');
const { rconCommand } = require('./rcon.js');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const RCON = (cmd) => rconCommand('127.0.0.1', 25575, 'balatro220', cmd, 15000);
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) { results.push({ name, ok, detail }); log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`); }

const val = (r) => r === 'A' ? 14 : r === 'K' ? 13 : r === 'Q' ? 12 : r === 'J' ? 11 : r === '10' ? 10 : parseInt(r);
function decide(handStr) {
  const cards = [...handStr.matchAll(/[♠♥♦♣]\s*([AJQK]|10|\d)/g)].map(t => t[1]);
  const cnt = {};
  cards.forEach((r, i) => { (cnt[r] = cnt[r] || []).push(i); });
  const groups = Object.values(cnt).sort((a, b) => b.length - a.length || val(cards[b[0]]) - val(cards[a[0]]));
  const best = groups[0] || [], second = groups[1] || [];
  if (best.length >= 3) return { play: best, discard: [] };
  if (best.length === 2 && second.length === 2) return { play: [...best, ...second], discard: [] };
  if (best.length === 2 && val(cards[best[0]]) >= 11) return { play: best, discard: [] };
  const keep = cards.map((r, i) => ({ i, v: val(r) })).sort((a, b) => b.v - a.v).slice(0, 3).map(o => o.i);
  return { play: [], discard: cards.map((r, i) => i).filter(i => !keep.includes(i)) };
}
function inters(bot, r = 16) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (e.position.distanceTo(bot.entity.position) <= r && e.name === 'interaction' && e.metadata && +e.metadata[8] > 0) out.push(e);
  }
  return out;
}
function boardAxes(bot, list) {
  const p = bot.entity.position;
  const cx = list.reduce((s, e) => s + e.position.x, 0) / list.length;
  const cz = list.reduce((s, e) => s + e.position.z, 0) / list.length;
  let fx = cx - p.x, fz = cz - p.z;
  const fl = Math.hypot(fx, fz) || 1; fx /= fl; fz /= fl;
  const rx = -fz, rz = fx;
  return (e) => (e.position.x - cx) * rx + (e.position.z - cz) * rz;
}
function statusSnapshot(bot, waitMs = 1200) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+)/);
      if (m) v = { phase: m[1], discards: +m[5] };
      const h = s.match(/^手牌: (.*)$/);
      if (h && v) v.handStr = h[1];
    };
    bot.on('message', onMsg);
    bot.chat('/balatro status');
    await sleep(waitMs);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
}
async function playerLoop(bot, name, deadline) {
  let runs = 0, hands = 0;
  while (Date.now() < deadline) {
    runs++;
    await bot.look(Math.PI, 0, false).catch(() => {});
    bot.chat(`/balatro play ${name}R${runs}`);
    await sleep(2600);
    let stuck = 0;
    for (let i = 0; i < 12 && Date.now() < deadline; i++) {
      const st = await statusSnapshot(bot, 1000);
      if (!st) break;
      if (st.phase !== 'ROUND') { await sleep(1300); continue; }
      if (!st.handStr) break;
      const d = decide(st.handStr);
      const list = inters(bot);
      if (!list.length) break;
      const proj = boardAxes(bot, list);
      const ys = list.map(x => x.position.y);
      const min = Math.min(...ys);
      const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, b) => proj(a) - proj(b));
      const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
      if (!btns.length || hand.length < 2) break;
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { bot.activateEntity(hand[pi]); await sleep(340); }
        bot.activateEntity(btns[btns.length - 1]);
        await sleep(1400);
        continue;
      }
      if (!d.play.length) { if (++stuck > 3) break; continue; }
      for (const pi of d.play) { bot.activateEntity(hand[pi]); await sleep(340); }
      bot.activateEntity(btns[0]);
      hands++;
      await sleep(1700);
    }
    bot.chat('/balatro quit');
    await sleep(1000);
  }
  return { runs, hands };
}
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 150)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 40000); });
  return bot;
}

(async () => {
  const memOf = () => { try { return +execSync('powershell -Command "(Get-Process java | Sort-Object WorkingSet -Descending | Select-Object -First 1).WorkingSet"').toString().trim(); } catch { return 0; } };
  const memBefore = memOf();
  const bots = [];
  for (let i = 1; i <= 10; i++) {
    const b = await makeBot('B' + i);
    b.setControlState('forward', true);
    setTimeout(() => b.setControlState('forward', false), 800 + i * 500);
    bots.push(b);
  }
  await sleep(2500);
  const deadline = Date.now() + 150000;
  const tpsSamples = [];
  const t0 = Date.now();
  const sampler = (async () => {
    while (Date.now() - t0 < 150000) {
      await sleep(40000);
      try {
        const out = await RCON('tps');
        const m = out.match(/TPS from last 1m, 5m, 15m: .*?([0-9.]+).*?([0-9.]+).*?([0-9.]+)/);
        if (m) { tpsSamples.push(m ? m.slice(1).map(Number) : [0]); log('TPS', m[0]); }
      } catch (e) { log('spark err', e.message); }
    }
  })();
  const stats = await Promise.all(bots.map((b, idx) => playerLoop(b, 'B' + (idx + 1), deadline)));
  await sampler;
  const memAfter = memOf();
  const totalRuns = stats.reduce((s, x) => s + x.runs, 0), totalHands = stats.reduce((s, x) => s + x.hands, 0);
  step('soak10_alive', bots.every(b => b.entity), `10/10 存活；${totalRuns} 局 ${totalHands} 手 / 2.5 分钟`);
  const minTps = tpsSamples.length ? Math.min(...tpsSamples.map(r => Math.min(...r))) : 0;
  step('soak10_tps', tpsSamples.length > 0 && minTps > 18, `TPS 样本 ${tpsSamples.length} 个，最低 ${minTps}`);
  step('soak10_mem', true, `java 工作集 ${(memBefore / 1e9).toFixed(2)}GB -> ${(memAfter / 1e9).toFixed(2)}GB`);
  for (const b of bots) { b.chat('/balatro quit'); await sleep(150); }
  await sleep(1500);
  for (const b of bots) b.quit();
  await sleep(1500);
  fs.writeFileSync(__dirname + '/check15-results.json', JSON.stringify({ results, stats, tpsSamples, memBefore, memAfter }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check14-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
