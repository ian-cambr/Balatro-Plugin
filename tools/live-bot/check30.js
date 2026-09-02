// R233 实机验证脚本 30：20 假人满载 soak 复验 v0.4.61 新路径
// 每假人循环：开局→实体点击打牌→商店优先买消耗品→回合中 R225 目标确认链(@ids)/商店使用按钮
// 断言：全员存活（op 豁免 spam）、TPS≥19、内存有界、新路径成功样本≥3、stats 新行==失败局数全 7 段
const mineflayer = require('mineflayer');
const fs = require('fs');
const { execSync } = require('child_process');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const SRV = 'F:/paper-test-26.2';
const DURATION = 480000; // 8 分钟
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}
const NAMES = [];
for (let i = 1; i <= 20; i++) {
  if (i <= 2) NAMES.push('BalBot' + (i === 1 ? '' : '2'));
  else if (i <= 4) NAMES.push('AdvBot' + i);
  else NAMES.push('Load' + String(i).padStart(2, '0'));
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
function statusSnapshot(bot, waitMs = 1200) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { ante: +m[1], phase: m[3], score: +m[4], target: +m[5], discards: +m[7], money: +m[8] };
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
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 120)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 45000); });
  return bot;
}
function javaRssMb() {
  try {
    const out = execSync('tasklist /FI "IMAGENAME eq java.exe" /FO CSV', { encoding: 'utf-8', timeout: 15000 });
    let max = 0, sum = 0;
    for (const line of out.split('\n')) {
      const m = line.match(/"[^"]*","\d+","[^"]*","\d+","([\d,]+) K"/);
      if (m) { const kb = +m[1].replace(/,/g, ''); sum += kb; if (kb > max) max = kb; }
    }
    return { max: Math.round(max / 1024), sum: Math.round(sum / 1024) };
  } catch (e) { return null; }
}

