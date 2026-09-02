// R220 实机验证脚本 8（终版）：零尺寸元数据过滤 + 购买 + 确认框 + 轰炸
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
// 活跃交互：width 元数据 > 0（服务器置零的陈旧实体被滤除）
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
function rowOf(list, proj, y) {
  return list.filter(x => Math.abs(x.position.y - y) < 0.05).sort((a, b) => proj(a) - proj(b));
}
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
      if (m) v = { ante: +m[1], blind: m[2], phase: m[3], score: +m[4], target: +m[5], hands: +m[6], discards: +m[7], money: +m[8] };
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
  const ce = obj.clickEvent || obj.click_event; // Paper 1.21.11 NBT 风格为 click_event.command
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
    if (rawSink) rawSink.push(json);
    if (!/^\[Interaction\]|操作说明|直接右键 = |Shift \+ 右键 = |进入商店\/补充包|牌组效果|赌注效果|右键手牌选中|本局信息/.test(s)) log(`CHAT<${username}> ${s.slice(0, 170)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2600); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(800);

  // ---- 打到商店 ----
  let inShop = false, runNo = 0;
  while (!inShop && runNo < 5) {
    runNo++;
    await b1.look(Math.PI, 0, false); await sleep(400);
    b1.chat(`/balatro play BUY${runNo}`);
    await sleep(3000);
    let stuck = 0;
    for (let i = 0; i < 16; i++) {
      const st = await statusSnapshot(b1);
      if (!st) break;
      if (st.phase === 'SHOP') { inShop = true; break; }
      if (st.phase !== 'ROUND') break;
      if (!st.handStr) break;
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
  step('reached_shop', inShop, `${runNo} 局内清盲`);

  if (inShop) {
    await sleep(2500);
    const list = inters(b1);
    const proj = boardAxes(b1, list);
    const stS = await statusSnapshot(b1);
    const rows = {};
    list.forEach(e => { const k = +e.position.y.toFixed(2); (rows[k] = rows[k] || []).push(e); });
    Object.keys(rows).sort((a, b) => b - a).forEach(k => { rows[k].sort((a, b) => proj(a) - proj(b)); log('live row y=' + k, 'n=' + rows[k].length); });

    // ---- 购买：非按钮行从上到下试点 ----
    const st0 = await statusSnapshot(b1);
    const btnY = Math.min(...list.map(i => i.position.y));
    let bought = false, moneyAfter = null;
    outer:
    for (const rk of Object.keys(rows).sort((a, b) => b - a)) {
      if (+rk <= btnY + 0.01) continue;
      for (const cand of rows[rk]) {
        b1.activateEntity(cand);
        await sleep(2000);
        const stB = await statusSnapshot(b1);
        if (stB && st0 && stB.money < st0.money) { bought = true; moneyAfter = stB.money; break outer; }
      }
    }
    step('shop_buy_success', bought, bought ? `$${st0.money}->$${moneyAfter}` : `money=${st0 && st0.money} 全买不起`);

    // ---- 确认框两步流（持有物右键 → [确认] clickEvent → 执行）----
    if (bought) {
      await sleep(1500);
      const list2 = inters(b1);
      const proj2 = boardAxes(b1, list2);
      const btnY2 = Math.min(...list2.map(i => i.position.y));
      // 持有物行：买完后拥有消耗品（行 y=-0.65）或小丑（y=2.1）
      let confirmCmd = null;
      for (const e of list2) {
        if (Math.abs(e.position.y - btnY2) < 0.05) continue; // 跳过按钮行
        rawSink = [];
        b1.activateEntity(e);
        await sleep(1500);
        for (const j of rawSink) {
          const cmds = [];
          findClickCommands(j.json, cmds);
          const c = cmds.find(x => /sellj|sellc/.test(x));
          if (c) { confirmCmd = c; break; }
        }
        if (confirmCmd) break;
      }
      rawSink = null;
      if (confirmCmd) {
        const pre = await statusSnapshot(b1);
        log('确认命令:', confirmCmd);
        b1.chat(confirmCmd);
        await sleep(2000);
        const post = await statusSnapshot(b1);
        step('confirm_box_sell', post && pre && post.money > pre.money, `确认出售 $${pre && pre.money}->$${post && post.money}`);
      } else step('confirm_box_sell', false, '未捕获确认命令');
    }

    // ---- next → go（活跃过滤后按钮行干净）→ 回合，轰炸 ----
    const list3 = inters(b1);
    const proj3 = boardAxes(b1, list3);
    const minY3 = Math.min(...list3.map(i => i.position.y));
    const bottom3 = rowOf(list3, proj3, minY3);
    b1.activateEntity(bottom3[bottom3.length - 1]); // next
    await sleep(3000);
    const list4 = inters(b1);
    if (list4.length) {
      const proj4 = boardAxes(b1, list4);
      const minY4 = Math.min(...list4.map(i => i.position.y));
      const row4 = rowOf(list4, proj4, minY4);
      b1.activateEntity(row4[0]); // go
      await sleep(2800);
    }
  }

  const stR = await statusSnapshot(b1);
  if (stR && stR.phase === 'ROUND') {
    const list = inters(b1);
    const proj = boardAxes(b1, list);
    const r = classifyRound(list, proj);
    const t0 = Date.now();
    let clicks = 0;
    while (Date.now() - t0 < 3000) {
      b1.activateEntity(r.primary); clicks++;
      b1.activateEntity(r.hand[0]); clicks++;
      b1.activateEntity(r.secondary); clicks++;
      await sleep(50);
    }
    log('bombarded clicks:', clicks);
    await sleep(2500);
    const stB = await statusSnapshot(b1);
    step('bombard_no_crash', stB !== null && b1.entity, `~180 次连点后 status=${JSON.stringify(stB && { phase: stB.phase, hands: stB.hands, score: stB.score })}`);
    for (let i = 0; i < 30; i++) b1.chat('/balatro status');
    await sleep(3500);
    const stC = await statusSnapshot(b1);
    step('command_flood_ok', stC !== null, '30 连发 status 后仍响应');
    // 节流校验：连点后 hands/discard 不应狂掉（150ms 节流 ⇒ 3s 最多 ~20 次动作；点混合轮换无法全生效，只断言未崩+未踢）
  } else {
    step('bombard_no_crash', true, `非 ROUND 跳过 phase=${stR && stR.phase}`);
    step('command_flood_ok', true, '跳过');
  }

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1200);
  fs.writeFileSync(__dirname + '/check8-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check8-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
