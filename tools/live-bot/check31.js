// R234 实机验证脚本 31：挑战禁入 × v0.4.61 新路径交叉 + 黑注永恒/金注租赁确认框
// A) jokerless 局：商店/重掷后零小丑行（全禁入）+ 零审判/幽灵/灵魂行 + 目标确认链(@ids)在挑战局正常
// B) 黑注(red 3)：持有永恒小丑右键 →「永恒小丑，不可出售」且无确认按钮
// C) 金注(red 7)：租赁小丑确认框 → 售价 $1（R124 真版语义）
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
let rawSink = null;

const BANNED_NAMES = ['审判', '幽灵', '灵魂']; // jokerless: judgement/wraith/soul
const TARGET_ITEMS = {
  '魔术师': 1, '皇后': 1, '教皇': 1, '力量': 1, '倒吊人': 1,
  '恋人': 1, '战车': 1, '正义': 1, '恶魔': 1, '高塔': 1,
  '护符': 1, '光环': 1, '似曾相识': 1, '恍惚': 1, '灵媒': 1, '地穴生物': 1,
  '死神': 2, '星星': 1, '月亮': 1, '太阳': 1, '世界': 1,
};

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
      if (m) v = { phase: m[3], discards: +m[7], money: +m[8] };
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
function shopLines(bot) {
  return new Promise(async (resolve) => {
    const lines = [];
    const onMsg = (json) => lines.push(json.toString());
    bot.on('message', onMsg);
    bot.chat('/balatro shop');
    await sleep(1600);
    bot.removeListener('message', onMsg);
    resolve(lines);
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
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 140)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { if (rawSink) rawSink.push(json.json ? json.json : json); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}
/** 打到商店（playArg 完整传给 /balatro play）。 */
async function toShop(bot, playArg) {
  bot.chat('/balatro quit'); await sleep(1000);
  bot.chat('/balatro play ' + playArg);
  await sleep(3000);
  for (let i = 0; i < 30; i++) {
    const st = await statusSnapshot(bot, 1100);
    if (!st) { bot.chat('/balatro go'); await sleep(2000); continue; }
    if (st.phase === 'SHOP') return st;
    if (st.phase === 'END') return null; // 失败局快速放弃
    if (st.phase !== 'ROUND' || !st.handStr) continue;
    const d = decide(st.handStr);
    const list = inters(bot);
    if (!list.length) continue;
    const proj = boardAxes(bot, list);
    const r = classifyRound(list, proj);
    if (!r.primary || r.hand.length < 2) continue;
    if (st.discards > 0 && d.discard.length >= 2) {
      for (const pi of d.discard) { bot.activateEntity(r.hand[pi]); await sleep(350); }
      bot.activateEntity(r.secondary);
      await sleep(1500);
      continue;
    }
    if (!d.play.length) continue;
    for (const pi of d.play) { bot.activateEntity(r.hand[pi]); await sleep(350); }
    bot.activateEntity(r.primary);
    await sleep(1900);
  }
  return null;
}
/** 右键持有小丑行（最上行，无其他持有物时）并捕获文本+命令。 */
async function clickTopRowCapture(bot) {
  const list = inters(bot);
  if (!list.length) return null;
  const proj = boardAxes(bot, list);
  const topY = Math.max(...list.map(e => e.position.y));
  const row = list.filter(e => Math.abs(e.position.y - topY) < 0.05).sort((a, b) => proj(a) - proj(b));
  if (!row.length) return null;
  const raw = [];
  rawSink = [];
  bot.activateEntity(row[0]);
  await sleep(1600);
  rawSink = null;
  const parts = [];
  raw.forEach(j => jsonText(j, parts));
  const cmds = [];
  raw.forEach(j => findClickCommands(j, cmds));
  return { text: parts.join(' '), cmds };
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);

  // ---------- A. jokerless 禁入 × 新路径 ----------
  {
    let st = null;
    for (let tr = 0; tr < 3 && !st; tr++) st = await toShop(b1, 'jokerless ' + (tr ? 'R' + tr : ''));
    step('A_jokerless_reached_shop', !!st, st ? 'jokerless 清盲进商店' : '3 次未到商店');
    if (st) {
      let violations = [], sawTarot = false;
      for (let round = 0; round < 2; round++) {
        const lines = await shopLines(b1);
        for (const l of lines) {
          if (/^\[\d+\] 小丑/.test(l)) violations.push('小丑行:' + l.slice(0, 30));
          for (const b of BANNED_NAMES) if (l.includes(b)) violations.push('禁入:' + b);
          if (/^\[\d+\] tarot/.test(l)) sawTarot = true;
        }
        const stm = await statusSnapshot(b1, 1000);
        if (round === 0 && stm && stm.money >= 5) { b1.chat('/balatro reroll'); await sleep(2000); }
        else break;
      }
      step('A_ban_respected', violations.length === 0, violations.length ? violations.join('|') : '两轮商店零小丑行、零审判/幽灵/灵魂');
      step('A_tarots_still_sold', sawTarot, '非禁入塔罗仍正常出售（禁入是子集而非全禁）');

      // 买一个允许的目标类消耗品 → 回合 @ids 链
      const lines = await shopLines(b1);
      let found = null;
      for (const l of lines) {
        const m = l.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
        if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2] }; break; }
      }
      let acq = false;
      if (!found) {
        const stm = await statusSnapshot(b1, 1000);
        if (stm && stm.money >= 5) {
          b1.chat('/balatro reroll'); await sleep(2000);
          const l2 = await shopLines(b1);
          for (const l of l2) {
            const m = l.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
            if (m && TARGET_ITEMS[m[2]] !== undefined) { found = { idx: +m[1], name: m[2] }; break; }
          }
        }
      }
      if (found) {
        const bought = [];
        const onB = (json) => bought.push(json.toString());
        b1.on('message', onB);
        b1.chat(`/balatro buy ${found.idx}`);
        await sleep(1800);
        b1.removeListener('message', onB);
        acq = bought.some(x => x.includes('购买成功'));
        if (!acq) { step('A_target_chain_in_challenge', false, `买入失败（${found.name}）：${bought[0] || '无回复'}`); }
        // next → go → 回合
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
        const stR = acq ? await statusSnapshot(b1) : null;
        if (!acq) { /* 已在上面报因 */ }
        else if (stR && stR.phase === 'ROUND') {
          let list = inters(b1);
          let proj = boardAxes(b1, list);
          let r = classifyRound(list, proj);
          b1.activateEntity(r.hand[0]);
          await sleep(500);
          list = inters(b1); proj = boardAxes(b1, list);
          const maxY = Math.max(...list.map(e => e.position.y));
          const consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
          const cap = await clickTopRowCapture(b1); // cons 行即最上行（未持小丑）
          const useCmd = cap && cap.cmds.find(x => /^\/balatro use \d+ [a-z]+:[\w.]+ @[\d,]+$/.test(x));
          if (useCmd) {
            const w = [];
            const onMsg = (json) => w.push(json.toString());
            b1.on('message', onMsg);
            b1.chat(useCmd);
            await sleep(2000);
            b1.removeListener('message', onMsg);
            step('A_target_chain_in_challenge', w.some(s => s.includes('使用成功')) && !w.some(s => s.includes('请选择')),
                `挑战局 ${found.name} @ids 使用成功`);
          } else step('A_target_chain_in_challenge', false, cap ? ('cmds=' + JSON.stringify(cap.cmds) + ' text=' + cap.text.slice(0, 80)) : '未捕获确认框');
        } else if (acq) step('A_target_chain_in_challenge', false, '未回到回合');
      } else {
        step('A_target_chain_in_challenge', false, '两轮商店均无目标类消耗品（记录，非缺陷面）');
      }
    }
    b1.chat('/balatro quit'); await sleep(1200);
  }

  // ---------- B. 黑注永恒小丑 ----------
  {
    let eternalHit = false, tried = 0, triedBuy = 0;
    for (let att = 0; att < 8 && !eternalHit; att++) {
      const st = await toShop(b1, 'red 3');
      if (!st) continue;
      const lines = await shopLines(b1);
      const stm = await statusSnapshot(b1, 1000);
      // 买光所有可负担小丑，逐只右键（每只独立 30% 贴纸）
      let money = stm ? stm.money : 0;
      const picks = [];
      for (const l of lines) {
        const m = l.match(/^\[(\d+)\] 小丑 ([^\s§]+).*\$(\d+)/);
        if (m && !l.includes('已售') && +m[3] <= money) { picks.push({ idx: +m[1], price: +m[3] }); money -= +m[3]; }
        if (picks.length >= 2) break;
      }
      if (!picks.length) { b1.chat('/balatro quit'); await sleep(1200); continue; }
      for (const pick of picks) {
        b1.chat(`/balatro buy ${pick.idx}`);
        await sleep(1600);
        triedBuy++;
        const cap = await clickTopRowCapture(b1);
        tried++;
        if (cap) {
          const eternalMsg = cap.text.includes('永恒小丑，不可出售');
          const hasSell = cap.cmds.some(x => /^\/balatro sellj /.test(x));
          if (eternalMsg && !hasSell) { eternalHit = true; step('B_eternal_nosell', true, '「永恒小丑，不可出售」且无确认按钮'); break; }
        }
      }
      b1.chat('/balatro quit'); await sleep(1200);
    }
    if (!eternalHit) step('B_eternal_nosell', false, `${triedBuy} 次购丑（${tried} 次右键）未遇到永恒（30%/只）`);
  }

  // ---------- C. 金注租赁小丑（售价 $1） ----------
  {
    let rentalHit = false, tried = 0;
    for (let att = 0; att < 8 && !rentalHit; att++) {
      const st = await toShop(b1, 'red 7');
      if (!st) continue;
      const lines = await shopLines(b1);
      const stm = await statusSnapshot(b1, 1000);
      let money = stm ? stm.money : 0;
      const picks = [];
      for (const l of lines) {
        const m = l.match(/^\[(\d+)\] 小丑 ([^\s§]+).*\$(\d+)/);
        if (m && !l.includes('已售') && +m[3] <= money) { picks.push({ idx: +m[1], price: +m[3] }); money -= +m[3]; }
        if (picks.length >= 2) break;
      }
      if (!picks.length) { b1.chat('/balatro quit'); await sleep(1200); continue; }
      for (const pick of picks) {
        b1.chat(`/balatro buy ${pick.idx}`);
        await sleep(1600);
        const cap = await clickTopRowCapture(b1);
        tried++;
        if (cap) {
          const m = cap.text.match(/售价：\$(\d+)/);
          const sellCmd = cap.cmds.find(x => /^\/balatro sellj /.test(x));
          // 租赁：购价 $1 且确认框售价 $1（R124：租金恒 $1；售价=半价取 1）
          if (pick.price === 1 && sellCmd) {
            const sell = m ? +m[1] : -1;
            rentalHit = true;
            step('C_rental_confirm', sell === 1, `购价 $1 的租赁小丑 → 确认框售价 $${sell}（期望 1）`);
            break;
          }
        }
      }
      b1.chat('/balatro quit'); await sleep(1200);
    }
    if (!rentalHit) step('C_rental_confirm', false, `${tried} 次购丑未遇到 $1 租赁（30%/只）`);
  }

  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check31-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check31-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
