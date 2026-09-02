// R229 实机验证脚本 25：Vault+EssentialsX 下 3 假人并发清盲
// 断言：①每假人 Δ余额 == 清盲数×$1 + 清底注数×$10（EconomyReward 三档精确）；
//       ②失败局不发钱（计入公式自然验证）；③stats.txt 新增行数 == 失败局数且全部 7 段合法；
//       ④wins.txt 无变化（无通关）。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SRV = 'F:/paper-test-26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
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
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { ante: +m[1], blind: m[2], phase: m[3], hands: +m[6], discards: +m[7] };
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
/** 自己查余额（EssentialsX /balance），返回数值或 null。 */
function myBalance(bot) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/[\$￥]?\s*([\d,]+(?:\.\d+)?)/);
      if (m && /余额|alance/i.test(s)) v = parseFloat(m[1].replace(/,/g, ''));
    };
    bot.on('message', onMsg);
    bot.chat('/balance');
    await sleep(1400);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
}
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 160)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { const s = json.toString(); if (s && !/^\[Interaction\]|操作说明|右键|本局信息/.test(s)) log(`CHAT<${username}> ${s.slice(0, 120)}`); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 30000); });
  return bot;
}

(async () => {
  const names = ['BalBot', 'BalBot2', 'AdvBot3'];
  const bots = [];
  for (const nm of names) bots.push(await makeBot(nm));
  for (const bt of bots) bt.setControlState('forward', true);
  await sleep(2400);
  for (const bt of bots) { bt.setControlState('forward', false); await bt.look(Math.PI, 0, false); }
  await sleep(600);
  for (const bt of bots) bt.chat('/balatro quit');
  await sleep(1200);

  // 初始余额 + 初始落盘
  const bal0 = {};
  for (let i = 0; i < bots.length; i++) { bal0[names[i]] = await myBalance(bots[i]); await sleep(300); }
  step('initial_balances', Object.values(bal0).every(v => v !== null), JSON.stringify(bal0));

  const statsPath = SRV + '/plugins/Balatro/stats.txt';
  const winsPath = SRV + '/plugins/Balatro/wins.txt';
  const statsBefore = fs.existsSync(statsPath) ? fs.readFileSync(statsPath, 'utf-8') : '';
  const winsBefore = fs.existsSync(winsPath) ? fs.readFileSync(winsPath, 'utf-8') : '';
  const linesBefore = statsBefore ? statsBefore.trimEnd().split('\n').length : 0;

  // 并发打牌协程
  const track = {};
  names.forEach(nm => track[nm] = { blindClears: 0, anteClears: 0, losses: 0, runs: 0 });
  const deadline = Date.now() + 200000;

  await Promise.all(bots.map(async (bt, idx) => {
    const nm = names[idx];
    const t = track[nm];
    while (Date.now() < deadline && t.blindClears < 3 && t.losses < 2) {
      t.runs++;
      bt.chat(`/balatro play E25${nm}_${t.runs}`);
      await sleep(2800);
      let lastPhase = 'ROUND', lastBlind = 'small', noSessionStreak = 0;
      for (let i = 0; i < 40 && Date.now() < deadline; i++) {
        const st = await statusSnapshot(bt, 1100);
        if (!st) { if (++noSessionStreak >= 2) break; continue; }
        noSessionStreak = 0;
        if (st.phase === 'SHOP') {
          if (lastPhase === 'ROUND') {
            t.blindClears++;
            if (lastBlind === 'boss') t.anteClears++;
            log(`${nm} 清盲#${t.blindClears}(${lastBlind})`);
          }
          lastPhase = 'SHOP';
          if (t.blindClears >= 3) break;
          bt.chat('/balatro next'); await sleep(1600);
          bt.chat('/balatro go'); await sleep(2400);
          lastPhase = 'ROUND'; lastBlind = 'big';
          continue;
        }
        if (st.phase === 'ROUND' && st.handStr) {
          lastPhase = 'ROUND'; lastBlind = st.blind;
          const d = decide(st.handStr);
          const list = inters(bt);
          if (!list.length) continue;
          const proj = boardAxes(bt, list);
          const r = classifyRound(list, proj);
          if (!r.primary || r.hand.length < 2) continue;
          if (st.discards > 0 && d.discard.length >= 2) {
            for (const pi of d.discard) { bt.activateEntity(r.hand[pi]); await sleep(360); }
            bt.activateEntity(r.secondary);
            await sleep(1600);
            continue;
          }
          if (!d.play.length) continue;
          for (const pi of d.play) { bt.activateEntity(r.hand[pi]); await sleep(360); }
          bt.activateEntity(r.primary);
          await sleep(2000);
        } else if (st.phase === 'END') {
          t.losses++;
          break;
        }
      }
      // 局末：若仍无会话（失败）计一次损失
      const stEnd = await statusSnapshot(bt, 1000);
      if (!stEnd) t.losses++;
      bt.chat('/balatro quit');
      await sleep(900);
    }
  }));

  // 结束后余额
  await sleep(2500);
  const bal1 = {};
  for (let i = 0; i < bots.length; i++) { bal1[names[i]] = await myBalance(bots[i]); await sleep(300); }

  let ecoOk = true, ecoDetail = [];
  for (const nm of names) {
    const d0 = bal0[nm], d1 = bal1[nm];
    if (d0 === null || d1 === null) { ecoOk = false; ecoDetail.push(`${nm}:余额读取失败`); continue; }
    const delta = d1 - d0;
    const expect = track[nm].blindClears * 1 + track[nm].anteClears * 10;
    const ok = Math.abs(delta - expect) < 0.001;
    ecoOk = ecoOk && ok;
    ecoDetail.push(`${nm}:Δ${delta.toFixed(1)}=清盲${track[nm].blindClears}×1+底注${track[nm].anteClears}×10(期望${expect})${ok ? '' : ' 不符!'}`);
  }
  step('economy_reward_exact', ecoOk, ecoDetail.join(' ; '));
  log('track:', JSON.stringify(track));

  // 落盘一致性
  await sleep(2500);
  const statsAfter = fs.existsSync(statsPath) ? fs.readFileSync(statsPath, 'utf-8') : '';
  const winsAfter = fs.existsSync(winsPath) ? fs.readFileSync(winsPath, 'utf-8') : '';
  const newLines = statsAfter.trimEnd().split('\n').slice(linesBefore).filter(l => l.trim());
  const totalLosses = names.reduce((s, n) => s + track[n].losses, 0);
  const allWellFormed = newLines.every(l => (l.match(/\|/g) || []).length === 6);
  step('stats_rows_match_losses', newLines.length === totalLosses, `新增${newLines.length} 行 == 失败局数${totalLosses}（含部分打出 END 后自动结算）`);
  step('stats_rows_wellformed', allWellFormed, `${newLines.length} 行均 7 段`);
  step('wins_file_unchanged', winsAfter === winsBefore, '无通关 → wins.txt 不变');

  for (const bt of bots) bt.chat('/balatro quit');
  await sleep(1200);
  for (const bt of bots) bt.quit();
  await sleep(1000);
  fs.writeFileSync(__dirname + '/check25-results.json', JSON.stringify({ results, track, bal0, bal1 }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check25-results.json', JSON.stringify({ results }, null, 2)); process.exit(2); });
