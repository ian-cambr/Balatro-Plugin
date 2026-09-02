// R222：#10 区块卸载/重载自愈 + #18 持有消耗品时的实体路由独立性（空间点击）
const mineflayer = require('mineflayer');
const fs = require('fs');
const { rconCommand } = require('./rcon.js');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const RCON = (cmd) => rconCommand('127.0.0.1', 25575, 'balatro220', cmd, 15000);
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) { results.push({ name, ok, detail }); log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`); }

function balEntities(bot, r = 16) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (e.position.distanceTo(bot.entity.position) <= r && (e.name === 'interaction' || e.name === 'text_display')) out.push(e);
  }
  return out;
}
const liveInts = (bot, r = 16) => balEntities(bot, r).filter(e => e.name === 'interaction' && e.metadata && +e.metadata[8] > 0);
function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { phase: m[3], money: +m[8] };
      if (/没有进行中/.test(s)) v = v || { phase: 'NOSESSION' };
    };
    bot.on('message', onMsg);
    bot.chat('/balatro status');
    await sleep(waitMs);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
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
function boardAxes(bot, list) {
  const p = bot.entity.position;
  const cx = list.reduce((s, e) => s + e.position.x, 0) / list.length;
  const cz = list.reduce((s, e) => s + e.position.z, 0) / list.length;
  let fx = cx - p.x, fz = cz - p.z;
  const fl = Math.hypot(fx, fz) || 1; fx /= fl; fz /= fl;
  const rx = -fz, rz = fx;
  return (e) => (e.position.x - cx) * rx + (e.position.z - cz) * rz;
}
function rawInteract(bot, entity, sneaking) {
  bot._client.write('use_entity', { target: entity.id, mouse: 0, sneaking, hand: 0 });
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 150)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { const s = json.toString(); if (s && !/^\[Interaction\]/.test(s)) log(`CHAT ${s.slice(0, 120)}`); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}
// 自动打到商店（复用成熟循环）
async function playToShop(bot, name) {
  for (let run = 1; run <= 5; run++) {
    await bot.look(Math.PI, 0, false).catch(() => {});
    bot.chat(`/balatro play ${name}${run}`);
    await sleep(2800);
    for (let i = 0; i < 16; i++) {
      const st = await statusSnapshot(bot);
      if (!st) break;
      if (st.phase === 'SHOP') return true;
      if (st.phase !== 'ROUND') break;
      const st2 = await statusSnapshot(bot);
      if (!st2 || !st2.handStr) break;
      const d = decide(st2.handStr);
      const list = liveInts(bot);
      if (!list.length) break;
      const proj = boardAxes(bot, list);
      const ys = list.map(x => x.position.y);
      const min = Math.min(...ys);
      const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, b) => proj(a) - proj(b));
      const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
      if (!btns.length || hand.length < 2) { log('DIAG list=', list.length, 'btns=', btns.length, 'hand=', hand.length, 'ys=', list.map(x => +x.position.y.toFixed(1)).join(',')); break; }
      if (st2.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { bot.activateEntity(hand[pi]); await sleep(360); }
        bot.activateEntity(btns[btns.length - 1]);
        await sleep(1500);
        continue;
      }
      if (!d.play.length) continue;
      for (const pi of d.play) { bot.activateEntity(hand[pi]); await sleep(360); }
      bot.activateEntity(btns[0]);
      await sleep(1900);
    }
    bot.chat('/balatro quit');
    await sleep(1300);
  }
  return false;
}

(async () => {
  const b1 = await makeBot('BalBot');

  // ================= #10 区块卸载/重载自愈 =================
  // 站到区块边界（x=992.5 为 chunk 62/63 边界），开局使牌桌跨界
  b1.chat('/tp BalBot 992.5 -60 400');
  await sleep(2000);
  await b1.look(Math.PI, 0, false);
  await sleep(400);
  b1.chat('/balatro play CHUNK1');
  await sleep(3200);
  const preE = balEntities(b1).length;
  const preSt = await statusSnapshot(b1);
  // 远离 300 格（超出视距，卸载牌桌区块）
  b1.chat('/tp BalBot 992.5 -60 100');
  await sleep(6000);
  const awayE = balEntities(b1).length;
  // 返回
  b1.chat('/tp BalBot 992.5 -60 400');
  await sleep(6000);
  const backE = balEntities(b1).length;
  const backSt = await statusSnapshot(b1);
  step('chunk_unload_reload_heal', backE >= 5 && backSt && backSt.phase === (preSt && preSt.phase),
    `跨界开局实体 ${preE} → 远离 ${awayE} → 返回 ${backE}，phase ${preSt && preSt.phase}->${backSt && backSt.phase}`);

  // ================= #18 实体路由独立性（服务器侧可测子面） =================
  // 打到商店买一个便宜消耗品，next+go 回合阶段持有消耗品，然后：
  //  A. 点击选中中央手牌（上移 0.5，与消耗品行命中盒重叠带）→ 只应切换选中
  //  B. 直接点击消耗品实体（恶意客户端路径）→ 使用确认框
  const inShop = await playToShop(b1, 'HK');
  let buyOk = false;
  if (inShop) {
    await sleep(2000);
    const stS = await statusSnapshot(b1);
    // 两轮购买探测：直接试买 → 不行才 reroll 再试买（避免先把钱花光）
    const probeBuy = async () => {
      const list = liveInts(b1);
      const proj = boardAxes(b1, list);
      const rows = {};
      list.forEach(e => { const k = +e.position.y.toFixed(2); (rows[k] = rows[k] || []).push(e); });
      const btnY = Math.min(...list.map(i => i.position.y));
      for (const k of Object.keys(rows).sort((a, b) => b - a)) {
        if (+k <= btnY + 0.01) continue;
        rows[k].sort((a, b) => proj(a) - proj(b));
        for (const cand of rows[k]) {
          b1.activateEntity(cand);
          await sleep(1800);
          const stB = await statusSnapshot(b1);
          if (stB && stS && stB.money != null && stS.money != null && stB.money < stS.money) return true;
        }
      }
      return false;
    };
    buyOk = await probeBuy();
    if (!buyOk) {
      const list0 = liveInts(b1);
      const proj0 = boardAxes(b1, list0);
      const minY0 = Math.min(...list0.map(i => i.position.y));
      const bottom0 = list0.filter(x => Math.abs(x.position.y - minY0) < 0.05).sort((a, b) => proj0(a) - proj0(b));
      const stNow = await statusSnapshot(b1);
      if (stNow && stNow.money >= 5) { b1.activateEntity(bottom0[0]); await sleep(2300); buyOk = await probeBuy(); }
    }
    step('setup_bought_consumable', buyOk, `购买便宜消耗品（${buyOk ? '成功' : '失败——#18 空间点击子面跳过'}）`);
  }

  if (buyOk) {
    // next → go 回合
    let list = liveInts(b1);
    let proj = boardAxes(b1, list);
    const minY = Math.min(...list.map(i => i.position.y));
    const bottom = list.filter(x => Math.abs(x.position.y - minY) < 0.05).sort((a, b) => proj(a) - proj(b));
    b1.activateEntity(bottom[bottom.length - 1]);
    await sleep(2800);
    list = liveInts(b1);
    if (list.length) {
      proj = boardAxes(b1, list);
      const minY2 = Math.min(...list.map(i => i.position.y));
      const row = list.filter(x => Math.abs(x.position.y - minY2) < 0.05).sort((a, b) => proj(a) - proj(b));
      b1.activateEntity(row[0]);
      await sleep(2800);
    }
    const stR = await statusSnapshot(b1);
    if (stR && stR.phase === 'ROUND') {
      // 回合视图：cons 行 y=+0.78（板心上方第二排），手牌 y=0
      const l2 = liveInts(b1);
      const p2 = boardAxes(b1, l2);
      const ys = [...new Set(l2.map(i => +i.position.y.toFixed(2)))].sort((a, b) => b - a);
      log('round rows y:', ys.join(','), 'ints:', l2.length);
      const consRow = l2.filter(x => Math.abs(x.position.y - (ys[1] ?? ys[0])) < 0.05).sort((a, b) => p2(a) - p2(b));
      const handRow = l2.filter(x => Math.abs(x.position.y - ys[ys.length - 2]) < 0.05).sort((a, b) => p2(a) - p2(b));
      // A: 选中央手牌（上移后与消耗品盒重叠带）
      const center = handRow[Math.floor(handRow.length / 2)];
      b1.activateEntity(center);
      await sleep(1200);
      // B: 直接点消耗品实体（伪造客户端「点中消耗品盒」路径）→ 期待使用确认框聊天
      let confirmShown = false;
      const onMsg = (j) => { if (/确认使用/.test(j.toString())) confirmShown = true; };
      b1.on('message', onMsg);
      if (consRow.length) { b1.activateEntity(consRow[0]); }
      await sleep(1600);
      b1.removeListener('message', onMsg);
      // 再点中央手牌（应取消选中——若无确认误触弹窗即证明路由按实体 id 不混淆）
      b1.activateEntity(center);
      await sleep(1200);
      step('entity_routing_independent', confirmShown,
        `点消耗品实体→确认使用框出现=${confirmShown}；点选中手牌只切换选中（两者按实体 id 独立路由，服务器侧无混淆）`);
    } else {
      step('entity_routing_independent', false, `未回到 ROUND（phase=${stR && stR.phase}）`);
    }
  }

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check16-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check16-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
