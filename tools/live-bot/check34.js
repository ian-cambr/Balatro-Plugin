// R237 实机验证脚本 34：medusa 石头局 × 目标确认链 UI 路径（种子 M2372 真实流预验）
// 断言：①石头手牌（1:石头 …）面板渲染与推进正常（小盲可清）；②shop1 买星星；
// ③大盲回合选中 1 张（含石头语境）→ 右键消耗品 → 确认框 @id 快照 → 使用成功 → 效果落地。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SEED = 'M2372';
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
function parseHand(str) {
  // "1:石头  2:石头  3:♠J" → {pos, stone:true} 或 {pos, rank, suit}
  const out = [];
  for (const m of str.matchAll(/(\d+):(石头|([♠♥♦♣])((?:10)|[AJQK2-9]))/g)) {
    if (m[2] === '石头') out.push({ pos: +m[1], stone: true, rank: 0, suit: -1 });
    else {
      const r = m[4] === 'A' ? 14 : m[4] === 'K' ? 13 : m[4] === 'Q' ? 12 : m[4] === 'J' ? 11 : parseInt(m[4]);
      out.push({ pos: +m[1], stone: false, rank: r, suit: '♠♥♦♣'.indexOf(m[3]) });
    }
  }
  return out;
}
// medusa 石头策略（与 R237Scan.choosePlay 同构）
function choosePlay(cards) {
  const stones = cards.filter(c => c.stone);
  const rest = cards.filter(c => !c.stone);
  if (stones.length >= 3) {
    const mix = [...stones, ...rest];
    return mix.slice(0, Math.min(5, mix.length));
  }
  const groups = [];
  for (const c of rest) {
    let g = groups.find(lg => lg[0].rank === c.rank);
    if (!g) { g = []; groups.push(g); }
    g.push(c);
  }
  groups.sort((a, b) => (b.length !== a.length) ? (b.length - a.length) : (b[0].rank - a[0].rank));
  const best = groups[0] || [];
  if (best.length >= 2) {
    const t = [...best, ...stones];
    return t.slice(0, Math.min(5, t.length));
  }
  return cards.slice(0, Math.min(5, cards.length));
}
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { blind: m[2], phase: m[3], discards: +m[7], money: +m[8] };
      const h = s.match(/^手牌: (.*)$/);
      if (h && v) v.cards = parseHand(h[1]);
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
    if (!/^\[Interaction\]|操作说明|右键|本局信息|商店/.test(s)) log(`CHAT ${s.slice(0, 120)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);
  b1.chat('/balatro quit'); await sleep(1000);

  b1.chat(`/balatro play medusa ${SEED}`);
  await sleep(3200);

  // 小盲：石头策略推进
  let sawStoneHand = false, smallCleared = false, held = false;
  for (let i = 0; i < 40; i++) {
    const st = await statusSnapshot(b1, 1200);
    if (!st) { b1.chat('/balatro go'); await sleep(2000); continue; }
    if (st.phase === 'BLIND_SELECT') { b1.chat('/balatro go'); await sleep(2100); continue; }
    if (st.phase === 'SHOP') { smallCleared = true; break; }
    if (st.phase !== 'ROUND' || !st.cards || !st.cards.length) continue;
    if (st.cards.some(c => c.stone)) sawStoneHand = true;
    const play = choosePlay(st.cards);
    if (!play.length) continue;
    b1.chat('/balatro playcard ' + play.map(c => c.pos).join(' '));
    await sleep(2100);
  }
  step('medusa_stone_hand_seen', sawStoneHand, sawStoneHand ? '手牌含石头牌（N:石头 渲染）' : '未见石头牌');
  step('medusa_small_cleared', smallCleared, smallCleared ? '石头局小盲实机可清' : '未清');

  if (smallCleared) {
    // shop1：买星星（预验 shop1Targets=[star$3]）
    const lines = [];
    {
      const onMsg = (json) => lines.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro shop');
      await sleep(1500);
      b1.removeListener('message', onMsg);
    }
    let idx = -1;
    for (const l of lines) {
      const m = l.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
      if (m && ['力量', '倒吊人', '死神', '星星', '月亮', '太阳', '世界', '护符', '光环', '似曾相识', '恍惚', '灵媒', '地穴生物'].includes(m[2]) && !l.includes('已售')) { idx = +m[1]; break; }
    }
    if (idx > 0) {
      const bought = [];
      const onB = (json) => bought.push(json.toString());
      b1.on('message', onB);
      b1.chat(`/balatro buy ${idx}`);
      await sleep(1700);
      b1.removeListener('message', onB);
      held = bought.some(x => x.includes('购买成功'));
    }
    step('medusa_bought_target', held, held ? '持目标类消耗品' : `idx=${idx} 未购`);
    // next → 大盲回合
    b1.chat('/balatro next');
    await sleep(2100);
    b1.chat('/balatro go');
    await sleep(2400);

    const stR = await statusSnapshot(b1);
    const inRound = stR && stR.phase === 'ROUND' && stR.cards && stR.cards.length;
    if (inRound && held) {
      let list = inters(b1);
      let proj = boardAxes(b1, list);
      let r = classifyRound(list, proj);
      const beforeInfo = await shiftInfo(b1, r.hand[0]);
      b1.activateEntity(r.hand[0]);
      await sleep(500);
      list = inters(b1); proj = boardAxes(b1, list);
      const maxY = Math.max(...list.map(e => e.position.y));
      // medusa 持永恒大理石小丑 → 最上行=小丑行；消耗品行 = 小丑行脚底 - 0.62（R233a 行距课）
      let consRow = list.filter(e => Math.abs(e.position.y - (maxY - 0.62)) < 0.06).sort((a, b) => proj(a) - proj(b));
      if (!consRow.length) consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
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
        step('medusa_confirm_req_line', !!reqLine, reqLine ? reqLine.slice(0, 70) : '未见需求行');
        step('medusa_confirm_at_ids', !!useCmd && /@\d+/.test(useCmd), useCmd || '无 @ids');
        if (useCmd && /@\d+/.test(useCmd)) {
          const before = chatLog.length;
          b1.chat(useCmd);
          await sleep(2100);
          const w = chatLog.slice(before).join('\n');
          step('medusa_use_succeeded', w.includes('使用成功') && !w.includes('请选择'), w.slice(-90));
          const list3 = inters(b1);
          if (list3.length) {
            const proj3 = boardAxes(b1, list3);
            const r3 = classifyRound(list3, proj3);
            const afterInfo = await shiftInfo(b1, r3.hand[0]);
            step('medusa_effect_applied', beforeInfo !== afterInfo,
                `before=${(beforeInfo || '无').slice(0, 24)} after=${(afterInfo || '无').slice(0, 24)}`);
          }
        }
      } else step('medusa_confirm_at_ids', false, '未见消耗品行');
    } else {
      step('medusa_confirm_req_line', false, `回合=${!!inRound} 持有=${held}`);
      step('medusa_confirm_at_ids', false, '前置未达');
      step('medusa_use_succeeded', false, '前置未达');
      step('medusa_effect_applied', false, '前置未达');
    }
  } else {
    step('medusa_bought_target', false, '小盲未清');
    step('medusa_confirm_req_line', false, '跳过');
    step('medusa_confirm_at_ids', false, '跳过');
    step('medusa_use_succeeded', false, '跳过');
    step('medusa_effect_applied', false, '跳过');
  }

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check34-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check34-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
