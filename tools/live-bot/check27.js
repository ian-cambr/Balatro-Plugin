// R231 实机验证脚本 27：通关(W26A1446)后进入无尽模式——长局下 EconomyReward 累积公式
// 与 stats.txt/wins.txt 落盘边界。
// 断言：①通关时 Δ=204（R230 已证）+ wins.txt 恰 +1 + stats +1 行(won=true)；
// ②无尽盲注继续 $1/盲、$10/底注累积；③无尽失败局不再发通关奖、不再计数——
//   终局 Δ == 清盲总数×1 + 底注总数×10 + 100；stats 终局共 +2 行(win + endless loss)；wins 恰 +1。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SEED = process.argv[2] || 'W26A1446';
const SRV = 'F:/paper-test-26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}
const chatLog = [];

function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { ante: +m[1], blind: m[2], phase: m[3], hands: +m[6], discards: +m[7], money: +m[8] };
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
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 160)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { const s = json.toString(); if (s) chatLog.push(s); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

const readFileSafe = (p) => fs.existsSync(p) ? fs.readFileSync(p, 'utf-8') : '';
const lineCount = (s) => s.trim() ? s.trimEnd().split('\n').length : 0;
const winsCount = (s, name) => {
  // wins.txt 格式 uuid=count；BalBot offline UUID
  const crypto = require('crypto');
  const hash = crypto.createHash('md5').update('OfflinePlayer:' + name, 'binary').digest('hex');
  const uuid = [hash.slice(0, 8), hash.slice(8, 12), hash.slice(12, 16), hash.slice(16, 20), hash.slice(20, 32)].join('-');
  const m = s.match(new RegExp(uuid + '=(\\d+)'));
  return m ? +m[1] : 0;
};

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);
  b1.chat('/balatro quit'); await sleep(1000);

  const statsPath = SRV + '/plugins/Balatro/stats.txt';
  const winsPath = SRV + '/plugins/Balatro/wins.txt';
  const stats0 = lineCount(readFileSafe(statsPath));
  const wins0 = winsCount(readFileSafe(winsPath), 'BalBot');
  const bal0 = await myBalance(b1);
  step('baseline', bal0 !== null, `bal=$${bal0} statsLines=${stats0} wins=${wins0}`);

  b1.chat(`/balatro play ${SEED}`);
  await sleep(3500);

  let blindClears = 0, anteClears = 0, lastPhase = '', lastBlind = '', wonAt8 = false, endlessLost = false;
  let phase = 'run'; // run → endless
  const t0 = Date.now();
  let guard = 0, goNulls = 0;

  while (guard++ < 2600 && Date.now() - t0 < 900000) {
    const st = await statusSnapshot(b1);
    if (!st) {
      // BLIND_SELECT 无 score 字段 / 或终局无会话——go 兜底推进；连续无效才判终局
      b1.chat('/balatro go');
      await sleep(2000);
      if (++goNulls > 10) {
        if (phase === 'endless') { endlessLost = true; }
        break;
      }
      continue;
    }
    goNulls = 0;
    if (st.phase === 'END') {
      if (!wonAt8) {
        wonAt8 = true; // 种子保证通关
        // 通关瞬间快照
        await sleep(1500);
        break; // 跳出后处理 endless 进入
      }
      endlessLost = true;
      break;
    }
    if (st.phase === 'SHOP') {
      if (lastPhase === 'ROUND') {
        blindClears++;
        if (lastBlind === 'boss') anteClears++;
        log(`清盲 #${blindClears}（${lastBlind}，ante=${st.ante}）`);
      }
      lastPhase = 'SHOP';
      const lines = [];
      {
        const onMsg = (json) => lines.push(json.toString());
        b1.on('message', onMsg);
        b1.chat('/balatro shop');
        await sleep(1500);
        b1.removeListener('message', onMsg);
      }
      const jokerItems = [];
      for (const line of lines) {
        const m = line.match(/^\[(\d+)\] 小丑 ([^\s§]+).*\$(\d+)/);
        if (m && !line.includes('已售')) jokerItems.push({ idx: +m[1], price: +m[3] });
      }
      const stM = await statusSnapshot(b1, 1100);
      for (const it of jokerItems) {
        if (stM && it.price > stM.money) continue;
        b1.chat(`/balatro buy ${it.idx}`);
        await sleep(1400);
      }
      b1.chat('/balatro next');
      await sleep(1900);
      continue;
    }
    if (st.phase === 'BLIND_SELECT') {
      b1.chat('/balatro go');
      await sleep(2100);
      lastPhase = 'BLIND_SELECT';
      continue;
    }
    if (st.phase === 'ROUND' && st.cards && st.cards.length) {
      lastPhase = 'ROUND'; lastBlind = st.blind;
      const play = choosePlay(st.cards);
      if (play.length) {
        b1.chat('/balatro playcard ' + play.map(c => c.pos).join(' '));
        await sleep(1900);
      } else if (st.discards > 0) {
        const keep = chooseKeepIds(st.cards);
        const dis = st.cards.filter((c, i) => !keep.has(i));
        if (dis.length) { b1.chat('/balatro disc ' + dis.map(c => c.pos).join(' ')); await sleep(1700); }
        else { const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]); b1.chat(`/balatro playcard ${top.pos}`); await sleep(1900); }
      } else {
        const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]);
        b1.chat(`/balatro playcard ${top.pos}`);
        await sleep(1900);
      }
      continue;
    }
    await sleep(1100);
  }

  step('won_ante8', wonAt8, `blindClears=${blindClears} anteClears=${anteClears} 用时${((Date.now() - t0) / 1000 | 0)}s`);
  // 通关瞬间落盘与到账
  await sleep(2500);
  const balWin = await myBalance(b1);
  const statsWin = lineCount(readFileSafe(statsPath));
  const winsWin = winsCount(readFileSafe(winsPath), 'BalBot');
  const dWin = balWin - bal0;
  step('win_economy_exact', Math.abs(dWin - 204) < 0.001, `通关 Δ=${dWin.toFixed(1)}（期望 204）`);
  step('win_stats_row', statsWin - stats0 === 1, `stats +${statsWin - stats0} 行（期望 1）`);
  step('win_counter_once', winsWin - wins0 === 1, `wins +${winsWin - wins0}（期望恰 1）`);

  // ---- 进入无尽 ----
  b1.chat('/balatro endless');
  await sleep(3000);
  phase = 'endless';
  lastPhase = ''; goNulls = 0;
  const tEndless = Date.now();
  let endlessEntered = false;
  guard = 0;
  while (guard++ < 2600 && Date.now() - tEndless < 540000) {
    const st = await statusSnapshot(b1);
    if (!st) {
      b1.chat('/balatro go');
      await sleep(2000);
      if (++goNulls > 8) { endlessLost = true; break; }
      continue;
    }
    goNulls = 0;
    if (st.ante >= 9) endlessEntered = true;
    if (st.phase === 'END') { endlessLost = true; break; }
    if (st.phase === 'SHOP') {
      if (lastPhase === 'ROUND') {
        blindClears++;
        if (lastBlind === 'boss') anteClears++;
        log(`无尽清盲 #${blindClears}（${lastBlind}，ante=${st.ante}）`);
      }
      lastPhase = 'SHOP';
      b1.chat('/balatro next');
      await sleep(1900);
      continue;
    }
    if (st.phase === 'BLIND_SELECT') { b1.chat('/balatro go'); await sleep(2100); lastPhase = 'BLIND_SELECT'; continue; }
    if (st.phase === 'ROUND' && st.cards && st.cards.length) {
      lastPhase = 'ROUND'; lastBlind = st.blind;
      const play = choosePlay(st.cards);
      if (play.length) { b1.chat('/balatro playcard ' + play.map(c => c.pos).join(' ')); await sleep(1900); }
      else if (st.discards > 0) {
        const keep = chooseKeepIds(st.cards);
        const dis = st.cards.filter((c, i) => !keep.has(i));
        if (dis.length) { b1.chat('/balatro disc ' + dis.map(c => c.pos).join(' ')); await sleep(1700); }
        else { const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]); b1.chat(`/balatro playcard ${top.pos}`); await sleep(1900); }
      } else { const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]); b1.chat(`/balatro playcard ${top.pos}`); await sleep(1900); }
      continue;
    }
    await sleep(1100);
  }

  await sleep(3000);
  const balEnd = await myBalance(b1);
  const statsEnd = lineCount(readFileSafe(statsPath));
  const winsEnd = winsCount(readFileSafe(winsPath), 'BalBot');
  const expect = blindClears * 1 + anteClears * 10 + 100;
  const dEnd = balEnd - bal0;
  step('endless_entered', endlessEntered, endlessEntered ? 'ante>=9' : '未能进入无尽');
  step('endless_economy_formula', Math.abs(dEnd - expect) < 0.001,
      `终局 Δ=${dEnd.toFixed(1)} == 清盲${blindClears}×1+底注${anteClears}×10+100=${expect}（无尽失败不发通关奖）`);
  const endlessLostRow = endlessLost && statsEnd - stats0 === 2;
  step('endless_stats_boundary', statsEnd - stats0 === (endlessLost ? 2 : 1),
      `stats 总 +${statsEnd - stats0} 行（endlessLost=${endlessLost}，期望 ${endlessLost ? 2 : 1}：win + endless loss）`);
  step('win_counter_not_double', winsEnd - wins0 === 1, `wins 终局 +${winsEnd - wins0}（恰 1，无尽失败不重复计数）`);

  b1.chat('/balatro quit');
  await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check27-results.json', JSON.stringify({ results, seed: SEED, blindClears, anteClears, endlessLost, endlessEntered }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check27-results.json', JSON.stringify({ results }, null, 2)); process.exit(2); });
