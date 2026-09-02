// R223 #14：Vault 经济接入验证（前置：服务器已装 Vault+EssentialsX 并重启）
// 验证：①启用日志「已接入 Vault 经济」②赢盲后 Vault 余额增加（BlindResult→Reward→Economy 链）
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) { results.push({ name, ok, detail }); log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`); }

const val = (r) => r === 'A' ? 14 : r === 'K' ? 13 : r === 'Q' ? 12 : r === 'J' ? 11 : r === '10' ? 10 : parseInt(r);
function decide(handStr) {
  const cards = [...handStr.matchAll(/[♠♥♦♣]\s*([AJQK]|10|\d)/g)].map(t => t[1]);
  const cnt = {};
  cards.forEach((r, i) => { (cnt[r] = cnt[r] || []).push(i); });
  const groups = Object.values(cnt).sort((a, b) => b.length - a.length || val(cards[b[0]]) - val(cards[a[0]]));
  const best = groups[0] || [], second = groups[1] || [];
  if (best.length >= 3) return { play: best, discard: [] };
  if (best.length === 2 && second.length === 2) return { play: [...best, ...second], discard: [] };
  if (best.length === 2 && val(cards[best[0]]) >= 11) return { play: best, discard: [] };
  const keep = cards.map((r, i) => ({ i, v: val(r) })).sort((a, b) => b.v - a.v).slice(0, 3).map(o => o.i);
  return { play: [], discard: cards.map((r, i) => i).filter(i => !keep.includes(i)) };
}
function inters(bot, r = 16) {
  const o = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'interaction' && e.position && e.metadata && +e.metadata[8] > 0 && e.position.distanceTo(bot.entity.position) <= r) o.push(e);
  }
  return o;
}
function boardAxes(bot, list) {
  const p = bot.entity.position;
  const cx = list.reduce((s, e) => s + e.position.x, 0) / list.length;
  const cz = list.reduce((s, e) => s + e.position.z, 0) / list.length;
  let fx = cx - p.x, fz = cz - p.z;
  const fl = Math.hypot(fx, fz) || 1; fx /= fl; fz /= fl;
  const rx = -fz, rz = fx;
  return (e) => (e.position.x - cx) * rx + (e.position.z - cz) * rz;
}
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+)/);
      if (m) v = { phase: m[1], discards: +m[5] };
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
// /balance（EssentialsX）解析
function vaultBalance(bot, waitMs = 1800) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/(?:余额|Balance)[：:\s]*\$?([0-9][0-9,.]*)/i);
      if (m) v = parseFloat(m[1].replace(/,/g, ''));
    };
    bot.on('message', onMsg);
    bot.chat('/balance');
    await sleep(waitMs);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
}

(async () => {
  const b1 = await makeBot('BalBot');
  // ① 余额命令可用（Vault+EssentialsX 链路活）
  const bal0 = await vaultBalance(b1);
  step('vault_balance_cmd', bal0 !== null, `初始 Vault 余额 = ${bal0}`);

  // ② 打到清盲 → 余额应增加
  let inShop = false, runNo = 0;
  while (!inShop && runNo < 5) {
    runNo++;
    await b1.look(Math.PI, 0, false).catch(() => {});
    b1.chat(`/balatro play V${runNo}`);
    await sleep(2800);
    for (let i = 0; i < 16; i++) {
      const st = await statusSnapshot(b1);
      if (!st) break;
      if (st.phase === 'SHOP') { inShop = true; break; }
      if (st.phase !== 'ROUND') break;
      if (!st.handStr) break;
      const d = decide(st.handStr);
      const list = inters(b1);
      if (!list.length) break;
      const proj = boardAxes(b1, list);
      const ys = list.map(x => x.position.y);
      const min = Math.min(...ys);
      const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, b) => proj(a) - proj(b));
      const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
      if (!btns.length || hand.length < 2) break;
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { b1.activateEntity(hand[pi]); await sleep(360); }
        b1.activateEntity(btns[btns.length - 1]);
        await sleep(1500);
        continue;
      }
      if (!d.play.length) continue;
      for (const pi of d.play) { b1.activateEntity(hand[pi]); await sleep(360); }
      b1.activateEntity(btns[0]);
      await sleep(1900);
    }
    if (!inShop) { b1.chat('/balatro quit'); await sleep(1300); }
  }
  step('vault_cleared_blind', inShop, `${runNo} 局内清盲`);
  if (inShop) {
    await sleep(1500);
    const bal1 = await vaultBalance(b1);
    step('vault_reward_credited', bal1 !== null && bal0 !== null && bal1 > bal0, `清盲后 Vault 余额 ${bal0} -> ${bal1}（BlindResult→Reward→Economy 链生效）`);
  }

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check19-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);

  async function makeBot(u) {
    const bot = mineflayer.createBot({ host: HOST, port: PORT, username: u, version: VER, auth: 'offline' });
    bot.on('kicked', r => log(`${u} KICKED:`, JSON.stringify(r).slice(0, 150)));
    bot.on('error', e => log(`${u} ERROR:`, e.message));
    bot.on('message', (json) => { const s = json.toString(); if (s && /余额|Balance/i.test(s)) log('CHAT', s.slice(0, 90)); });
    await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
    return bot;
  }
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check19-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
