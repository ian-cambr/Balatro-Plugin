// R225 实机验证脚本 21b：商店内右键持有星球 → 确认框双按钮（[确认出售]+[确认使用]）→ 商店内使用成功
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}
const chatLog = [];
let rawSink = null;

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
function rowOf(list, proj, y) { return list.filter(x => Math.abs(x.position.y - y) < 0.05).sort((a, b) => proj(a) - proj(b)); }
function classifyRound(list, proj) {
  const ys = list.map(i => i.position.y);
  const min = Math.min(...ys);
  const btns = rowOf(list, proj, min);
  const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
  return { hand, primary: btns[0], secondary: btns[btns.length - 1] };
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
function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { money: +m[8], phase: m[3], score: +m[4], target: +m[5] };
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
function findClickCommands(obj, out) {
  if (!obj || typeof obj !== 'object') return;
  const ce = obj.clickEvent || obj.click_event;
  if (ce) {
    const cmd = ce.value || ce.command;
    if (cmd && ce.action === 'run_command') out.push(cmd);
  }
  for (const k of ['extra', 'with']) if (obj[k]) obj[k].forEach(x => findClickCommands(x, out));
}
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (!s) return;
    chatLog.push(s);
    if (rawSink) rawSink.push(json.json ? json.json : json);
    if (!/^\[Interaction\]|操作说明|直接右键 = |Shift \+ 右键 = |进入商店\/补充包|牌组效果|赌注效果|右键手牌选中|本局信息/.test(s)) log(`CHAT<${username}> ${s.slice(0, 170)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2600); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(800);

  let done = false;
  for (let attempt = 1; attempt <= 12 && !done; attempt++) {
    let inShop = false, runNo = 0;
    while (!inShop && runNo < 4) {
      runNo++;
      await b1.look(Math.PI, 0, false); await sleep(400);
      b1.chat(`/balatro play L21B${Date.now() % 1000000}_${runNo}`);
      await sleep(3000);
      let stuck = 0;
      for (let i = 0; i < 16; i++) {
        const st = await statusSnapshot(b1);
        if (!st) break;
        if (st.phase === 'SHOP') { inShop = true; break; }
        if (st.phase !== 'ROUND' || !st.handStr) break;
        const d = decide(st.handStr);
        const list = inters(b1);
        if (!list.length) break;
        const proj = boardAxes(b1, list);
        const r = classifyRound(list, proj);
        if (!r.primary || r.hand.length < 2) break;
        if (st.discards > 0 && d.discard.length >= 2) {
          for (const pi of d.discard) { b1.activateEntity(r.hand[pi]); await sleep(380); }
          b1.activateEntity(r.secondary);
          await sleep(1700);
          continue;
        }
        if (!d.play.length) { if (++stuck > 3) break; continue; }
        for (const pi of d.play) { b1.activateEntity(r.hand[pi]); await sleep(380); }
        b1.activateEntity(r.primary);
        await sleep(2100);
        const st2 = await statusSnapshot(b1);
        if (st2 && st2.score >= st2.target) await sleep(2800);
      }
      if (!inShop) { b1.chat('/balatro quit'); await sleep(1500); }
    }
    if (!inShop) continue;
    await sleep(2200);

    // 找星球
    const lines = [];
    {
      const onMsg = (json) => lines.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro shop');
      await sleep(1800);
      b1.removeListener('message', onMsg);
    }
    const NO_TARGET = /^\[\d+\] (planet|tarot (隐者|节制|女祭司|皇帝|审判|命运之轮))/;const pm = lines.find(l => NO_TARGET.test(l));
    if (!pm) { log(`attempt ${attempt}: 商店无星球/非目标塔罗`); b1.chat('/balatro quit'); await sleep(1500); continue; }
    const idx = +pm.match(/^\[(\d+)\]/)[1];
    const price = +(pm.match(/\$(\d+)/) || [0, 99])[1];
    const st = await statusSnapshot(b1);
    if (!st || st.money < price) { log('星球买不起'); b1.chat('/balatro quit'); await sleep(1500); continue; }
    step('bought_planet', true, pm.slice(0, 60));
    b1.chat(`/balatro buy ${idx}`);
    await sleep(2000);

    // 商店内右键持有的星球（held cons 行：锚点 -0.65，脚底 ≈ -0.79）
    const list = inters(b1);
    const proj = boardAxes(b1, list);
    // 板面世界高度不定：以最上行（商品行脚底，锚点1.2-hh0.2）为基准，持有消耗品行 = 基准-1.79
    const topY = Math.max(...list.map(e => e.position.y));
    const consY = topY - 1.79;
    const consRow = list.filter(e => Math.abs(e.position.y - consY) < 0.09).sort((a, b) => proj(a) - proj(b));
    log('rows topY=', topY.toFixed(2), 'consY=', consY.toFixed(2), 'n=', consRow.length);
    step('held_cons_row', consRow.length >= 1, `${consRow.length} 个`);
    if (!consRow.length) { b1.chat('/balatro quit'); await sleep(1200); continue; }
    rawSink = [];
    b1.activateEntity(consRow[consRow.length - 1]);
    await sleep(1600);
    let sellCmd = null, useCmd = null;
    for (const j of rawSink) {
      const cmds = [];
      findClickCommands(j, cmds);
      for (const c of cmds) {
        if (/^\/balatro sellc /.test(c)) sellCmd = c;
        if (/^\/balatro use /.test(c)) useCmd = c;
      }
    }
    rawSink = null;
    step('shop_dual_buttons', !!sellCmd && !!useCmd, `sell=${!!sellCmd} use=${!!useCmd}`);
    if (useCmd) {
      const before = chatLog.length;
      b1.chat(useCmd);
      await sleep(2200);
      const w = chatLog.slice(before).join('\n');
      step('shop_use_works', w.includes('使用成功'), w.slice(-140));
    }
    done = true;
  }
  step('planet_shop_flow_done', done, done ? '完成' : '12 次尝试未遇到星球/非目标塔罗');

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check21b-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check21b-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
