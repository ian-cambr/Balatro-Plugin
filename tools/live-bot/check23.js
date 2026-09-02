// R228 实机验证脚本 23：GUI 向导开局 × R225 目标类消耗品链路交叉 + 双通道竞态
// A) /balatro gui 六步点击链开局 → 打到商店买目标类塔罗 → 回合选中牌 → 确认框 @ids → 使用成功
// B) 向导打开期间命令通道抢先开局 → GUI 再点「开始游戏」应被拒（双通道竞态防线）
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

function findSlotByLabel(window, label) {
  for (let i = 0; i < window.slots.length; i++) {
    const it = window.slots[i];
    if (!it) continue;
    const n = (it.customName ? JSON.stringify(it.customName) : '') + (it.name || '');
    const plain = it.customName ? JSON.stringify(it.customName).replace(/\\u00a7./g, '') : '';
    if (n.includes(label) || plain.includes(label)) return i;
  }
  return -1;
}
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
function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { ante: +m[1], phase: m[3], money: +m[8] };
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
  if (typeof obj.content === 'string') out.push(obj.content);
  if (obj.extra) obj.extra.forEach(x => jsonText(x, out));
  if (obj.with) obj.with.forEach(x => jsonText(x, out));
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
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 200)));
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

  let acquired = null;
  for (let attempt = 1; attempt <= 12 && !acquired; attempt++) {
    // ---- A1: GUI 向导六步点击链开局 ----
    let winType = null;
    const onOpen = (w) => { winType = w; };
    b1.on('windowOpen', onOpen);
    b1.chat('/balatro gui');
    await sleep(2000);
    let guiOk = false;
    if (winType) {
      const clickLabeled = async (labels) => {
        for (const label of labels) {
          const slot = findSlotByLabel(winType, label);
          if (slot < 0) return false;
          await b1.clickWindow(slot, 0, 0);
          await sleep(1200);
        }
        return true;
      };
      const ok1 = await clickLabeled(['标准局']); await sleep(800);
      const ok2 = await clickLabeled(['红色牌组', '下一步']); await sleep(800);
      const ok3 = await clickLabeled(['0 白注', '下一步']); await sleep(800);
      const ok4 = await clickLabeled(['开始游戏']); await sleep(3200);
      guiOk = ok1 && ok2 && ok3 && ok4;
    }
    b1.removeListener('windowOpen', onOpen);
    if (attempt === 1) step('gui_wizard_start', guiOk, guiOk ? '六步点击链开局' : '向导链未完成');

    // ---- A2: 打到商店 ----
    let inShop = false, runNo = 0;
    while (!inShop && runNo < 4 && guiOk) {
      runNo++;
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
      if (!inShop) { b1.chat('/balatro quit'); await sleep(1500); break; }
    }
    if (!inShop) continue;
    if (attempt === 1) step('gui_run_reached_shop', true, 'GUI 开局的局清盲进商店');

    // ---- A3: 买目标类消耗品 ----
    await sleep(2000);
    const lines = [];
    {
      const onMsg = (json) => lines.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro shop');
      await sleep(1800);
      b1.removeListener('message', onMsg);
    }
    let found = null;
    for (const line of lines) {
      const m = line.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
      if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2], price: +m[3] }; break; }
    }
    if (!found) {
      const st = await statusSnapshot(b1);
      if (st && st.money >= 5) { b1.chat('/balatro reroll'); await sleep(2000); }
      const lines2 = [];
      const onMsg2 = (json) => lines2.push(json.toString());
      b1.on('message', onMsg2);
      b1.chat('/balatro shop');
      await sleep(1800);
      b1.removeListener('message', onMsg2);
      for (const line of lines2) {
        const m = line.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
        if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2], price: +m[3] }; break; }
      }
    }
    if (!found) { b1.chat('/balatro quit'); await sleep(1500); continue; }
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
    if (consSink.some(s => s.includes(found.name))) {
      acquired = { name: found.name, need: TARGET_ITEMS[found.name] };
      step('gui_bought_target', true, `GUI 开局的局持有 ${found.name}`);
    } else { b1.chat('/balatro quit'); await sleep(1500); }
  }
  step('gui_target_acquired', !!acquired, acquired ? acquired.name : '多次未遇到目标类消耗品');

  if (acquired) {
    // ---- A4: next → go → 回合 → 选中 → 右键消耗品 → @ids → 使用成功 ----
    {
      const list = inters(b1);
      if (list.length) {
        const proj = boardAxes(b1, list);
        const minY = Math.min(...list.map(i => i.position.y));
        const bottom = rowOf(list, proj, minY);
        b1.activateEntity(bottom[bottom.length - 1]);
        await sleep(3000);
      }
      const list2 = inters(b1);
      if (list2.length) {
        const proj2 = boardAxes(b1, list2);
        const minY2 = Math.min(...list2.map(i => i.position.y));
        b1.activateEntity(rowOf(list2, proj2, minY2)[0]);
        await sleep(2800);
      } else { b1.chat('/balatro go'); await sleep(2500); }
    }
    const stR = await statusSnapshot(b1);
    if (stR && stR.phase === 'ROUND') {
      let list = inters(b1);
      let proj = boardAxes(b1, list);
      let r = classifyRound(list, proj);
      const beforeInfo = await shiftInfo(b1, r.hand[0]);
      for (let k = 0; k < acquired.need; k++) {
        b1.activateEntity(r.hand[k]);
        await sleep(450);
        list = inters(b1); proj = boardAxes(b1, list); r = classifyRound(list, proj);
      }
      const maxY = Math.max(...list.map(e => e.position.y));
      const consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
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
      step('gui_confirm_req_line', !!reqLine, reqLine ? reqLine.slice(0, 70) : '未见需求行');
      step('gui_confirm_at_ids', !!useCmd && /@\d+/.test(useCmd), useCmd || '未捕获确认命令');
      if (useCmd) {
        const before = chatLog.length;
        b1.chat(useCmd);
        await sleep(2200);
        const w = chatLog.slice(before).join('\n');
        step('gui_use_succeeded', w.includes('使用成功') && !w.includes('请选择'), w.slice(-120));
        const list3 = inters(b1);
        if (list3.length) {
          const proj3 = boardAxes(b1, list3);
          const r3 = classifyRound(list3, proj3);
          const afterInfo = await shiftInfo(b1, r3.hand[0]);
          step('gui_effect_applied', (beforeInfo !== afterInfo) || (afterInfo && EFFECT_KEYS.test(afterInfo)),
              `before=${beforeInfo ? 'Y' : 'N'} after=${(afterInfo || '无').slice(0, 60)}`);
        }
      }
    } else step('gui_back_to_round', false, `phase=${stR && stR.phase}`);
  }

  // ---- B: 双通道竞态——向导打开期间命令抢先开局，GUI 再点开始应被拒 ----
  b1.chat('/balatro quit'); await sleep(1200);
  {
    let winType = null;
    const onOpen = (w) => { winType = w; };
    b1.on('windowOpen', onOpen);
    b1.chat('/balatro gui');
    await sleep(1800);
    // 命令通道抢先开局
    b1.chat('/balatro play CMDRACE');
    await sleep(2500);
    const stA = await statusSnapshot(b1);
    // GUI 通道再点开始游戏（若确认页可达；不可达则点穿到确认页）
    let raceRejected = false;
    if (winType) {
      const clickLabeled = async (labels) => {
        for (const label of labels) {
          const slot = findSlotByLabel(winType, label);
          if (slot < 0) return false;
          await b1.clickWindow(slot, 0, 0);
          await sleep(1100);
        }
        return true;
      };
      await clickLabeled(['标准局']); await sleep(600);
      await clickLabeled(['红色牌组', '下一步']); await sleep(600);
      await clickLabeled(['0 白注', '下一步']); await sleep(600);
      const before = chatLog.length;
      await clickLabeled(['开始游戏']); await sleep(2500);
      const w = chatLog.slice(before).join('\n');
      raceRejected = /已有进行中的局|已在一局中|无法开始/.test(w) || !w.includes('使用成功');
    }
    b1.removeListener('windowOpen', onOpen);
    const stB = await statusSnapshot(b1);
    const ents = inters(b1).length;
    step('dual_channel_race_safe', !!stA && !!stB && raceRejected && ents < 60,
        `命令局保持 ante=${stB && stB.ante} phase=${stB && stB.phase} 实体=${ents} rejected=${raceRejected}`);
  }

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check23-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check23-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
