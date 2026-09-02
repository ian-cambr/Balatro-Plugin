// R231 实机验证脚本 28：BoardMoveListener 极端迁移矩阵 × 目标类消耗品选中态/确认待决态
// 四场景：A 远距同世界传送(>64格) B 跨世界传送(下界) C 死亡重生 D 跨区块卸载重载(自愈)
// 每场景：选中牌 → 右键消耗品拿确认命令(@ids) → 迁移事件 → 牌桌重建 + 选中保持(再右键仍显示已选N张)
//         → 执行迁移前捕获的确认命令 → 使用成功 + 效果落地
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

const TARGET_ITEMS = {
  '魔术师': 1, '皇后': 1, '教皇': 1, '力量': 1, '倒吊人': 1,
  '恋人': 1, '战车': 1, '正义': 1, '恶魔': 1, '高塔': 1,
  '护符': 1, '光环': 1, '似曾相识': 1, '恍惚': 1, '灵媒': 1, '地穴生物': 1,
  '死神': 2, '星星': 1, '月亮': 1, '太阳': 1, '世界': 1,
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
  if (best.length === 2 && val(cards[best[0]]) >= 8) return { play: best, discard: [] };
  const keep = cards.map((r, i) => ({ i, v: val(r) })).sort((a, b) => b.v - a.v).slice(0, 3).map(o => o.i);
  return { play: [], discard: cards.map((r, i) => i).filter(i => !keep.includes(i)) };
}
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { phase: m[3], hands: +m[6], discards: +m[7], money: +m[8] };
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
function jsonText(obj, out) {
  if (!obj || typeof obj !== 'object') return;
  if (typeof obj.text === 'string') out.push(obj.text);
  if (obj.extra) obj.extra.forEach(x => jsonText(x, out));
  return out;
}
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
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 160)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (!s) return;
    chatLog.push(s);
    if (rawSink) rawSink.push(json.json ? json.json : json);
    if (!/^\[Interaction\]|操作说明|右键|本局信息|商店 ===/.test(s)) log(`CHAT ${s.slice(0, 120)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

/** 打到商店并买目标类消耗品，然后 next+go 回到回合（选中态就绪）。返回 {name,need} 或 null。 */
async function acquireTargetAndBackToRound(b1) {
  for (let attempt = 1; attempt <= 10; attempt++) {
    let inShop = false, runNo = 0;
    while (!inShop && runNo < 3) {
      runNo++;
      await b1.look(Math.PI, 0, false).catch(() => {});
      b1.chat(`/balatro play M28${Date.now() % 100000}_${runNo}`);
      await sleep(2800);
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
          for (const pi of d.discard) { b1.activateEntity(r.hand[pi]); await sleep(360); }
          b1.activateEntity(r.secondary);
          await sleep(1600);
          continue;
        }
        if (!d.play.length) { if (++stuck > 3) break; continue; }
        for (const pi of d.play) { b1.activateEntity(r.hand[pi]); await sleep(360); }
        b1.activateEntity(r.primary);
        await sleep(2000);
        const st2 = await statusSnapshot(b1);
        if (st2 && st2.score >= st2.target) await sleep(2600);
      }
      if (!inShop) { b1.chat('/balatro quit'); await sleep(1400); break; }
    }
    if (!inShop) continue;
    await sleep(1800);
    const lines = [];
    {
      const onMsg = (json) => lines.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro shop');
      await sleep(1600);
      b1.removeListener('message', onMsg);
    }
    let found = null;
    for (const line of lines) {
      const m = line.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
      if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2] }; break; }
    }
    if (!found) {
      const st = await statusSnapshot(b1);
      if (st && st.money >= 5) {
        b1.chat('/balatro reroll');
        await sleep(2000);
        const lines2 = [];
        const onMsg2 = (json) => lines2.push(json.toString());
        b1.on('message', onMsg2);
        b1.chat('/balatro shop');
        await sleep(1600);
        b1.removeListener('message', onMsg2);
        for (const line of lines2) {
          const m = line.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
          if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2] }; break; }
        }
      }
      if (!found) { b1.chat('/balatro quit'); await sleep(1500); continue; } // 关键：放弃当前局再重试
    }
    b1.chat(`/balatro buy ${found.idx}`);
    await sleep(1800);
    // next + go 回合
    {
      const list = inters(b1);
      if (list.length) {
        const proj = boardAxes(b1, list);
        const minY = Math.min(...list.map(i => i.position.y));
        const bottom = rowOf(list, proj, minY);
        b1.activateEntity(bottom[bottom.length - 1]);
        await sleep(2800);
      }
      const list2 = inters(b1);
      if (list2.length) {
        const proj2 = boardAxes(b1, list2);
        const minY2 = Math.min(...list2.map(i => i.position.y));
        b1.activateEntity(rowOf(list2, proj2, minY2)[0]);
        await sleep(2600);
      } else { b1.chat('/balatro go'); await sleep(2400); }
    }
    const st = await statusSnapshot(b1);
    if (st && st.phase === 'ROUND') return { name: found.name, need: TARGET_ITEMS[found.name] };
    b1.chat('/balatro quit'); await sleep(1300);
  }
  return null;
}

/** 选中 need 张 + 右键消耗品，返回 { reqLine, useCmd, beforeInfo } 或 null。 */
async function selectAndCaptureConfirm(b1, need) {
  let list = inters(b1);
  if (!list.length) return null;
  let proj = boardAxes(b1, list);
  let r = classifyRound(list, proj);
  const beforeInfo = await shiftInfo(b1, r.hand[0]);
  for (let k = 0; k < need; k++) {
    b1.activateEntity(r.hand[k]);
    await sleep(450);
    list = inters(b1); proj = boardAxes(b1, list); r = classifyRound(list, proj);
  }
  const maxY = Math.max(...list.map(e => e.position.y));
  const consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
  log('capDiag list=', list.length, 'maxY=', maxY.toFixed(2), 'consRow=', consRow.length);
  if (!consRow.length) return null;
  rawSink = [];
  b1.activateEntity(consRow[0]);
  await sleep(1600);
  log('capDiag rawSink=', rawSink.length);
  let useCmd = null, reqLine = null;
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
  return (useCmd && /@\d+/.test(useCmd)) ? { useCmd, reqLine, beforeInfo } : null;
}

/** 迁移后再右键消耗品验证选中保持；并执行迁移前命令。 */
async function verifyAfterMigration(b1, pre) {
  await sleep(2500);
  const list = inters(b1);
  const rebuilt = list.length >= 10;
  let selPreserved = false;
  if (rebuilt) {
    const proj = boardAxes(b1, list);
    const maxY = Math.max(...list.map(e => e.position.y));
    const consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
    if (consRow.length) {
      rawSink = [];
      b1.activateEntity(consRow[0]);
      await sleep(1600);
      for (const j of rawSink) {
        const parts = [];
        jsonText(j, parts);
        const t = parts.join('');
        if (/将作用于已选牌/.test(t)) selPreserved = true;
      }
      rawSink = null;
    }
  }
  const before = chatLog.length;
  b1.chat(pre.useCmd);
  await sleep(2200);
  const w = chatLog.slice(before).join('\n');
  const usedOk = w.includes('使用成功') && !w.includes('请选择');
  return { rebuilt, selPreserved, usedOk, ents: list.length };
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);

  // ---------- A. 远距同世界传送 ----------
  {
    const acq = await acquireTargetAndBackToRound(b1);
    step('A_setup', !!acq, acq ? `持 ${acq.name}` : '未获得目标类消耗品');
    if (acq) {
      const pre = await selectAndCaptureConfirm(b1, acq.need);
      step('A_confirm_captured', !!pre, pre && pre.useCmd);
      if (pre) {
        const home = b1.entity.position.clone();
        b1.chat('/tp BalBot 5000 -60 5000');
        await sleep(2000);
        const v = await verifyAfterMigration(b1, pre);
        step('A_far_tp', v.rebuilt && v.selPreserved && v.usedOk, `重建=${v.rebuilt}(${v.ents}) 选中保持=${v.selPreserved} 使用=${v.usedOk}`);
        b1.chat(`/tp BalBot ${home.x.toFixed(0)} ${home.y.toFixed(0)} ${home.z.toFixed(0)}`);
        await sleep(2000);
      }
      b1.chat('/balatro quit'); await sleep(1300);
    }
  }

  // ---------- B. 跨世界传送（下界） ----------
  {
    const acq = await acquireTargetAndBackToRound(b1);
    if (acq) {
      const pre = await selectAndCaptureConfirm(b1, acq.need);
      if (pre) {
        const home = b1.entity.position.clone();
        b1.chat('/execute in minecraft:the_nether run tp BalBot 100 70 100');
        await sleep(3000);
        const inNether = b1.entity.position && Math.abs(b1.entity.position.x - 100) < 2;
        const v = await verifyAfterMigration(b1, pre);
        step('B_cross_world', inNether && v.rebuilt && v.selPreserved && v.usedOk, `下界=${inNether} 重建=${v.rebuilt}(${v.ents}) 选中保持=${v.selPreserved} 使用=${v.usedOk}`);
        b1.chat('/execute in minecraft:overworld run tp BalBot ' + `${home.x.toFixed(0)} ${home.y.toFixed(0)} ${home.z.toFixed(0)}`);
        await sleep(2500);
      } else step('B_cross_world', false, '确认命令未捕获');
      b1.chat('/balatro quit'); await sleep(1300);
    } else { step('B_cross_world', false, '未获得目标类消耗品'); }
  }

  // ---------- C. 死亡重生 ----------
  {
    const acq = await acquireTargetAndBackToRound(b1);
    if (acq) {
      const pre = await selectAndCaptureConfirm(b1, acq.need);
      if (pre) {
        b1.chat('/kill BalBot');
        await sleep(2500);
        const v = await verifyAfterMigration(b1, pre);
        step('C_death_respawn', v.rebuilt && v.selPreserved && v.usedOk, `重建=${v.rebuilt}(${v.ents}) 选中保持=${v.selPreserved} 使用=${v.usedOk}`);
      } else step('C_death_respawn', false, '确认命令未捕获');
      b1.chat('/balatro quit'); await sleep(1300);
    } else { step('C_death_respawn', false, '未获得目标类消耗品'); }
  }

  // ---------- D. 跨区块卸载重载（自愈） ----------
  {
    const acq = await acquireTargetAndBackToRound(b1);
    if (acq) {
      const pre = await selectAndCaptureConfirm(b1, acq.need);
      if (pre) {
        const home = b1.entity.position.clone();
        b1.chat(`/tp BalBot ${(home.x + 400).toFixed(0)} -60 ${(home.z + 400).toFixed(0)}`);
        await sleep(2000);
        const awayEnts = inters(b1, 16).length;
        await sleep(22000); // 等区块卸载（实体被杀）
        b1.chat(`/tp BalBot ${home.x.toFixed(0)} ${home.y.toFixed(0)} ${home.z.toFixed(0)}`);
        await sleep(4000);
        const v = await verifyAfterMigration(b1, pre);
        step('D_chunk_heal', v.rebuilt && v.selPreserved && v.usedOk, `远离实体=${awayEnts} 重建=${v.rebuilt}(${v.ents}) 选中保持=${v.selPreserved} 使用=${v.usedOk}`);
      } else step('D_chunk_heal', false, '确认命令未捕获');
      b1.chat('/balatro quit'); await sleep(1300);
    } else { step('D_chunk_heal', false, '未获得目标类消耗品'); }
  }

  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check28-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check28-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
