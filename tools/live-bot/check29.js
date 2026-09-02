// R232 实机验证脚本 29：4 假人并发同种子(W26A1446)完整通关竞速
// 断言：①四局并发交错下逐手结果序列完全一致（多实例种子复现）；
//      ②四局全部通关且各 bot EconomyReward Δ 各=204（互不串扰）；
//      ③满载期间 TPS≥19；④stats 恰 +4 行、各 bot wins 各 +1。
const mineflayer = require('mineflayer');
const fs = require('fs');
const crypto = require('crypto');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SEED = 'W26A1446';
const SRV = 'F:/paper-test-26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}

const NAMES = ['BalBot', 'BalBot2', 'AdvBot3', 'AdvBot4'];

function offlineUuid(name) {
  const b = crypto.createHash('md5').update('OfflinePlayer:' + name, 'binary').digest();
  b[6] = (b[6] & 0x0f) | 0x30; b[8] = (b[8] & 0x3f) | 0x80; // Java nameUUIDFromBytes = MD5 v3
  const hex = b.toString('hex');
  return [hex.slice(0, 8), hex.slice(8, 12), hex.slice(12, 16), hex.slice(16, 20), hex.slice(20, 32)].join('-');
}
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { ante: +m[1], blind: m[2], phase: m[3], score: +m[4], target: +m[5], hands: +m[6], discards: +m[7], money: +m[8] };
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
    await sleep(1300);
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
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 30000); });
  return bot;
}

async function runRace(bot, name, record) {
  // trace：盲注+逐次出牌后的 (blind,score) 序列——同种子四局应完全一致
  const trace = [];
  let lastBlind = '', lastPhase = '', won = false;
  bot.chat(`/balatro play ${SEED}`);
  await sleep(3000);
  const t0 = Date.now();
  let guard = 0, goNulls = 0;
  while (guard++ < 2600 && Date.now() - t0 < 900000) {
    const st = await statusSnapshot(bot);
    if (!st) {
      bot.chat('/balatro go');
      await sleep(2000);
      if (++goNulls > 10) break;
      continue;
    }
    goNulls = 0;
    if (st.phase === 'END') { won = true; break; }
    if (st.phase === 'SHOP') {
      if (lastPhase === 'ROUND') trace.push(`CLR:${lastBlind}`);
      lastPhase = 'SHOP';
      const lines = [];
      const onMsg = (json) => lines.push(json.toString());
      bot.on('message', onMsg);
      bot.chat('/balatro shop');
      await sleep(1500);
      bot.removeListener('message', onMsg);
      const jokerItems = [];
      for (const line of lines) {
        const m = line.match(/^\[(\d+)\] 小丑 ([^\s§]+).*\$(\d+)/);
        if (m && !line.includes('已售')) jokerItems.push({ idx: +m[1], price: +m[3] });
      }
      const stM = await statusSnapshot(bot, 1100);
      for (const it of jokerItems) {
        if (stM && it.price > stM.money) continue;
        bot.chat(`/balatro buy ${it.idx}`);
        await sleep(1400);
      }
      bot.chat('/balatro next');
      await sleep(1900);
      continue;
    }
    if (st.phase === 'BLIND_SELECT') { bot.chat('/balatro go'); await sleep(2000); lastPhase = 'BLIND_SELECT'; continue; }
    if (st.phase === 'ROUND' && st.cards && st.cards.length) {
      if (lastPhase !== 'ROUND' || lastBlind !== st.blind) trace.push(`BLIND:${st.blind}/${st.target}`);
      lastPhase = 'ROUND'; lastBlind = st.blind;
      const play = choosePlay(st.cards);
      if (play.length) {
        bot.chat('/balatro playcard ' + play.map(c => c.pos).join(' '));
        await sleep(1900);
        const st2 = await statusSnapshot(bot, 1200);
        if (st2 && st2.phase === 'ROUND') trace.push(`${st.blind}=${st2.score}`);
        else if (st2 && st2.phase === 'SHOP') trace.push(`${st.blind}=CLEARED`);
      } else if (st.discards > 0) {
        const keep = chooseKeepIds(st.cards);
        const dis = st.cards.filter((c, i) => !keep.has(i));
        if (dis.length) { bot.chat('/balatro disc ' + dis.map(c => c.pos).join(' ')); await sleep(1700); }
        else { const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]); bot.chat(`/balatro playcard ${top.pos}`); await sleep(1900); }
      } else {
        const top = st.cards.reduce((a, c) => c.rank > a.rank ? c : a, st.cards[0]);
        bot.chat(`/balatro playcard ${top.pos}`);
        await sleep(1900);
      }
      continue;
    }
    await sleep(1100);
  }
  record.won = won;
  record.trace = trace;
  record.dur = ((Date.now() - t0) / 1000 | 0);
}

