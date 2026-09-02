// R220 实机验证脚本 6：旋转无关投影分类（right=(−fz,0,fx)），完整玩法环到商店
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

function entitiesNear(bot, radius) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (e.position.distanceTo(bot.entity.position) <= radius) out.push(e);
  }
  return out;
}
const inters = (bot, r = 16) => entitiesNear(bot, r).filter(e => e.name === 'interaction');

// 旋转无关：right=(−fz,0,fx)，f=bot→板中心水平向量；proj 升序 = lx 升序 = 列表顺序
function boardAxes(bot, list) {
  const p = bot.entity.position;
  const cx = list.reduce((s, e) => s + e.position.x, 0) / list.length;
  const cz = list.reduce((s, e) => s + e.position.z, 0) / list.length;
  let fx = cx - p.x, fz = cz - p.z;
  const fl = Math.hypot(fx, fz) || 1; fx /= fl; fz /= fl;
  const rx = -fz, rz = fx;
  return (e) => (e.position.x - cx) * rx + (e.position.z - cz) * rz;
}
// 按钮行 = y 最低行；primary=proj 最小（play/go/reroll），secondary=proj 最大（discard/skip/next）
function classify(list, proj) {
  const ys = list.map(i => i.position.y);
  const min = Math.min(...ys);
  const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, b) => proj(a) - proj(b));
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
  if (best.length === 2 && val(cards[best[0]]) >= 11) return { play: best, discard: [] };
  const keep = cards.map((r, i) => ({ i, v: val(r) })).sort((a, b) => b.v - a.v).slice(0, 3).map(o => o.i);
  return { play: [], discard: cards.map((r, i) => i).filter(i => !keep.includes(i)) };
}

function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let val = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) val = { ante: +m[1], blind: m[2], phase: m[3], score: +m[4], target: +m[5], hands: +m[6], discards: +m[7], money: +m[8] };
      const h = s.match(/^手牌: (.*)$/);
      if (h && val) val.handStr = h[1];
    };
    bot.on('message', onMsg);
    bot.chat('/balatro status');
    await sleep(waitMs);
    bot.removeListener('message', onMsg);
    resolve(val);
  });
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (!s) return;
    chatLog.push(s);
    if (!/^\[Interaction\]|操作说明|直接右键 = |Shift \+ 右键 = |进入商店\/补充包|牌组效果|赌注效果|右键手牌选中|本局信息/.test(s)) log(`CHAT<${username}> ${s.slice(0, 190)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2600); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(800);

  let inShop = false, playedOne = false, runNo = 0;
  while (!inShop && runNo < 5) {
    runNo++;
    await b1.look(Math.PI, 0, false); await sleep(400);
    b1.chat(`/balatro play WIN${runNo}`);
    await sleep(3000);
    let stuck = 0;
    for (let i = 0; i < 16; i++) {
      const st = await statusSnapshot(b1);
      if (!st) break;
      if (st.phase === 'SHOP') { inShop = true; break; }
      if (st.phase !== 'ROUND') break;
      if (!st.handStr) break;
      const d = decide(st.handStr);
      const list = inters(b1);
      const proj = boardAxes(b1, list);
      const r = classify(list, proj);
      if (!r.primary || r.hand.length < 2) { log('layout miss', r.hand.length); break; }
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { b1.activateEntity(r.hand[pi]); await sleep(380); }
        b1.activateEntity(r.secondary); // 弃牌按钮（proj 最大）
        await sleep(1700);
        continue;
      }
      if (!d.play.length) { if (++stuck > 3) break; continue; }
      for (const pi of d.play) { b1.activateEntity(r.hand[pi]); await sleep(380); }
      b1.activateEntity(r.primary); // 出牌按钮（proj 最小）
      await sleep(2100);
      const st2 = await statusSnapshot(b1);
      if (st2 && (st2.hands < st.hands || st2.score > st.score)) playedOne = true;
      if (st2 && st2.score >= st2.target) await sleep(2800);
    }
    if (!inShop) { b1.chat('/balatro quit'); await sleep(1500); }
  }
  step('smart_play_scores', playedOne, '智能选牌出牌计分生效');
  step('reached_shop', inShop, `${runNo} 局内清盲进入商店`);


  // ---- 商店 next 先行 ----
  {
    const list0 = inters(b1);
    const proj0 = boardAxes(b1, list0);
    const min0 = Math.min(...list0.map(i => i.position.y));
    const bottom0 = list0.filter(x => Math.abs(x.position.y - min0) < 0.05).sort((a,b)=>proj0(a)-proj0(b));
    log('CLICK next, bottom row size=', bottom0.length);
    b1.activateEntity(bottom0[bottom0.length-1]);
    await sleep(3500);
  }
  // ---- 聚焦探针：盲注选择 go/skip 点击映射 ----
  if (true) {
    const stP = await statusSnapshot(b1);
    log('PROBE status:', JSON.stringify(stP));
    if (stP && stP.phase === 'BLIND_SELECT') {
      const list = inters(b1);
      const proj = boardAxes(b1, list);
      list.forEach(e => log('ENT', e.id, e.name, e.position.x.toFixed(2), e.position.y.toFixed(2), e.position.z.toFixed(2), 'proj=', proj(e).toFixed(2)));
      const sorted = list.slice().sort((a,b)=>proj(a)-proj(b));
      for (const cand of sorted) {
        log('CLICK cand id=', cand.id);
        b1.activateEntity(cand);
        await sleep(3500);
        const stx = await statusSnapshot(b1);
        log('AFTER cand id=', cand.id, '->', JSON.stringify(stx && {phase: stx.phase, blind: stx.blind}));
        if (stx && stx.phase !== 'BLIND_SELECT') break;
      }
      const stz = await statusSnapshot(b1);
      if (stz && stz.phase === 'BLIND_SELECT') {
        log('CMD /balatro go 兜底');
        b1.chat('/balatro go');
        await sleep(3000);
        const sty = await statusSnapshot(b1);
        log('AFTER cmd go ->', JSON.stringify(sty && {phase: sty.phase, blind: sty.blind}));
      }
    } else { log('PROBE: not in BLIND_SELECT, skip'); }
  }

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1200);
  fs.writeFileSync(__dirname + '/probe-results.json', JSON.stringify(results, null, 2));
  log('PROBE DONE');
  process.exit(0);
})().catch(ex => { log('FATAL', ex); process.exit(2); });