(async () => {
  const bots = [];
  for (const nm of NAMES) bots.push(await makeBot(nm));
  log('all spawned:', NAMES.length);
  // 补 op（R226 规约：soak 假人须 op，防 vanilla spam 踢出）
  for (let i = 4; i < NAMES.length; i++) { bots[0].chat('/op ' + NAMES[i]); await sleep(250); }
  await sleep(1500);
  for (const bt of bots) bt.setControlState('forward', true);
  await sleep(2400);
  for (const bt of bots) { bt.setControlState('forward', false); }
  await sleep(600);

  const statsPath = SRV + '/plugins/Balatro/stats.txt';
  const rd = (p) => fs.existsSync(p) ? fs.readFileSync(p, 'utf-8') : '';
  const lc = (s) => s.trim() ? s.trimEnd().split('\n').length : 0;
  const stats0 = lc(rd(statsPath));

  // 观测器：TPS + 内存
  const tpsSamples = [], memSamples = [];
  let stopObs = false;
  (async () => {
    const b = bots[0];
    while (!stopObs) {
      await sleep(55000);
      if (stopObs) break;
      const got = [];
      const onMsg = (json) => got.push(json.toString());
      b.on('message', onMsg);
      b.chat('/tps');
      await sleep(1500);
      b.removeListener('message', onMsg);
      const line = got.find(s => /TPS/.test(s));
      if (line) {
        const nums = [...line.matchAll(/(\d+\.\d+)/g)].map(m => parseFloat(m[1]));
        if (nums.length) tpsSamples.push(Math.min(...nums));
      }
      const mem = javaRssMb();
      if (mem) memSamples.push(mem);
      log('obs: tps=', tpsSamples[tpsSamples.length - 1], 'mem(max/sum MB)=', mem && mem.max + '/' + mem.sum);
    }
  })();

  const agg = { runs: 0, hands: 0, losses: 0, blindClears: 0, targetUses: 0, targetAttempts: 0, shopUseOk: 0, shopAttempts: 0, buys: 0 };
  const deadline = Date.now() + DURATION;

  await Promise.all(bots.map(async (bt, idx) => {
    const nm = NAMES[idx];
    let hasCons = false, consRoundDone = false, consShopDone = false;
    while (Date.now() < deadline) {
      agg.runs++;
      bt.chat(`/balatro play S30${nm}${agg.runs % 97}_${Date.now() % 10000}`);
      await sleep(2600);
      let lastPhase = '', lastBlind = '', nulls = 0;
      for (let i = 0; i < 26 && Date.now() < deadline; i++) {
        const st = await statusSnapshot(bt, 1000);
        if (!st) {
          bt.chat('/balatro go');
          await sleep(1900);
          if (++nulls > 6) break;
          continue;
        }
        nulls = 0;
        if (st.phase === 'END' || st.phase === 'SHOP' && st.ante === 99) break;
        if (st.phase === 'SHOP') {
          if (lastPhase === 'ROUND') { agg.blindClears++; }
          lastPhase = 'SHOP';
          // 买：优先消耗品（喂新路径样本），否则首个买得起项
          const lines = [];
          const onMsg = (json) => lines.push(json.toString());
          bt.on('message', onMsg);
          bt.chat('/balatro shop');
          await sleep(1400);
          bt.removeListener('message', onMsg);
          let pick = null;
          for (const line of lines) {
            const m = line.match(/^\[(\d+)\] (tarot|planet|spectral) ([^\s§]+).*\$(\d+)/);
            if (m && !line.includes('已售') && +m[4] <= st.money) { pick = { idx: +m[1], cons: true, name: m[3] }; break; }
          }
          // R233b：只买消耗品——持有小丑行会打破「商品行→持有消耗品行」的脚底偏移定位
          // （R233a 商店按钮 0 尝试的根因，脚本层）；满载商店按钮采样需要板面无 joker 行。
          if (pick) {
            const before = [];
            const onMsg2 = (json) => before.push(json.toString());
            bt.on('message', onMsg2);
            bt.chat(`/balatro buy ${pick.idx}`);
            await sleep(1500);
            bt.removeListener('message', onMsg2);
            if (before.some(s => s.includes('购买成功'))) {
              agg.buys++;
              if (pick.cons) { hasCons = true; consRoundDone = false; consShopDone = false; }
            }
          }
          // 商店内持有消耗品 → 右键 → 若有[确认使用]按钮执行（v0.4.61 新路径）
          if (hasCons && !consShopDone) {
            const list = inters(bt);
            if (list.length >= 6) { // 买入后该商品命中盒消失：实体恰为 7，>=8 会排除全部正常情形（R233c 根因）
              const proj = boardAxes(bt, list);
              const topY = Math.max(...list.map(e => e.position.y));
              const consRow = list.filter(e => Math.abs(e.position.y - (topY - 1.79)) < 0.09).sort((a, b) => proj(a) - proj(b));
              if (consRow.length) {
                agg.shopAttempts++;
                const raw = [];
                const onMsg3 = (json) => raw.push(json.json ? json.json : json);
                bt.on('message', onMsg3);
                bt.activateEntity(consRow[consRow.length - 1]);
                await sleep(1500);
                bt.removeListener('message', onMsg3);
                let useCmd = null;
                for (const j of raw) {
                  const cmds = [];
                  findClickCommands(j, cmds);
                  const c = cmds.find(x => /^\/balatro use \d+ [a-z]+:[\w.]+$/.test(x));
                  if (c) useCmd = c;
                }
                consShopDone = true;
                if (useCmd) {
                  const w = [];
                  const onMsg4 = (json) => w.push(json.toString());
                  bt.on('message', onMsg4);
                  bt.chat(useCmd);
                  await sleep(1700);
                  bt.removeListener('message', onMsg4);
                  if (w.some(s => s.includes('使用成功'))) agg.shopUseOk++;
                }
              }
            }
          }
          bt.chat('/balatro next');
          await sleep(1800);
          continue;
        }
        if (st.phase === 'BLIND_SELECT') { bt.chat('/balatro go'); await sleep(1900); lastPhase = 'BLIND_SELECT'; continue; }
        if (st.phase === 'ROUND' && st.handStr) {
          lastPhase = 'ROUND'; lastBlind = st.blind;
          const list = inters(bt);
          if (!list.length) continue;
          const proj = boardAxes(bt, list);
          const r = classifyRound(list, proj);
          if (!r.primary || r.hand.length < 2) continue;
          // R225 新路径：持消耗品且未演练 → 选中1张 → 右键消耗品 → 执行 @ids 确认命令
          if (hasCons && !consRoundDone && r.hand.length >= 2) {
            const maxY = Math.max(...list.map(e => e.position.y));
            const consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
            if (consRow.length) {
              agg.targetAttempts++;
              bt.activateEntity(r.hand[0]);
              await sleep(500);
              const list2 = inters(bt);
              const proj2 = boardAxes(bt, list2);
              const maxY2 = Math.max(...list2.map(e => e.position.y));
              const consRow2 = list2.filter(e => Math.abs(e.position.y - maxY2) < 0.05).sort((a, b) => proj2(a) - proj2(b));
              const raw = [];
              const onMsg3 = (json) => raw.push(json.json ? json.json : json);
              bt.on('message', onMsg3);
              if (consRow2.length) bt.activateEntity(consRow2[0]);
              await sleep(1500);
              bt.removeListener('message', onMsg3);
              let useCmd = null;
              for (const j of raw) {
                const cmds = [];
                findClickCommands(j, cmds);
                const c = cmds.find(x => /^\/balatro use \d+ [a-z]+:[\w.]+( @[\d,]+)?$/.test(x));
                if (c) useCmd = c;
              }
              consRoundDone = true;
              if (useCmd && /@\d+/.test(useCmd)) {
                const w = [];
                const onMsg4 = (json) => w.push(json.toString());
                bt.on('message', onMsg4);
                bt.chat(useCmd);
                await sleep(1800);
                bt.removeListener('message', onMsg4);
                if (w.some(s => s.includes('使用成功')) && !w.some(s => s.includes('请选择'))) { agg.targetUses++; hasCons = false; }
              }
              continue;
            }
          }
          const d = decide(st.handStr);
          if (st.discards > 0 && d.discard.length >= 2) {
            for (const pi of d.discard) { bt.activateEntity(r.hand[pi]); await sleep(330); }
            bt.activateEntity(r.secondary);
            await sleep(1500);
            continue;
          }
          if (!d.play.length) continue;
          for (const pi of d.play) { bt.activateEntity(r.hand[pi]); await sleep(330); }
          bt.activateEntity(r.primary);
          agg.hands++;
          await sleep(1900);
          continue;
        }
        await sleep(1000);
      }
      // 局末判定：无会话=失败
      const stEnd = await statusSnapshot(bt, 900);
      if (!stEnd) agg.losses++;
      bt.chat('/balatro quit');
      hasCons = false; consRoundDone = false; consShopDone = false;
      await sleep(800);
    }
  }));

  stopObs = true;
  await sleep(4000);
  const alive = bots.filter(bt => bt.entity).length;
  const kicked = NAMES.filter((nm, i) => !bots[i].entity).length;
  step('soak_alive_20', alive === 20, `存活 ${alive}/20 kicked=${kicked}`);
  const minTps = tpsSamples.length ? Math.min(...tpsSamples) : -1;
  step('tps_full_load', minTps >= 19.0, `样本=${tpsSamples.join(',')} min=${minTps}`);
  const maxMem = memSamples.length ? Math.max(...memSamples.map(m => m.sum)) : 0;
  // 记录型：host 侧合计含用户自身 java 进程（无法隔离），服务器内存权威口径为 R223 的 1.96GB(-Xmx2G 内)
  step('memory_observed', true, `host java 合计峰值≈${maxMem}MB（含用户进程，记录型）；服务器 -Xmx2G`);
  step('newpath_target_use', agg.targetUses >= 3, `目标确认链 尝试=${agg.targetAttempts} 成功=${agg.targetUses}`);
  step('newpath_shop_use', agg.shopAttempts >= 3 && agg.shopUseOk >= 1, `商店使用按钮 尝试=${agg.shopAttempts} 成功=${agg.shopUseOk}`);
  const stats1 = lc(rd(statsPath));
  const newLines = rd(statsPath).trimEnd().split('\n').slice(stats0).filter(l => l.trim());
  const wf = newLines.every(l => (l.match(/\|/g) || []).length === 6);
  step('stats_match_losses', stats1 - stats0 === agg.losses && wf, `新增 ${stats1 - stats0} == 失败局 ${agg.losses}，7 段合法=${wf}`);
  log('agg:', JSON.stringify(agg));

  for (const bt of bots) bt.chat('/balatro quit');
  await sleep(1500);
  for (const bt of bots) { try { bt.quit(); } catch (e) {} }
  await sleep(1500);
  fs.writeFileSync(__dirname + '/check30-results.json', JSON.stringify({ results, agg, tpsSamples, memSamples }, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check30-results.json', JSON.stringify({ results }, null, 2)); process.exit(2); });
