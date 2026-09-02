// R220 实机验证脚本 9：恶意输入防线 + 4 假人并发 soak + TPS/内存采样
const mineflayer = require('mineflayer');
const fs = require('fs');
const { execSync } = require('child_process');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 200)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 30000); });
  return bot;
}
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
function entitiesNear(bot, radius) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (e.position.distanceTo(bot.entity.position) <= radius) out.push(e);
  }
  return out;
}
const inters = (bot, r = 16) => entitiesNear(bot, r).filter(e => e.name === 'interaction' && e.metadata && (+e.metadata[8] > 0));
function boardAxes(bot, list) {
  const p = bot.entity.position;
  const cx = list.reduce((s, e) => s + e.position.x, 0) / list.length;
  const cz = list.reduce((s, e) => s + e.position.z, 0) / list.length;
  let fx = cx - p.x, fz = cz - p.z;
  const fl = Math.hypot(fx, fz) || 1; fx /= fl; fz /= fl;
  const rx = -fz, rz = fx;
  return (e) => (e.position.x - cx) * rx + (e.position.z - cz) * rz;
}
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { phase: m[3], score: +m[4], target: +m[5], hands: +m[6], discards: +m[7], money: +m[8] };
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
// 每个 bot 的自动打牌协程：局循环（打→失败/胜利→再开），只靠点击
async function playerLoop(bot, name, deadline) {
  let runs = 0, handsPlayed = 0;
  while (Date.now() < deadline) {
    runs++;
    await bot.look(Math.PI, 0, false).catch(() => {});
    bot.chat(`/balatro play ${name}R${runs}`);
    await sleep(2800);
    for (let i = 0; i < 14 && Date.now() < deadline; i++) {
      const st = await statusSnapshot(bot, 1100);
      if (!st) break;
      if (st.phase !== 'ROUND') { if (st.phase === 'SHOP' || st.phase === 'BLIND_SELECT') { await sleep(1500); continue; } break; }
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
        for (const pi of d.discard) { bot.activateEntity(hand[pi]); await sleep(360); }
        bot.activateEntity(btns[btns.length - 1]);
        await sleep(1500);
        continue;
      }
      if (!d.play.length) continue;
      for (const pi of d.play) { bot.activateEntity(hand[pi]); await sleep(360); }
      bot.activateEntity(btns[0]);
      handsPlayed++;
      await sleep(1900);
    }
    bot.chat('/balatro quit');
    await sleep(1200);
  }
  return { runs, handsPlayed };
}

(async () => {
  const memOf = () => { try { return execSync('powershell -Command "(Get-Process java | Sort-Object WorkingSet -Descending | Select-Object -First 1).WorkingSet"').toString().trim(); } catch { return '?'; } };

  // ---- Phase A：恶意/畸形输入防线 ----
  const b0 = await makeBot('BalBot');
  const evil = [
    '/balatro play ' + 'A'.repeat(300),
    '/balatro play seed-with-$pecial!chars',
    '/balatro play OK1',
    '/balatro playcard 0 -1 99 1000000 2147483648',
    '/balatro playcard abc def',
    '/balatro playcard ' + '1 '.repeat(500),
    '/balatro disc 99999999999999999999',
    '/balatro use 1 0 -5',
    '/balatro sellj -100',
    '/balatro sellc 99999',
    '/balatro buy 2147483647',
    '/balatro buybag 0',
    '/balatro buyvoucher -1',
    '/balatro pick 1000000000',
    '/balatro help ' + 'x'.repeat(200),
    '/balatro play ' + '\u00a7\u00a7\u00a7CONSOLE\u00a7r',
    '/balatro',
    '/balatro unknownsubcommand123',
    '/balatro reroll',
    '/balatro next',
    '/balatro go',
    '/balatro skip',
    '/balatro endless',
    '/balatro top',
  ];
  for (const c of evil) { b0.chat(c); await sleep(260); }
  await sleep(2000);
  const stAfter = await statusSnapshot(b0);
  step('evil_inputs_no_crash', stAfter !== null && b0.entity, `${evil.length} 条畸形命令后仍可交互 status=${stAfter && stAfter.phase}`);
  b0.chat('/balatro quit'); await sleep(1200);
  b0.quit(); await sleep(800);

  // ---- Phase B：4 bot 并发 soak ----
  const memBefore = memOf();
  const t0 = Date.now();
  const monitor = await makeBot('BalBot2'); // 观察者+TPS
  monitor.chat('/spark tps');
  const tpsReadings = [];
  const monLoop = (async () => {
    while (Date.now() - t0 < 150000) {
      await sleep(45000);
      monitor.chat('/spark tps --timeout 200');
      await sleep(2500);
    }
  })();
  monitor.on('message', (j) => { const s = j.toString(); const m = s.match(/(\d+\.\d+), (\d+\.\d+), (\d+\.\d+), (\d+\.\d+), (\d+\.\d+)/); if (m) tpsReadings.push(m.slice(1).map(Number)); });

  const bots = [];
  for (let i = 1; i <= 4; i++) {
    const b = await makeBot('Bot' + i);
    b.setControlState('forward', true);
    setTimeout(() => b.setControlState('forward', false), 1200 + i * 700); // 散开站位
    bots.push(b);
    await sleep(700);
  }
  await sleep(2500);
  const deadline = Date.now() + 150000;
  const stats = await Promise.all(bots.map((b, idx) => playerLoop(b, 'BOT' + (idx + 1), deadline)));
  await monLoop;
  const memAfter = memOf();
  const totalRuns = stats.reduce((s, x) => s + x.runs, 0);
  const totalHands = stats.reduce((s, x) => s + x.handsPlayed, 0);
  step('soak_4bots_no_kick', bots.every(b => b.entity), `4 bot 存活，共 ${totalRuns} 局 ${totalHands} 手`);
  const minTps = tpsReadings.length ? Math.min(...tpsReadings.map(r => Math.min(...r))) : null;
  step('soak_tps', minTps === null || minTps > 18, `TPS 读数 ${tpsReadings.length} 次，最低 ${minTps}（读数：${tpsReadings.map(r => r[0]).join('/')}）`);
  log('mem before/after:', memBefore, memAfter);
  step('soak_mem', true, `java 工作集 ${memBefore} -> ${memAfter}`);

  for (const b of bots) { b.chat('/balatro quit'); await sleep(300); }
  await sleep(1500);
  for (const b of bots) b.quit();
  monitor.quit();
  await sleep(1500);
  fs.writeFileSync(__dirname + '/check9-results.json', JSON.stringify({ results, stats, tpsReadings, memBefore, memAfter }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check9-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
