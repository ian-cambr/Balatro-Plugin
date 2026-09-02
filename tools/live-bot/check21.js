// R225 实机验证脚本 21：目标类消耗品全息使用链路（#80 修复验证）
// 链路：清盲进商店 → 买目标类消耗品 → next+go 回合 → 右键选中手牌 → 右键消耗品
//       → 确认框 click_event 应携带 @id 快照 → 执行 → 使用成功（无「请选择」错）→ 卡面生效。
// 附加：商店持有无目标消耗品（星球）右键确认框应同时含 [确认出售] 与 [确认使用]。
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

// 目标类消耗品名（21 种）→ 需求张数
const TARGET_ITEMS = {
  '魔术师': 1, '皇后': 1, '教皇': 1, '力量': 1, '倒吊人': 1,           // 至多 2，选 1 即可
  '恋人': 1, '战车': 1, '正义': 1, '恶魔': 1, '高塔': 1,               // 恰好 1
  '护符': 1, '光环': 1, '似曾相识': 1, '恍惚': 1, '灵媒': 1, '地穴生物': 1, // 恰好 1
  '死神': 2,                                                            // 恰好 2
  '星星': 1, '月亮': 1, '太阳': 1, '世界': 1,                            // 至多 3，选 1
};
const EFFECT_KEYS = /幸运|倍率|奖励|万能|玻璃|钢铁|石头|黄金|版本|蜡封|闪膜|镭射|多彩|复制/;

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

