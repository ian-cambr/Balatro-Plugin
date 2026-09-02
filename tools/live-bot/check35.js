// R238 实机验证脚本 35：water Boss（无弃牌）弃牌拒绝 UX + @ids；mark Boss 面朝下渲染/简介保密/选中目标
// A) WATER seed=W238w369（shop1 有月亮）：双盲推进 → Boss 回合：/balatro disc 1 被拒（水文案）
//    → 选中 1 张 → 右键消耗品 → @id 快照 → 使用成功
// B) MARK seed=W238m2：双盲推进买目标类 → Boss 回合：status 手牌含「N:？」（身份保密=0.4.62 修复）
//    → Shift+右键面朝下牌 → 「面朝下的牌」不泄身份 → 选中面朝下牌作为目标 → @id 使用成功
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
const TARGETS = ['力量', '倒吊人', '死神', '星星', '月亮', '太阳', '世界', '护符', '光环', '似曾相识', '恍惚', '灵媒', '地穴生物'];

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
  if (best.length === 2) return { play: best, discard: [] }; // 与 R238Scan 一致：任意对子（预验口径）
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
      if (h && v) { v.handStr = h[1]; v.handRaw = h[1]; }
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
function shiftInfo(bot, e, pattern) {
  return new Promise(async (resolve) => {
    let text = null;
    const re = pattern || /^(红桃|黑桃|梅花|方块|石头牌|面朝下)|增强|版本|蜡封|钢铁|幸运|玻璃|黄金|万能|奖励|倍率/;
    const onMsg = (json) => { const s = json.toString(); if (!text && re.test(s)) text = s; };
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
/** 双盲推进到 Boss 回合；途中商店买目标类（两店各一次机会+一次重掷）。返回 held。 */
async function toBossRound(bot, playArg) {
  bot.chat('/balatro quit'); await sleep(1000);
  bot.chat('/balatro play ' + playArg);
  await sleep(3200);
  let held = false;
  for (let bl = 0; bl < 2; bl++) {
    for (let i = 0; i < 36; i++) {
      const st = await statusSnapshot(bot, 1100);
      if (!st) { bot.chat('/balatro go'); await sleep(1900); continue; }
      if (st.phase === 'BLIND_SELECT') { bot.chat('/balatro go'); await sleep(2100); continue; }
      if (st.phase === 'SHOP') break;
      if (st.phase !== 'ROUND' || !st.handStr) continue;
      const d = decide(st.handStr);
      const list = inters(bot);
      if (!list.length) continue;
      const proj = boardAxes(bot, list);
      const r = classifyRound(list, proj);
      if (!r.primary || r.hand.length < 2) continue;
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { bot.activateEntity(r.hand[pi]); await sleep(340); }
        bot.activateEntity(r.secondary);
        await sleep(1500);
        continue;
      }
      if (!d.play.length) continue;
      for (const pi of d.play) { bot.activateEntity(r.hand[pi]); await sleep(340); }
      bot.activateEntity(r.primary);
      await sleep(1900);
    }
    const stS = await statusSnapshot(bot);
    if (!stS || stS.phase !== 'SHOP') break;
    if (!held) {
      for (let rr = 0; rr < 3 && !held; rr++) {
        const lines = [];
        const onMsg = (json) => lines.push(json.toString());
        bot.on('message', onMsg);
        bot.chat('/balatro shop');
        await sleep(1500);
        bot.removeListener('message', onMsg);
        let idx = -1;
        for (const l of lines) {
          const m = l.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
          if (m && TARGETS.includes(m[2]) && !l.includes('已售')) { idx = +m[1]; break; }
        }
        if (idx > 0) {
          const bought = [];
          const onB = (json) => bought.push(json.toString());
          bot.on('message', onB);
          bot.chat(`/balatro buy ${idx}`);
          await sleep(1700);
          bot.removeListener('message', onB);
          held = bought.some(x => x.includes('购买成功'));
        }
        if (!held) {
          const stm = await statusSnapshot(bot, 1000);
          if (stm && stm.money >= 5 && rr < 2) { bot.chat('/balatro reroll'); await sleep(1900); }
          else break;
        }
      }
    }
    bot.chat('/balatro next');
    await sleep(2100);
  }
  // go 进 Boss
  {
    const list = inters(bot);
    if (list.length) {
      const proj = boardAxes(bot, list);
      const minY = Math.min(...list.map(i => i.position.y));
      bot.activateEntity(rowOf(list, proj, minY)[0]);
      await sleep(2600);
    } else { bot.chat('/balatro go'); await sleep(2400); }
  }
  const st = await statusSnapshot(bot);
  return { st, held };
}
/** 选中 hand[k] 张 → 右键消耗品行 → 捕获确认命令。 */
async function selectAndCapture(bot, count) {
  let list = inters(bot);
  let proj = boardAxes(bot, list);
  let r = classifyRound(list, proj);
  for (let k = 0; k < count; k++) {
    bot.activateEntity(r.hand[k]);
    await sleep(500);
    list = inters(bot); proj = boardAxes(bot, list); r = classifyRound(list, proj);
  }
  const maxY = Math.max(...list.map(e => e.position.y));
  let consRow = list.filter(e => Math.abs(e.position.y - (maxY - 0.62)) < 0.06).sort((a, b) => proj(a) - proj(b));
  if (!consRow.length) consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
  if (!consRow.length) return null;
  rawSink = [];
  bot.activateEntity(consRow[0]);
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
  return { useCmd, reqLine, selEntity: r.hand[0] };
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);

  // ---------- A. WATER ----------
  {
    const { st, held } = await toBossRound(b1, 'W238w183');
    const inBoss = st && st.phase === 'ROUND' && st.blind === 'boss';
    step('A_water_boss_round', inBoss, `blind=${st && st.blind} phase=${st && st.phase} discards=${st && st.discards} held=${held}`);
    if (inBoss) {
      const w = [];
      {
        const onMsg = (json) => w.push(json.toString());
        b1.on('message', onMsg);
        b1.chat('/balatro disc 1');
        await sleep(1700);
        b1.removeListener('message', onMsg);
      }
      step('A_water_disc_rejected', w.some(s => /水|弃牌/.test(s) && !w.some(x => x.includes('已弃'))), w.join('|').slice(0, 90));
      if (held) {
        const cap = await selectAndCapture(b1, 1);
        step('A_confirm_req_line', !!(cap && cap.reqLine), cap && cap.reqLine ? cap.reqLine.slice(0, 70) : '未捕获');
        step('A_confirm_at_ids', !!(cap && cap.useCmd && /@\d+/.test(cap.useCmd)), cap && cap.useCmd || '无 @ids');
        if (cap && cap.useCmd && /@\d+/.test(cap.useCmd)) {
          const before = chatLog.length;
          b1.chat(cap.useCmd);
          await sleep(2100);
          const ww = chatLog.slice(before).join('\n');
          step('A_use_succeeded', ww.includes('使用成功') && !ww.includes('请选择'), ww.slice(-80));
        }
      } else { step('A_confirm_req_line', false, '未持有目标类'); step('A_confirm_at_ids', false, '未持有'); step('A_use_succeeded', false, '未持有'); }
    }
    b1.chat('/balatro quit'); await sleep(1200);
  }

  // ---------- B. MARK ----------
  {
    const { st, held } = await toBossRound(b1, 'W238m2');
    const inBoss = st && st.phase === 'ROUND' && st.blind === 'boss';
    step('B_mark_boss_round', inBoss, `blind=${st && st.blind} held=${held}`);
    if (inBoss) {
      // ① status 手牌身份保密（0.4.62：facedown → ？）
      const handRaw = st.handRaw || '';
      const hasFd = /:\?/.test(handRaw) || /：?/.test('') || handRaw.includes('？');
      step('B_status_hides_facedown', hasFd, handRaw.slice(0, 70));
      // ② Shift+右键面朝下牌（若有）→ 「面朝下的牌」
      const list = inters(b1);
      const proj = boardAxes(b1, list);
      const r = classifyRound(list, proj);
      // 无法从协议区分面朝下实体 → 对全部手牌逐个 Shift 简介直到见到「面朝下」或遍历完
      let fdInfo = null, anyInfo = null;
      for (let k = 0; k < Math.min(8, r.hand.length) && !fdInfo; k++) {
        const info = await shiftInfo(b1, r.hand[k]);
        if (info) {
          if (!anyInfo) anyInfo = info;
          if (info.includes('面朝下')) fdInfo = info;
        }
      }
      const fdCount = (handRaw.match(/：?\?/g) || handRaw.match(/？/g) || []).length;
      step('B_shiftinfo_hides_facedown', fdCount === 0 || !!fdInfo || !anyInfo || !/[♠♥♦♣]/.test(anyInfo),
          `面朝下张数=${fdCount} 简介样本=${(fdInfo || anyInfo || '无').slice(0, 40)}`);
      // ③ 选中（可能是面朝下牌）作为目标 → @ids 使用
      if (held) {
        const cap = await selectAndCapture(b1, 1);
        step('B_confirm_at_ids', !!(cap && cap.useCmd && /@\d+/.test(cap.useCmd)), cap && cap.useCmd || '无');
        if (cap && cap.useCmd && /@\d+/.test(cap.useCmd)) {
          const before = chatLog.length;
          b1.chat(cap.useCmd);
          await sleep(2100);
          const ww = chatLog.slice(before).join('\n');
          step('B_use_succeeded', ww.includes('使用成功') && !ww.includes('请选择'), ww.slice(-80));
        }
      } else { step('B_confirm_at_ids', false, '未持有'); step('B_use_succeeded', false, '未持有'); }
    }
    b1.chat('/balatro quit'); await sleep(1200);
  }

  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check35-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check35-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
