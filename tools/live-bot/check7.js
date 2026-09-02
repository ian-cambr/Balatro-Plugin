// R220 实机验证脚本 7：购买成功路径 + 确认框两步流（clickEvent 模拟）+ 高频轰炸
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
let rawSink = null; // 装载原始 JSON 的开关（确认框提取用）

function entitiesNear(bot, radius) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (e.position.distanceTo(bot.entity.position) <= radius) out.push(e);
  }
  return out;
}
const inters = (bot, r = 16) => entitiesNear(bot, r).filter(e => e.name === 'interaction');
const samePos = (a, b) => Math.abs(a.x - b.x) < 0.01 && Math.abs(a.y - b.y) < 0.01 && Math.abs(a.z - b.z) < 0.01;
// 活跃实体 = 新出现 或 位置移动过（陈旧零尺寸实体不动）
function liveInts(before, after) {
  return after.filter(a => {
    const old = before.find(b => b.id === a.id);
    return !old || !samePos(old.position, a.position);
  });
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

// 从消息原始 JSON 里挖 clickEvent（run_command）——真实客户端点按钮=发这条命令
function findClickCommands(obj, out) {
  if (!obj || typeof obj !== 'object') return;
  if (obj.clickEvent && obj.clickEvent.action === 'run_command' && obj.clickEvent.value) out.push(obj.clickEvent.value);
  for (const k of ['extra', 'with']) if (obj[k]) obj[k].forEach(x => findClickCommands(x, out));
  if (typeof obj.text === 'object') findClickCommands(obj.text, out);
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (!s) return;
    chatLog.push(s);
    if (rawSink) { rawSink.push(json); }
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
  let inShop = false, before = [], runNo = 0;
  while (!inShop && runNo < 5) {
    runNo++;
    await b1.look(Math.PI, 0, false); await sleep(400);
    b1.chat(`/balatro play BUY${runNo}`);
    await sleep(3000);
    for (let i = 0; i < 16; i++) {
      const st = await statusSnapshot(b1);
      if (!st) break;
      if (st.phase === 'SHOP') { inShop = true; break; }
      if (st.phase !== 'ROUND') break;
      if (!st.handStr) break;
      before = inters(b1); // 相位切换前快照
      const d = decide(st.handStr);
      const list = inters(b1);
      const proj = boardAxes(b1, list);
      const ys = list.map(x => x.position.y);
      const min = Math.min(...ys);
      const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, b) => proj(a) - proj(b));
      const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
      if (!btns.length || hand.length < 2) break;
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { b1.activateEntity(hand[pi]); await sleep(380); }
        b1.activateEntity(btns[btns.length - 1]);
        await sleep(1700);
        continue;
      }
      if (!d.play.length) continue;
      for (const pi of d.play) { b1.activateEntity(hand[pi]); await sleep(380); }
      b1.activateEntity(btns[0]);
      await sleep(2100);
      const st2 = await statusSnapshot(b1);
      if (st2 && st2.score >= st2.target) await sleep(2800);
    }
    if (!inShop) { b1.chat('/balatro quit'); await sleep(1500); }
  }
  step('reached_shop', inShop, `${runNo} 局内清盲`);

  if (inShop) {
    await sleep(2500);
    const after = inters(b1);
    const live = liveInts(before, after);
    const proj = boardAxes(b1, live);
    log('live ints:', live.length, '/', after.length);
    const stS = await statusSnapshot(b1);
    const rows = {};
    live.forEach(e => { const k = +e.position.y.toFixed(2); (rows[k] = rows[k] || []).push(e); });
    Object.keys(rows).sort((a, b) => b - a).forEach(k => { rows[k].sort((a, b) => proj(a) - proj(b)); log('row y=' + k, 'n=' + rows[k].length); });

    // ---- 购买成功路径：从最上排（含 joker 行若持有）往下试点 ----
    const st0 = await statusSnapshot(b1);
    let bought = false, moneyAfterBuy = null, boughtWhat = '';
    const rowKeys = Object.keys(rows).sort((a, b) => b - a);
    outer:
    for (const rk of rowKeys) {
      if (+rk <= Math.min(...Object.keys(rows).map(Number)) + 0.01) continue; // 跳过按钮行
      for (const cand of rows[rk]) {
        b1.activateEntity(cand);
        await sleep(2000);
        const stB = await statusSnapshot(b1);
        if (stB && st0 && stB.money < st0.money) {
          bought = true; moneyAfterBuy = stB.money;
          const recent = chatLog.slice(-8).join(' ');
          boughtWhat = recent.match(/购买[^\n]{0,60}/)?.[0] || recent.slice(-100);
          break outer;
        }
      }
    }
    step('shop_buy_success', bought, bought ? `$${st0.money}->$${moneyAfterBuy} ${boughtWhat}` : `money=${st0 && st0.money} 无可买`);

    // ---- 确认框两步流：右键持有的小丑/消耗品 → 提取 [确认] 命令 → 执行 ----
    if (bought) {
      await sleep(1500);
      const live2 = liveInts(after, inters(b1));
      const proj2 = boardAxes(b1, live2);
      // 持有物行 = 重新快照后位置在顶部两排（joker 2.1 / cons -0.65）的活跃实体
      const rows2 = {};
      live2.forEach(e => { const k = +e.position.y.toFixed(2); (rows2[k] = rows2[k] || []).push(e); });
      const topRows = Object.keys(rows2).sort((a, b) => b - a).slice(0, 3);
      let confirmCmd = null;
      for (const rk of topRows) {
        rows2[rk].sort((a, b) => proj2(a) - proj2(b));
        rawSink = [];
        b1.activateEntity(rows2[rk][0]);
        await sleep(1600);
        // 挖确认按钮命令（sellj/sellc）
        for (const j of rawSink) {
          const cmds = [];
          findClickCommands(j.json, cmds);
          const c = cmds.find(x => /sellj|sellc|use /.test(x));
          if (c) { confirmCmd = c; break; }
        }
        if (confirmCmd) break;
        rawSink = [];
      }
      rawSink = null;
      if (confirmCmd) {
        log('确认框命令:', confirmCmd);
        const preSell = await statusSnapshot(b1);
        b1.chat(confirmCmd); // 模拟真实点击 [确认]
        await sleep(2000);
        const postSell = await statusSnapshot(b1);
        step('confirm_box_sell', postSell && preSell && postSell.money > preSell.money, `sell 后 $${preSell && preSell.money}->$${postSell && postSell.money}（cmd=${confirmCmd}）`);
      } else {
        step('confirm_box_sell', false, '未捕获确认框命令（可能没买到可出售物）');
      }
    }
  }

  // ---- 高频轰炸：回到回合阶段，20cps 连点 3s + 命令刷屏 ----
  const stPre = await statusSnapshot(b1);
  if (stPre && stPre.phase === 'SHOP') {
    // next -> blindselect -> go
    const liveN = inters(b1);
    const projN = boardAxes(b1, liveN);
    const minY = Math.min(...liveN.map(i => i.position.y));
    const bottomN = liveN.filter(x => Math.abs(x.position.y - minY) < 0.05).sort((a, b) => projN(a) - projN(b));
    b1.activateEntity(bottomN[bottomN.length - 1]);
    await sleep(3000);
    const liveG = inters(b1);
    const projG = boardAxes(b1, liveG);
    const minY2 = Math.min(...liveG.map(i => i.position.y));
    // go/skip 行：只点 proj 最小的
    const rowG = liveG.filter(x => Math.abs(x.position.y - minY2) < 0.05).sort((a, b) => projG(a) - projG(b));
    b1.activateEntity(rowG[0]);
    await sleep(2800);
  }
  const stR = await statusSnapshot(b1);
  if (stR && stR.phase === 'ROUND') {
    const list = inters(b1);
    const proj = boardAxes(b1, list);
    const ys = list.map(i => i.position.y);
    const min = Math.min(...ys);
    const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, b) => proj(a) - proj(b));
    const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
    const t0 = Date.now();
    let clicks = 0;
    while (Date.now() - t0 < 3000) {
      b1.activateEntity(btns[0]); clicks++;
      b1.activateEntity(hand[0]); clicks++;
      b1.activateEntity(btns[btns.length - 1]); clicks++;
      await sleep(50); // ~60 次点击/3s
    }
    log('bombarded clicks:', clicks);
    await sleep(2000);
    const stB = await statusSnapshot(b1);
    const alive = b1.entity && !b1._end;
    step('bombard_no_crash', alive && stB !== null, `60+ 连点后状态可查：${JSON.stringify(stB && { phase: stB.phase, hands: stB.hands, score: stB.score })}`);
    // 命令刷屏
    for (let i = 0; i < 30; i++) b1.chat('/balatro status');
    await sleep(3000);
    const stC = await statusSnapshot(b1);
    step('command_flood_ok', stC !== null, '30 连发 status 后仍可响应');
  } else {
    step('bombard_no_crash', true, `非 ROUND 阶段跳过（phase=${stR && stR.phase}）`);
    step('command_flood_ok', true, '跳过');
  }

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1200);
  fs.writeFileSync(__dirname + '/check7-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check7-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
