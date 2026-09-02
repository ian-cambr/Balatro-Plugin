// R240 实机验证脚本 36：命令驱动推进（分叉结构性消除）+ 逐动作对账日志（规约 #13 基建）
// A) WATER W238w183：双盲命令推进（镜像 ACTION 序列已在模拟侧产出）→ Boss 回合：
//    /balatro disc 1 被拒（水文案）→ 选中 1 张右键消耗品 → @id 快照 → 使用成功
// B) MARK W238m2：双盲命令推进 → Boss 回合：status 含「N:？」→ 选中 → @id 使用成功
// 全程聊天快照写入 check36-actions.log（实机侧对账基准）。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
const actionLog = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}
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
// live-exact decide（与 R240Scan 逐字一致）
function decide(handStr) {
  const cards = [...handStr.matchAll(/[♠♥♦♣]\s*([AJQK]|10|\d)/g)].map(t => t[1]);
  const cnt = {};
  cards.forEach((r, i) => { (cnt[r] = cnt[r] || []).push(i); });
  const gs = Object.values(cnt).map((g, i) => ({ g, i }));
  gs.sort((a, b) => (b.g.length !== a.g.length) ? (b.g.length - a.g.length) : (val(cards[b.g[0]]) - val(cards[a.g[0]])));
  const best = gs[0].g, sec = gs[1] ? gs[1].g : null;
  if (best.length >= 3) return { play: best, disc: [] };
  if (best.length === 2 && sec && sec.length === 2) return { play: [...best, ...sec], disc: [] };
  if (best.length === 2) return { play: best, disc: [] };
  const all = cards.map((r, i) => ({ i, v: val(r) }));
  all.sort((a, b) => b.v - a.v);
  const keep = new Set(all.slice(0, Math.min(3, all.length)).map(o => o.i));
  return { play: [], disc: cards.map((r, i) => i).filter(i => !keep.has(i)) };
}
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { blind: m[2], phase: m[3], discards: +m[7], money: +m[8], score: +m[4], target: +m[5], hands: +m[6] };
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
  if (obj.extra) obj.extra.forEach(x => jsonText(x, out));
  return out;
}
let rawSink = null;
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 140)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { if (rawSink) rawSink.push(json.json ? json.json : json); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}
/** 命令驱动盲注推进（镜像 1:1）：逐动作写对账日志。 */
async function commandClear(bot, tag) {
  for (let g = 0; g < 36; g++) {
    const st = await statusSnapshot(bot, 1200);
    if (!st) { bot.chat('/balatro go'); await sleep(2000); continue; }
    if (st.phase === 'BLIND_SELECT') { actionLog.push(`${tag} GO`); bot.chat('/balatro go'); await sleep(2100); continue; }
    if (st.phase === 'SHOP') return st;
    if (st.phase !== 'ROUND' || !st.handStr) continue;
    const d = decide(st.handStr);
    if (d.play.length) {
      const pos = d.play.map(i => i + 1);
      actionLog.push(`${tag} PLAY [${st.handStr.trim()}] pos=[${pos}] pre=${st.score}/${st.target} h=${st.hands}`);
      bot.chat('/balatro playcard ' + pos.join(' '));
      await sleep(2100);
    } else if (st.discards > 0 && d.disc.length) {
      const pos = d.disc.map(i => i + 1);
      actionLog.push(`${tag} DISC [${st.handStr.trim()}] pos=[${pos}]`);
      bot.chat('/balatro disc ' + pos.join(' '));
      await sleep(1900);
    } else {
      actionLog.push(`${tag} PLAY top pre=${st.score}/${st.target}`);
      bot.chat('/balatro playcard 1');
      await sleep(2100);
    }
  }
  return await statusSnapshot(bot);
}
/** 商店买目标类（两轮重掷预算内）。 */
async function buyTarget(bot) {
  for (let rr = 0; rr < 3; rr++) {
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
      if (bought.some(x => x.includes('购买成功'))) return true;
    }
    const stm = await statusSnapshot(bot, 1000);
    if (stm && stm.money >= 5 && rr < 2) { actionLog.push(`SHOP reroll (money=${stm.money})`); bot.chat('/balatro reroll'); await sleep(1900); }
    else break;
  }
  return false;
}
/** Boss 回合 @ids 采样：选中 1 张 → 右键消耗品行 → 捕获并执行。 */
async function bossAtIds(bot) {
  let list = inters(bot);
  let proj = boardAxes(bot, list);
  let r = classifyRound(list, proj);
  bot.activateEntity(r.hand[0]);
  await sleep(500);
  list = inters(bot); proj = boardAxes(bot, list);
  const maxY = Math.max(...list.map(e => e.position.y));
  let consRow = list.filter(e => Math.abs(e.position.y - (maxY - 0.62)) < 0.06).sort((a, b) => proj(a) - proj(b));
  if (!consRow.length) consRow = list.filter(e => Math.abs(e.position.y - maxY) < 0.05).sort((a, b) => proj(a) - proj(b));
  if (!consRow.length) return { ok: false, why: 'no cons row' };
  rawSink = [];
  bot.activateEntity(consRow[0]);
  await sleep(1600);
  let useCmd = null, reqLine = null, allText = [];
  for (const j of rawSink) {
    const parts = [];
    jsonText(j, parts);
    allText.push(parts.join(''));
    if (parts.join('').includes('需选中')) reqLine = parts.join('');
    const cmds = [];
    findClickCommands(j, cmds);
    const c = cmds.find(x => /^\/balatro use \d+ [a-z]+:[\w.]+( @[\d,]+)?$/.test(x));
    if (c) useCmd = c;
  }
  rawSink = null;
  return { ok: !!(useCmd && /@\d+/.test(useCmd)), useCmd, reqLine, allText: allText.join(' | ').slice(0, 120) };
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);

  for (const [tag, seed] of [['A_water', 'W238w369'], ['B_mark', 'W238m603']]) {
    b1.chat('/balatro quit'); await sleep(1000);
    b1.chat(`/balatro play ${seed}`);
    await sleep(3200);
    let held = false;
    for (let bl = 0; bl < 2; bl++) {
      const st = await commandClear(b1, bl === 0 ? 'S' : 'B');
      if (!st || st.phase !== 'SHOP') break;
      if (!held) held = await buyTarget(b1);
      actionLog.push(`SHOP next`);
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
    const st = await statusSnapshot(b1);
    const inBoss = st && st.phase === 'ROUND' && st.blind === 'boss';
    step(`${tag}_boss_round`, inBoss, `blind=${st && st.blind} held=${held}`);

    if (inBoss) {
      if (tag === 'A_water') {
        const w = [];
        {
          const onMsg = (json) => w.push(json.toString());
          b1.on('message', onMsg);
          b1.chat('/balatro disc 1');
          await sleep(1700);
          b1.removeListener('message', onMsg);
        }
        step('A_water_disc_rejected', w.some(s => /弃牌/.test(s)), w.join('|').slice(0, 90));
      }
      if (tag === 'B_mark') {
        const handRaw = st.handStr || '';
        const fd = (handRaw.match(/？|\?/g) || []).length;
        step('B_mark_status_facedown', fd >= 1, handRaw.slice(0, 80));
      }
      if (held) {
        const cap = await bossAtIds(b1);
        step(`${tag}_confirm_at_ids`, cap.ok, cap.ok ? cap.useCmd : (cap.allText || cap.why || '未捕获'));
        if (cap.ok) {
          const w = [];
          const onMsg = (json) => w.push(json.toString());
          b1.on('message', onMsg);
          b1.chat(cap.useCmd);
          await sleep(2100);
          b1.removeListener('message', onMsg);
          step(`${tag}_use_succeeded`, w.some(s => s.includes('使用成功')) && !w.some(s => s.includes('请选择')), w.join('|').slice(-90));
        }
      } else {
        step(`${tag}_confirm_at_ids`, false, '商店无目标类（记录）');
        step(`${tag}_use_succeeded`, false, '前置未达');
      }
    }
  }

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check36-results.json', JSON.stringify(results, null, 2));
  fs.writeFileSync(__dirname + '/check36-actions.log', actionLog.join('\n'));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check36-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