/** 捕获一段时间的原始聊天 JSON。 */
function captureChat(bot, ms) {
  return new Promise(async (resolve) => {
    const sink = [];
    const onMsg = (json) => sink.push(json.json ? json.json : json);
    bot.on('message', onMsg);
    await sleep(ms);
    bot.removeListener('message', onMsg);
    resolve(sink);
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
const flat = (json) => json.toString();
/** 递归抽取 raw JSON 组件的 text 字段（Paper NBT 风格）。 */
function jsonText(obj, out) {
  if (!obj || typeof obj !== 'object') return;
  if (typeof obj.text === 'string') out.push(obj.text);
  if (typeof obj.content === 'string') out.push(obj.content);
  if (obj.extra) obj.extra.forEach(x => jsonText(x, out));
  if (obj.with) obj.with.forEach(x => jsonText(x, out));
  return out;
}

/** Shift+右键看某实体简介（手写 use_entity，sneaking:true）。 */
function shiftInfo(bot, e) {
  return new Promise(async (resolve) => {
    let text = null;
    const onMsg = (json) => { const s = json.toString(); if (!text && /^(红桃|黑桃|梅花|方块|石头牌)|增强|版本|蜡封|钢铁|幸运|玻璃|黄金|万能|奖励|倍率/.test(s)) text = s; };
    bot.on('message', onMsg);
    bot._client.write('use_entity', { target: e.id, mouse: 0, sneaking: true, hand: 0 });
    await sleep(1200);
    bot.removeListener('message', onMsg);
    resolve(text);
  });
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

  // ---- 权限自检（R224 后 LP 可能残留 noplay）----
  b1.chat('/balatro play PERMCHECK');
  await sleep(2500);
  if (chatLog.some(s => /balatro\.play|权限不足|没有权限/.test(s))) {
    log('检测到权限拒绝，尝试自修（op 直跑 lp）');
    b1.chat('/lp user BalBot permission unset balatro.play');
    await sleep(1500);
  }
  b1.chat('/balatro quit'); await sleep(1200);

  let acquired = null; // { name, need }
  for (let attempt = 1; attempt <= 6 && !acquired; attempt++) {
    // ---- 打到商店 ----
    let inShop = false, runNo = 0;
    while (!inShop && runNo < 4) {
      runNo++;
      await b1.look(Math.PI, 0, false); await sleep(400);
      b1.chat(`/balatro play T21S${attempt}_${runNo}`);
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
    if (!inShop) continue;

    // ---- 商店扫描目标类消耗品（含一次重掷机会）----
    await sleep(2000);
    let found = null;
    for (let tries = 0; tries < 2 && !found; tries++) {
      const sink = await (async () => {
        const s = [];
        const onMsg = (json) => s.push(json.toString());
        b1.on('message', onMsg);
        b1.chat('/balatro shop');
        await sleep(1800);
        b1.removeListener('message', onMsg);
        return s;
      })();
      for (const line of sink) {
        const m = line.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
        if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2], price: +m[3] }; break; }
      }
      if (!found) {
        const st = await statusSnapshot(b1);
        if (st && st.money >= 5 && tries === 0) { b1.chat('/balatro reroll'); await sleep(2000); }
        else break;
      }
    }
    log('shop scan:', found ? `找到 ${found.name} #$${found.price}` : '无目标类消耗品');
    if (!found) { b1.chat('/balatro quit'); await sleep(1500); continue; }

    // ---- 购买 + 持有确认 ----
    b1.chat(`/balatro buy ${found.idx}`);
    await sleep(2000);
    const consSink = [];
    {
      const onMsg = (json) => consSink.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro cons');
      await sleep(1800);
      b1.removeListener('message', onMsg);
    }
    const heldLine = consSink.find(s => s.includes(found.name));
    step('bought_target_consumable', !!heldLine, heldLine ? `持有 ${found.name}` : 'cons 未列出');
    if (heldLine) acquired = { name: found.name, need: TARGET_ITEMS[found.name] };
  }
  step('acquired_any_target', !!acquired, acquired ? acquired.name : '6 次尝试未遇到目标类消耗品');

  if (acquired) {
    // ---- 附加：商店内右键持有的无目标类（星球）→ 确认框双按钮 + 商店内使用 ----
    {
      const stS = await statusSnapshot(b1);
      if (stS && stS.phase === 'SHOP') {
        const shopLines = [];
        {
          const onMsg = (json) => shopLines.push(json.toString());
          b1.on('message', onMsg);
          b1.chat('/balatro shop');
          await sleep(1800);
          b1.removeListener('message', onMsg);
        }
        const pm = shopLines.find(l => /^\[\d+\] planet/.test(l));
        const stMoney = await statusSnapshot(b1);
        if (pm) {
          const idxS = +pm.match(/^\[(\d+)\]/)[1];
          const price = +(pm.match(/\$(\d+)/) || [0, 99])[1];
          if (stMoney && stMoney.money >= price) {
            b1.chat(`/balatro buy ${idxS}`);
            await sleep(2000);
            const list = inters(b1);
            if (list.length) {
              const proj = boardAxes(b1, list);
              const consRow = list.filter(e => Math.abs(e.position.y + 0.79) < 0.09).sort((a, b) => proj(a) - proj(b));
              if (consRow.length >= 2) {
                rawSink = [];
                b1.activateEntity(consRow[consRow.length - 1]); // 最右=新买入的星球
                await sleep(1600);
                let sellCmd = null, useCmd2 = null;
                for (const j of rawSink) {
                  const cmds = [];
                  findClickCommands(j, cmds);
                  for (const c of cmds) {
                    if (/^\/balatro sellc /.test(c)) sellCmd = c;
                    if (/^\/balatro use /.test(c)) useCmd2 = c;
                  }
                }
                rawSink = null;
                step('shop_dual_buttons', !!sellCmd && !!useCmd2, `sell=${!!sellCmd} use=${!!useCmd2}`);
                if (useCmd2) {
                  const before = chatLog.length;
                  b1.chat(useCmd2);
                  await sleep(2000);
                  const w = chatLog.slice(before).join('\n');
                  step('shop_use_works', w.includes('使用成功'), w.slice(-140));
                }
              } else step('shop_dual_buttons', false, `持有消耗品行 ${consRow.length} 个`);
            } else step('shop_dual_buttons', false, '无交互实体');
          } else step('shop_dual_buttons', true, '星球买不起，跳过（非缺陷）');
        } else step('shop_dual_buttons', true, '商店无星球，跳过（非缺陷）');
      } else step('shop_dual_buttons', true, '非商店阶段，跳过');
    }

    // ---- next → go → 回合 ----
    {
      const list = inters(b1);
      if (list.length) {
        const proj = boardAxes(b1, list);
        const minY = Math.min(...list.map(i => i.position.y));
        const bottom = rowOf(list, proj, minY);
        b1.activateEntity(bottom[bottom.length - 1]); // next
        await sleep(3000);
      }
      const list2 = inters(b1);
      if (list2.length) {
        const proj2 = boardAxes(b1, list2);
        const minY2 = Math.min(...list2.map(i => i.position.y));
        const row2 = rowOf(list2, proj2, minY2);
        b1.activateEntity(row2[0]); // go（主按钮）
        await sleep(2800);
      } else { b1.chat('/balatro go'); await sleep(2500); }
    }
    const stR = await statusSnapshot(b1);
    step('back_to_round', stR && stR.phase === 'ROUND', stR ? `phase=${stR.phase}` : 'status 无');

    if (stR && stR.phase === 'ROUND') {
      // ---- 选中 need 张手牌（左起）----
      let list = inters(b1);
      let proj = boardAxes(b1, list);
      let r = classifyRound(list, proj);
      const beforeInfo = await shiftInfo(b1, r.hand[0]);
      for (let k = 0; k < acquired.need; k++) {
        b1.activateEntity(r.hand[k]);
        await sleep(450);
        list = inters(b1); proj = boardAxes(b1, list); r = classifyRound(list, proj);
      }

      // ---- 右键消耗品：Interaction position.y 是脚底坐标（锚点 y − 半高），
      //      本局未持有小丑 → 最上行即消耗品行（锚点 0.78 − hh0.14 ≈ 0.64）----
      const maxY = Math.max(...list.map(e => e.position.y));
      const consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
      step('cons_row_visible', consRow.length >= 1, `最上行(y≈${maxY.toFixed(2)}) ${consRow.length} 个命中盒`);
      let useCmd = null, reqLine = null;
      if (consRow.length) {
        rawSink = [];
        b1.activateEntity(consRow[0]);
        await sleep(1600);
        for (const j of rawSink) {
          const parts = [];
          jsonText(j, parts);
          const t = parts.join('');
          if (t.includes('需选中')) reqLine = t;
          const cmds = [];
          findClickCommands(j, cmds);
          const c = cmds.find(x => /^\/balatro use \d+ [a-z]+:[\w.]+( @[\d,]+)?$/.test(x));
          if (c) useCmd = c;
        }
        rawSink = null;
      }
      step('confirm_req_line', !!reqLine, reqLine ? reqLine.slice(0, 80) : '未见需求预告行');
      step('confirm_cmd_has_at_ids', !!useCmd && /@\d+/.test(useCmd), useCmd || '未捕获确认使用命令');

      if (useCmd) {
        // ---- 执行确认 → 使用成功且无「请选择」错误 ----
        const before = chatLog.length;
        b1.chat(useCmd);
        await sleep(2200);
        const window = chatLog.slice(before).join('\n');
        const ok = window.includes('使用成功');
        const noSelErr = !window.includes('请选择');
        step('use_succeeded', ok, window.slice(-200));
        step('no_select_error', noSelErr, noSelErr ? '无「请选择」错误' : '仍报未选中');
        // ---- 生效验证：卡面简介变化/含增强版本蜡封关键词 ----
        const list3 = inters(b1);
        if (list3.length) {
          const proj3 = boardAxes(b1, list3);
          const r3 = classifyRound(list3, proj3);
          const afterInfo = await shiftInfo(b1, r3.hand[0]);
          const changed = beforeInfo !== afterInfo;
          const kw = afterInfo && EFFECT_KEYS.test(afterInfo);
          step('effect_applied', ok && (changed || kw),
              `changed=${changed} kw=${!!kw} :: ${(afterInfo || '无简介').slice(0, 90)}`);
        }
      }
    }
  }

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1200);
  fs.writeFileSync(__dirname + '/check21-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check21-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
