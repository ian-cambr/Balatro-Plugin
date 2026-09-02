// R230 实机验证脚本 26：命令驱动复现离线搜索到的通关种子（W26A1446）
// 策略与 SeedSearchScratchTest Java 镜像逐字对应：同花/顺子/三条/两对/高对出牌，
// 否则留最高 3 张弃其余钓鱼，无弃牌出最高单张；商店按槽位序购买全部买得起的小丑。
// 断言：①首个底注清除时 Δ == 3×1 + 10；②整局通关后 Δ == 24×1 + 8×10 + 100 = 204。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SEED = process.argv[2] || 'W26A1446';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}

function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { ante: +m[1], blind: m[2], phase: m[3], hands: +m[6], discards: +m[7] };
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
function parseHand(str) {
  // "1:♠J  2:♣10  3:♥A" → [{pos:1, rank:11, suit:'♠'}, ...]（石头/异常显示按 rank 0 处理）
  const out = [];
  for (const m of str.matchAll(/(\d+):([♠♥♦♣])((?:10)|[AJQK2-9])/g)) {
    const r = m[3] === 'A' ? 14 : m[3] === 'K' ? 13 : m[3] === 'Q' ? 12 : m[3] === 'J' ? 11 : parseInt(m[3]);
    out.push({ pos: +m[1], rank: r, suit: '♠♥♦♣'.indexOf(m[2]) });
  }
  return out;
}
function myBalance(bot) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/([\d,]+(?:\.\d+)?)/);
      if (m && /余额|alance/i.test(s)) v = parseFloat(m[1].replace(/,/g, ''));
    };
    bot.on('message', onMsg);
    bot.chat('/balance');
    await sleep(1400);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
}

// ==== Java choosePlay 镜像 ====
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
  idx.sort((a, b) => b.rank - a.rank); // JS 稳定排序 == Java TimSort
  return new Set(idx.slice(0, Math.min(3, idx.length)).map(x => x.i));
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 160)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { const s = json.toString(); if (s && !/^\[Interaction\]|操作说明|右键|本局信息|商店 ===|补充包：/.test(s)) log(`CHAT ${s.slice(0, 110)}`); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);
  b1.chat('/balatro quit'); await sleep(1000);

  const balStart = await myBalance(b1);
  step('start_balance', balStart !== null, `$${balStart}`);
  let balAfterFirstAnte = null;

  b1.chat(`/balatro play ${SEED}`);
  await sleep(3500);

  let won = false, lost = false, blindClears = 0, lastPhase = '', lastBlind = '';
  const t0 = Date.now();
  let guard = 0, goNulls = 0;
  while (guard++ < 1500 && Date.now() - t0 < 600000) {
    const st = await statusSnapshot(b1);
    if (!st) {
      // BLIND_SELECT 等阶段的 status 无 score/hands 字段（正则不匹配）——发 go 兜底推进；
      // 连续多轮仍无会话信息才判负（失败局 finishRun 后无会话）。
      b1.chat('/balatro go');
      await sleep(2200);
      if (++goNulls > 6) { lost = true; break; }
      continue;
    }
    goNulls = 0;
    if (st.phase === 'END') {
      // 区分通关/失败：通关会提示 endless；直接看后续无会话即终局
      const end = await new Promise(async (resolve) => {
        let msg = '';
        const onMsg = (json) => { msg += json.toString() + '\n'; };
        b1.on('message', onMsg);
        await sleep(1500);
        b1.removeListener('message', onMsg);
        resolve(msg);
      });
      won = /通关|胜利|endless|无尽/.test(end) || blindClears >= 24;
      lost = !won;
      break;
    }
    if (st.phase === 'SHOP') {
      if (lastPhase === 'ROUND') {
        blindClears++;
        log(`清盲 #${blindClears}（${lastBlind}）`);
        if (lastBlind === 'boss' && balAfterFirstAnte === null && st.ante === 1) {
          balAfterFirstAnte = await myBalance(b1);
        }
      }
      lastPhase = 'SHOP';
      // 商店：按槽位序买全部买得起的小丑（与模拟一致；槽满时引擎安全拒绝）
      const lines = [];
      {
        const onMsg = (json) => lines.push(json.toString());
        b1.on('message', onMsg);
        b1.chat('/balatro shop');
        await sleep(1600);
        b1.removeListener('message', onMsg);
      }
      const jokerItems = [];
      for (const line of lines) {
        const m = line.match(/^\[(\d+)\] 小丑 ([^\s§]+).*\$(\d+)/);
        if (m && !line.includes('已售')) jokerItems.push({ idx: +m[1], price: +m[3] });
      }
      const stM = await statusSnapshot(b1, 1200);
      for (const it of jokerItems) {
        if (stM && it.price > stM.money) continue;
        b1.chat(`/balatro buy ${it.idx}`);
        await sleep(1500);
      }
      b1.chat('/balatro next');
      await sleep(2000);
      continue;
    }
    if (st.phase === 'BLIND_SELECT') {
      b1.chat('/balatro go');
      await sleep(2200);
      lastPhase = 'BLIND_SELECT';
      continue;
    }
    if (st.phase === 'ROUND' && st.cards && st.cards.length) {
      lastPhase = 'ROUND'; lastBlind = st.blind;
      const play = choosePlay(st.cards);
      if (play.length) {
        b1.chat('/balatro playcard ' + play.map(c => c.pos).join(' '));
        await sleep(2100);
      } else if (st.discards > 0) {
        const keep = chooseKeepIds(st.cards);
        const dis = st.cards.filter((c, i) => !keep.has(i));
        if (dis.length) {
          b1.chat('/balatro disc ' + dis.map(c => c.pos).join(' '));
          await sleep(1900);
        } else {
          const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]);
          b1.chat(`/balatro playcard ${top.pos}`);
          await sleep(2100);
        }
      } else {
        const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]);
        b1.chat(`/balatro playcard ${top.pos}`);
        await sleep(2100);
      }
      continue;
    }
    await sleep(1200);
  }

  const balEnd = await myBalance(b1);
  const delta = balEnd - balStart;
  const delta1 = balAfterFirstAnte === null ? null : balAfterFirstAnte - balStart;
  step('first_ante_exact', delta1 !== null && Math.abs(delta1 - 13) < 0.001,
      `首个底注 Δ=${delta1 === null ? 'null' : delta1.toFixed(1)}（期望 3×1+10=13）`);
  step('run_won', won, `won=${won} blindClears=${blindClears} 用时${((Date.now() - t0) / 1000 | 0)}s`);
  step('full_run_economy_exact', won && Math.abs(delta - 204) < 0.001,
      `整局 Δ=${delta.toFixed(1)}（期望 24×1+8×10+100=204）`);

  b1.chat('/balatro quit');
  await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check26-results.json', JSON.stringify({ results, seed: SEED, blindClears, balStart, balAfterFirstAnte, balEnd }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check26-results.json', JSON.stringify({ results }, null, 2)); process.exit(2); });