(async () => {
  const bots = [];
  for (const nm of NAMES) bots.push(await makeBot(nm));
  for (const bt of bots) bt.setControlState('forward', true);
  await sleep(2400);
  for (const bt of bots) { bt.setControlState('forward', false); await bt.look(Math.PI, 0, false); }
  await sleep(600);
  for (const bt of bots) bt.chat('/balatro quit');
  await sleep(1200);

  const statsPath = SRV + '/plugins/Balatro/stats.txt';
  const winsPath = SRV + '/plugins/Balatro/wins.txt';
  const rd = (p) => fs.existsSync(p) ? fs.readFileSync(p, 'utf-8') : '';
  const lc = (s) => s.trim() ? s.trimEnd().split('\n').length : 0;
  const stats0 = lc(rd(statsPath));
  const wins0 = {};
  for (const nm of NAMES) {
    const m = rd(winsPath).match(new RegExp(offlineUuid(nm) + '=(\\d+)'));
    wins0[nm] = m ? +m[1] : 0;
  }
  const bal0 = {};
  for (let i = 0; i < bots.length; i++) { bal0[NAMES[i]] = await myBalance(bots[i]); await sleep(250); }
  step('baseline', Object.values(bal0).every(v => v !== null), JSON.stringify(bal0) + ` stats=${stats0} wins=${JSON.stringify(wins0)}`);

  // TPS 采样器（BalBot 兼跑）
  const tpsSamples = [];
  let tpsStop = false;
  (async () => {
    const b = bots[0];
    while (!tpsStop) {
      await sleep(55000);
      if (tpsStop) break;
      const got = [];
      const onMsg = (json) => got.push(json.toString());
      b.on('message', onMsg);
      b.chat('/tps');
      await sleep(1600);
      b.removeListener('message', onMsg);
      const line = got.find(s => /TPS/.test(s));
      if (line) {
        const nums = [...line.matchAll(/(\d+\.\d+)/g)].map(m => parseFloat(m[1]));
        if (nums.length) { tpsSamples.push(Math.min(...nums)); log('TPS sample:', Math.min(...nums)); }
      }
    }
  })();

  const records = {};
  NAMES.forEach(nm => records[nm] = {});
  const t0 = Date.now();
  await Promise.all(bots.map((bt, i) => runRace(bt, NAMES[i], records[NAMES[i]])));
  log('race done in', ((Date.now() - t0) / 1000 | 0), 's');
  await sleep(2500);

  // 断言 1：四局逐手结果序列完全一致
  const traces = NAMES.map(nm => (records[nm].trace || []).join('|'));
  const allSame = traces.every(t => t === traces[0]) && traces[0].length > 100;
  step('seed_reproducibility_identical', allSame,
      allSame ? `四局 trace 一致（${traces[0].split('|').length} 段）` : `不一致！长度=${traces.map(t => t.split('|').length).join('/')}`);

  // 断言 2：全部通关 + 各自 Δ=204
  const bal1 = {};
  for (let i = 0; i < bots.length; i++) { bal1[NAMES[i]] = await myBalance(bots[i]); await sleep(250); }
  let ecoOk = true, ecoDet = [];
  for (const nm of NAMES) {
    const d = bal1[nm] - bal0[nm];
    const ok = records[nm].won && Math.abs(d - 204) < 0.001;
    ecoOk = ecoOk && ok;
    ecoDet.push(`${nm}:won=${records[nm].won}Δ${d.toFixed(1)}${records[nm].dur ? '/' + records[nm].dur + 's' : ''}`);
  }
  step('four_wins_economy_204', ecoOk, ecoDet.join(' ; '));

  // 断言 3：TPS
  const minTps = tpsSamples.length ? Math.min(...tpsSamples) : -1;
  step('tps_under_race', minTps >= 19.0, `样本=${tpsSamples.join(',')} min=${minTps}`);

  // 断言 4：落盘
  await sleep(3000);
  const stats1 = lc(rd(statsPath));
  const wins1 = {};
  for (const nm of NAMES) {
    const m = rd(winsPath).match(new RegExp(offlineUuid(nm) + '=(\\d+)'));
    wins1[nm] = m ? +m[1] : 0;
  }
  step('stats_plus4', stats1 - stats0 === 4, `+${stats1 - stats0} 行（期望 4）`);
  const winsEach = NAMES.every(nm => wins1[nm] - wins0[nm] === 1);
  step('wins_each_plus1', winsEach, NAMES.map(nm => `${nm}+${wins1[nm] - wins0[nm]}`).join(' '));

  tpsStop = true;
  for (const bt of bots) bt.chat('/balatro quit');
  await sleep(1200);
  for (const bt of bots) bt.quit();
  await sleep(1000);
  fs.writeFileSync(__dirname + '/check29-results.json',
      JSON.stringify({ results, traces: NAMES.map(nm => ({ name: nm, trace: records[nm].trace })), bal0, bal1, tpsSamples }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check29-results.json', JSON.stringify({ results }, null, 2)); process.exit(2); });
