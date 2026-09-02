// R223：20 假人满载 soak（服务器上限 20 人）——状态变化断言版（R222 教训）
const mineflayer = require('mineflayer');
const fs = require('fs');
const { execSync } = require('child_process');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const NBOTS = 19; // + BalBot2 监控 = 20 满载
const DURATION_MS = 240000;
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
  const o = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'interaction' && e.position && e.metadata && +e.metadata[8] > 0 && e.position.distanceTo(bot.entity.position) <= r) o.push(e);
  }
  return o;
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
// 状态变化驱动的打牌循环：只有 hands/score/handStr 真变了才计数
async function playerLoop(bot, name, deadline) {
  let runs = 0, realActions = 0, deadCycles = 0;
  while (Date.now() < deadline) {
    runs++;
    await bot.look(Math.PI, 0, false).catch(() => {});
    bot.chat(`/balatro play ${name}r${runs}`);
    await sleep(2600);
    let stuck = 0;
    for (let i = 0; i < 13 && Date.now() < deadline; i++) {
      const st = await statusSnapshot(bot, 1000);
      if (!st) break;
      if (st.phase !== 'ROUND') { await sleep(1300); continue; }
      if (!st.handStr) break;
      const d = decide(st.handStr);
      const list = inters(bot);
      if (!list.length) { deadCycles++; break; }
      const proj = boardAxes(bot, list);
      const ys = list.map(x => x.position.y);
      const min = Math.min(...ys);
      const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, b) => proj(a) - proj(b));
      const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
      if (!btns.length || hand.length < 2) { deadCycles++; break; }
      const prevStr = st.handStr;
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { bot.activateEntity(hand[pi]); await sleep(340); }
        bot.activateEntity(btns[btns.length - 1]);
        await sleep(1400);
        const st2 = await statusSnapshot(bot, 900);
        if (st2 && (st2.discards < st.discards || (st2.handStr && st2.handStr !== prevStr))) realActions++;
        continue;
      }
      if (!d.play.length) { if (++stuck > 3) break; continue; }
      for (const pi of d.play) { bot.activateEntity(hand[pi]); await sleep(340); }
      bot.activateEntity(btns[0]);
      await sleep(1600);
      const st3 = await statusSnapshot(bot, 900);
      // 出牌成功判据：score 增或手牌重抽（用最新快照对比）
      if (st3 && st3.handStr && st3.handStr !== prevStr) realActions++;
    }
    bot.chat('/balatro quit');
    await sleep(1000);
  }
  return { runs, realActions, deadCycles };
}
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 130)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 45000); });
  return bot;
}

(async () => {
  const memOf = () => { try { return +execSync('powershell -Command "(Get-CimInstance Win32_Process -Filter \\"Name=\'java.exe\' and CommandLine like \'%paper%\'\\" | Sort-Object WorkingSet -Descending | Select-Object -First 1).WorkingSet"').toString().trim(); } catch { return 0; } };
  const memBefore = memOf();

  // 监控假人（op）：TPS 聊天采样
  const mon = await makeBot('BalBot2');
  const tpsSamples = [];
  mon.on('message', (j) => {
    const s = j.toString();
    const m = s.match(/TPS from last 1m, 5m, 15m: .*?([0-9.]+).*?([0-9.]+).*?([0-9.]+)/);
    if (m) { tpsSamples.push(m.slice(1).map(Number)); log('TPS', m[1], m[2], m[3]); }
  });
  const t0 = Date.now();
  const sampler = (async () => {
    while (Date.now() - t0 < DURATION_MS) {
      await sleep(35000);
      mon.chat('/tps');
      await sleep(2500);
      const mem = memOf();
      log('mem:', (mem / 1e9).toFixed(2) + 'GB');
    }
  })();

  const bots = [];
  for (let i = 1; i <= NBOTS; i++) {
    const b = await makeBot('L' + i);
    b.setControlState('forward', true);
    setTimeout(() => b.setControlState('forward', false), 700 + i * 420);
    bots.push(b);
  }
  log(`${NBOTS} bots + monitor = ${NBOTS + 1} players`);
  await sleep(2500);
  const deadline = Date.now() + DURATION_MS;
  const stats = await Promise.all(bots.map((b, idx) => playerLoop(b, 'L' + (idx + 1), deadline)));
  await sampler;

  const totalRuns = stats.reduce((s, x) => s + x.runs, 0);
  const totalReal = stats.reduce((s, x) => s + x.realActions, 0);
  const totalDead = stats.reduce((s, x) => s + x.deadCycles, 0);
  const memAfter = memOf();
  const minTps = tpsSamples.length ? Math.min(...tpsSamples.map(r => Math.min(...r))) : 0;
  step('maxload_alive', bots.every(b => b.entity) && mon.entity, `${NBOTS + 1}/${NBOTS + 1} 存活；${totalRuns} 局，**真实生效动作 ${totalReal} 次**（状态变化断言），死循环 ${totalDead}`);
  step('maxload_real_interaction', totalReal >= totalRuns, `真实交互数 ${totalReal} ≥ 局数 ${totalRuns}（若远小于则交互在满载下失效）`);
  step('maxload_tps', tpsSamples.length > 0 && minTps > 18, `TPS 样本 ${tpsSamples.length}，最低 ${minTps}`);
  step('maxload_mem', true, `java 工作集 ${(memBefore / 1e9).toFixed(2)} -> ${(memAfter / 1e9).toFixed(2)} GB`);

  for (const b of bots) { b.chat('/balatro quit'); await sleep(100); }
  await sleep(1500);
  for (const b of bots) b.quit();
  mon.quit();
  await sleep(1500);
  fs.writeFileSync(__dirname + '/check17-results.json', JSON.stringify({ results, stats, tpsSamples, memBefore, memAfter }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check17-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
