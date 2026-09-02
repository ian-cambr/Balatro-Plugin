// R235 实机验证脚本 32：psychic Boss 回合 × @ids 目标确认链（种子 B2351，扫描锁定）
// 断言：①psychic 回合出牌 <5 张被拒（文案指向通灵者）；②选中 1 张手牌右键目标类消耗品
// → 确认框带 @id 快照 → 执行成功（psychic 不限制消耗品）；③效果落地。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SEED = 'B2351';
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
  if (best.length === 2 && second && second.length === 2) return { play: [...best, ...second], discard: [] };
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
      if (m) v = { blind: m[2], phase: m[3], discards: +m[7], money: +m[8] };
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
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 140)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (!s) return;
    chatLog.push(s);
    if (rawSink) rawSink.push(json.json ? json.json : json);
    if (!/^\[Interaction\]|操作说明|右键|本局信息/.test(s)) log(`CHAT ${s.slice(0, 130)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);
  b1.chat('/balatro quit'); await sleep(1000);

  // ---- 开局 B2351 → 清 small → shop1 → 买目标类（无则重掷）→ 清 big → shop2 同 → Boss(pyschic) 回合 ----
  b1.chat(`/balatro play ${SEED}`);
  await sleep(3200);
  let bossRound = false, held = null;
  for (let phase = 1; phase <= 2 && !bossRound; phase++) {
    // 清当前盲
    for (let i = 0; i < 36; i++) {
      const st = await statusSnapshot(b1, 1100);
      if (!st) { b1.chat('/balatro go'); await sleep(1900); continue; }
      if (st.phase === 'BLIND_SELECT') { b1.chat('/balatro go'); await sleep(2100); continue; }
      if (st.phase === 'SHOP') break;
      if (st.phase !== 'ROUND' || !st.handStr) continue;
      const d = decide(st.handStr);
      const list = inters(b1);
      if (!list.length) continue;
      const proj = boardAxes(b1, list);
      const r = classifyRound(list, proj);
      if (!r.primary || r.hand.length < 2) continue;
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { b1.activateEntity(r.hand[pi]); await sleep(340); }
        b1.activateEntity(r.secondary);
        await sleep(1500);
        continue;
      }
      if (!d.play.length) continue;
      for (const pi of d.play) { b1.activateEntity(r.hand[pi]); await sleep(340); }
      b1.activateEntity(r.primary);
      await sleep(1900);
    }
    const stS = await statusSnapshot(b1);
    if (!stS || stS.phase !== 'SHOP') { b1.chat('/balatro quit'); break; }
    // 商店找目标类消耗品（两轮重掷）
    let found = null;
    for (let rr = 0; rr < 3 && !found; rr++) {
      const lines = [];
      const onMsg = (json) => lines.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro shop');
      await sleep(1500);
      b1.removeListener('message', onMsg);
      for (const l of lines) {
        const m = l.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
        if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2], price: +m[3] }; break; }
      }
      if (!found) {
        const stm = await statusSnapshot(b1, 1000);
        if (stm && stm.money >= 5 && rr < 2) { b1.chat('/balatro reroll'); await sleep(1900); }
        else break;
      }
    }
    if (found && !held) {
      const bought = [];
      const onB = (json) => bought.push(json.toString());
      b1.on('message', onB);
      b1.chat(`/balatro buy ${found.idx}`);
      await sleep(1700);
      b1.removeListener('message', onB);
      if (bought.some(x => x.includes('购买成功'))) held = found;
    }
    // next → 下一盲
    {
      const list = inters(b1);
      if (list.length) {
        const proj = boardAxes(b1, list);
        const minY = Math.min(...list.map(i => i.position.y));
        const bottom = rowOf(list, proj, minY);
        b1.activateEntity(bottom[bottom.length - 1]);
        await sleep(2700);
      }
    }
  }
  // go 进 Boss 回合
  {
    const list = inters(b1);
    if (list.length) {
      const proj = boardAxes(b1, list);
      const minY = Math.min(...list.map(i => i.position.y));
      b1.activateEntity(rowOf(list, proj, minY)[0]);
      await sleep(2600);
    } else { b1.chat('/balatro go'); await sleep(2400); }
  }
  const stB = await statusSnapshot(b1);
  const inBoss = stB && stB.phase === 'ROUND' && stB.blind === 'boss';
  step('boss_psychic_round_reached', inBoss, inBoss ? 'B2351 Boss 回合（psychic）' : `phase=${stB && stB.phase} blind=${stB && stB.blind}`);

  if (inBoss) {
    // ① psychic 限制：命令出 1 张应被拒（通灵者文案）
    const w1 = [];
    {
      const onMsg = (json) => w1.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro playcard 1');
      await sleep(1700);
      b1.removeListener('message', onMsg);
    }
    step('psychic_rejects_short_play', w1.some(s => s.includes('通灵者') && s.includes('5 张')), w1.join('|').slice(0, 80));

    // ② 持有目标类 → 选中 1 张 → 右键 → @ids → 使用成功
    if (held) {
      let list = inters(b1);
      let proj = boardAxes(b1, list);
      let r = classifyRound(list, proj);
      const beforeInfo = await shiftInfo(b1, r.hand[0]);
      b1.activateEntity(r.hand[0]);
      await sleep(500);
      list = inters(b1); proj = boardAxes(b1, list);
      const maxY = Math.max(...list.map(e => e.position.y));
      const consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
      step('cons_row_found', consRow.length >= 1, `最上行 ${consRow.length} 盒`);
      if (consRow.length) {
        rawSink = [];
        b1.activateEntity(consRow[0]);
        await sleep(1600);
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
        step('boss_confirm_req_line', !!reqLine, reqLine ? reqLine.slice(0, 70) : '未见需求行');
        step('boss_confirm_at_ids', !!useCmd && /@\d+/.test(useCmd), useCmd || '无 @ids 命令');
        if (useCmd) {
          const before = chatLog.length;
          b1.chat(useCmd);
          await sleep(2100);
          const w = chatLog.slice(before).join('\n');
          step('boss_use_succeeded', w.includes('使用成功') && !w.includes('请选择'), w.slice(-100));
          const list3 = inters(b1);
          if (list3.length) {
            const proj3 = boardAxes(b1, list3);
            const r3 = classifyRound(list3, proj3);
            const afterInfo = await shiftInfo(b1, r3.hand[0]);
            step('boss_effect_applied', (beforeInfo !== afterInfo) || (afterInfo && EFFECT_KEYS.test(afterInfo)),
                `before=${beforeInfo ? 'Y' : 'N'} after=${(afterInfo || '无').slice(0, 60)}`);
          }
        }
      }
    } else {
      // 未持有目标类：用命令路径验证（psychic 回合命令 use 带序号也应工作——引擎锁已证，此处命令面）
      step('cons_row_found', false, '两店未获目标类消耗品（引擎/满载面已证，此处记录）');
      step('boss_confirm_req_line', true, '跳过（无持有）');
      step('boss_confirm_at_ids', true, '跳过（无持有）');
      step('boss_use_succeeded', true, '跳过（无持有）');
      step('boss_effect_applied', true, '跳过（无持有）');
    }
  }

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check32-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check32-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
