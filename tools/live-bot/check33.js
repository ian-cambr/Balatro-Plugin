// R236 实机验证脚本 33：psychic Boss 回合 × @ids 确认链（种子 R236X51 真实流路径预验）
// 断言：①双盲推进到 Boss 回合；②psychic 拒绝出 1 张（文案指向通灵者）；
// ③选中 1 张右键「星星」→ 确认框 @id 快照 → 使用成功（psychic 不限制消耗品）→ 效果落地（变方块）。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SEED = 'R236X51';
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
  const out = [];
  for (const m of str.matchAll(/(\d+):([♠♥♦♣])((?:10)|[AJQK2-9])/g)) {
    const r = m[3] === 'A' ? 14 : m[3] === 'K' ? 13 : m[3] === 'Q' ? 12 : m[3] === 'J' ? 11 : parseInt(m[3]);
    out.push({ pos: +m[1], rank: r, suit: '♠♥♦♣'.indexOf(m[2]) });
  }
  return out;
}
// 与 check29（已证可通关）逐字同源的强策略
function choosePlay(cards) {
  if (cards.length >= 5) {
    const suitCnt = [0, 0, 0, 0];
    for (const c of cards) if (c.suit >= 0) suitCnt[c.suit]++;
    for (let su = 0; su < 4; su++) {
      if (suitCnt[su] >= 5) {
        const f = [];
        for (const c of cards) { if (c.suit === su && f.length < 5) f.push(c); }
        return f;
      }
    }
    const seen = new Set();
    const uniq = [];
    for (const c of cards) { if (c.rank >= 2 && c.rank <= 14 && !seen.has(c.rank)) { seen.add(c.rank); uniq.push(c); } }
    const desc = [...uniq].sort((a, b) => b.rank - a.rank);
    for (let low = 10; low >= 2; low--) {
      const run = [];
      const usedRanks = new Set();
      for (const c of desc) {
        if (c.rank >= low && c.rank <= low + 4 && !usedRanks.has(c.rank)) { usedRanks.add(c.rank); run.push(c); }
        if (run.length === 5) break;
      }
      if (run.length === 5) return run;
    }
    {
      const run = [];
      const usedVals = new Set();
      for (const c of desc) {
        const vv = c.rank === 14 ? 1 : c.rank;
        if (vv >= 1 && vv <= 5 && !usedVals.has(vv)) { usedVals.add(vv); run.push(c); }
        if (run.length === 5) break;
      }
      if (run.length === 5) return run;
    }
  }
  const groups = [];
  for (const c of cards) {
    let g = groups.find(lg => lg[0].rank === c.rank);
    if (!g) { g = []; groups.push(g); }
    g.push(c);
  }
  const stable = groups.map((g, i) => ({ g, i }));
  stable.sort((a, b) => (b.g.length !== a.g.length) ? (b.g.length - a.g.length) : (b.g[0].rank - a.g[0].rank));
  const sorted = stable.map(x => x.g);
  const best = sorted[0], second = sorted[1] || null;
  if (best.length >= 3) return best;
  if (best.length === 2 && second && second.length === 2) return [...best, ...second];
  if (best.length === 2 && best[0].rank >= 8) return best;
  return [];
}
function chooseKeepIds(cards) {
  const idx = cards.map((c, i) => ({ i, rank: c.rank }));
  idx.sort((a, b) => b.rank - a.rank);
  return new Set(idx.slice(0, Math.min(3, idx.length)).map(x => x.i));
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

  b1.chat(`/balatro play ${SEED}`);
  await sleep(3200);

  // ---- 推进：小盲 → shop1 买星星 → 大盲 → shop2 → Boss(pyschic) ----
  let held = false, stage = 0;
  for (let bl = 0; bl < 2; bl++) {
    for (let i = 0; i < 40; i++) {
      const st = await statusSnapshot(b1, 1200);
      if (!st) { b1.chat('/balatro go'); await sleep(2000); continue; }
      if (st.phase === 'BLIND_SELECT') { b1.chat('/balatro go'); await sleep(2100); continue; }
      if (st.phase === 'SHOP') break;
      if (st.phase !== 'ROUND' || !st.cards || !st.cards.length) continue;
      const play = choosePlay(st.cards);
      const list = inters(b1);
      if (!list.length) continue;
      const proj = boardAxes(b1, list);
      const r = classifyRound(list, proj);
      if (!r.primary || r.hand.length < 2) continue;
      if (play.length) {
        b1.chat('/balatro playcard ' + play.map(c => c.pos).join(' '));
        await sleep(2000);
      } else if (st.discards > 0) {
        const keep = chooseKeepIds(st.cards);
        const dis = st.cards.filter((c, k) => !keep.has(k));
        if (dis.length) { b1.chat('/balatro disc ' + dis.map(c => c.pos).join(' ')); await sleep(1800); }
        else { const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]); b1.chat(`/balatro playcard ${top.pos}`); await sleep(2000); }
      } else {
        const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]);
        b1.chat(`/balatro playcard ${top.pos}`);
        await sleep(2000);
      }
    }
    const stS = await statusSnapshot(b1);
    if (!stS || stS.phase !== 'SHOP') break;
    stage++;
    if (bl === 0 && !held) {
      // shop1：买星星（预验 shop1=[tarot/star$3, joker$4]）
      const lines = [];
      const onMsg = (json) => lines.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro shop');
      await sleep(1500);
      b1.removeListener('message', onMsg);
      let idx = -1;
      for (const l of lines) {
        const m = l.match(/^\[(\d+)\] tarot 星星.*\$(\d+)/);
        if (m && !l.includes('已售')) { idx = +m[1]; break; }
        const m2 = l.match(/^\[(\d+)\] (?:tarot|spectral) ([^\s§]+).*\$(\d+)/);
        if (m2 && ['魔术师','皇后','教皇','力量','倒吊人','恋人','战车','正义','恶魔','高塔','护符','光环','似曾相识','恍惚','灵媒','地穴生物','死神','星星','月亮','太阳','世界'].includes(m2[2]) && !l.includes('已售')) { idx = +m2[1]; break; }
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
    }
    // next → 下一个盲注
    b1.chat('/balatro next');
    await sleep(2100);
  }
  // go 进 Boss
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
  step('boss_psychic_round_reached', inBoss, inBoss ? `R236X51 Boss 回合（psychic）held=${held}` : `phase=${stB && stB.phase} blind=${stB && stB.blind} stage=${stage}`);

  if (inBoss) {
    const w1 = [];
    {
      const onMsg = (json) => w1.push(json.toString());
      b1.on('message', onMsg);
      b1.chat('/balatro playcard 1');
      await sleep(1700);
      b1.removeListener('message', onMsg);
    }
    step('psychic_rejects_short_play', w1.some(s => s.includes('通灵者')), w1.join('|').slice(0, 80));

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
        step('boss_confirm_at_ids', !!useCmd && /@\d+/.test(useCmd), useCmd || '无 @ids');
        if (useCmd && /@\d+/.test(useCmd)) {
          const before = chatLog.length;
          b1.chat(useCmd);
          await sleep(2100);
          const w = chatLog.slice(before).join('\n');
          step('boss_use_succeeded', w.includes('使用成功') && !w.includes('请选择'), w.slice(-90));
          const list3 = inters(b1);
          if (list3.length) {
            const proj3 = boardAxes(b1, list3);
            const r3 = classifyRound(list3, proj3);
            const afterInfo = await shiftInfo(b1, r3.hand[0]);
            step('boss_effect_applied', beforeInfo !== afterInfo,
                `before=${(beforeInfo || '无').slice(0, 24)} after=${(afterInfo || '无').slice(0, 24)}`);
          }
        }
      } else step('boss_confirm_at_ids', false, '未见消耗品行');
    } else {
      step('boss_confirm_req_line', false, '未持有目标类（预验失配）');
      step('boss_confirm_at_ids', false, '未持有');
      step('boss_use_succeeded', false, '未持有');
      step('boss_effect_applied', false, '未持有');
    }
  }

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check33-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check33-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
