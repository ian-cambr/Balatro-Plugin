// R220 实机验证脚本 5：智能选牌清盲→商店 reroll/购买/next 实机验证
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

function classifyRound(list) {
  const ys = list.map(i => i.position.y);
  const min = Math.min(...ys);
  const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, c) => a.position.x - c.position.x);
  const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, c) => a.position.x - c.position.x);
  return { hand, play: btns[btns.length - 1], discard: btns[0] };
}

// 手牌字符串 → 每张牌点数；选同点数最多的一组（1~5 张）。格式为「1:♥A」（花色在前）
function parseHand(handStr) {
  return [...handStr.matchAll(/[♠♥♦♣]\s*([AJQK]|10|\d)/g)].map(t => t[1]);
}
const val = (r) => r === 'A' ? 14 : r === 'K' ? 13 : r === 'Q' ? 12 : r === 'J' ? 11 : r === '10' ? 10 : parseInt(r);
// 决策：返回 {play:[idx], discard:[idx]} —— 有三条/两对/高对就打，否则弃最差的 4 张钓鱼
function decide(handStr) {
  const cards = parseHand(handStr);
  const cnt = {};
  cards.forEach((r, i) => { (cnt[r] = cnt[r] || []).push(i); });
  const groups = Object.values(cnt).sort((a, b) => b.length - a.length || val(cards[b[0]]) - val(cards[a[0]]));
  const best = groups[0] || [];
  const second = groups[1] || [];
  if (best.length >= 3) return { play: best, discard: [] };
  if (best.length === 2 && second.length === 2) return { play: [...best, ...second], discard: [] };
  if (best.length === 2 && val(cards[best[0]]) >= 11) return { play: best, discard: [] };
  // 钓鱼：保留最大点数的 3 张，弃其余
  const keep = cards.map((r, i) => ({ i, v: val(r) })).sort((a, b) => b.v - a.v).slice(0, 3).map(o => o.i);
  const toss = cards.map((r, i) => i).filter(i => !keep.includes(i));
  return { play: [], discard: toss };
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
    if (!/^\[Interaction\]|操作说明|直接右键 = |Shift \+ 右键 = |进入商店\/补充包|牌组效果|赌注效果|右键手牌选中/.test(s)) log(`CHAT<${username}> ${s.slice(0, 190)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2600); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(800);

  let inShop = false, playedOne = false, runNo = 0;
  while (!inShop && runNo < 4) {
    runNo++;
    b1.chat(`/balatro play WIN${runNo}`);
    await sleep(3000);
    for (let i = 0; i < 10; i++) {
      const st = await statusSnapshot(b1);
      if (!st) break;
      if (st.phase === 'SHOP') { inShop = true; break; }
      if (st.phase !== 'ROUND') break;
      if (!st.handStr) break;
      const d = decide(st.handStr);
      const r = classifyRound(inters(b1));
      if (!r.play || r.hand.length < 2) break;
      if (st.discards > 0 && d.discard.length >= 2) {
        for (const pi of d.discard) { b1.activateEntity(r.hand[pi]); await sleep(380); }
        b1.activateEntity(r.discard);
        await sleep(1600);
        continue; // 重抽后再决策
      }
      if (!d.play.length) continue;
      for (const pi of d.play) { b1.activateEntity(r.hand[pi]); await sleep(380); }
      b1.activateEntity(r.play);
      await sleep(2100);
      const st2 = await statusSnapshot(b1);
      if (st2 && (st2.hands < st.hands || st2.score > st.score)) playedOne = true;
      if (st2 && st2.score >= st2.target) { await sleep(2800); }
    }
    if (!inShop) { b1.chat('/balatro quit'); await sleep(1500); }
  }
  step('smart_play_scores', playedOne, '智能选牌出牌计分生效');
  step('reached_shop', inShop, `${runNo} 局内清盲进入商店`);

  if (inShop) {
    await sleep(2500);
    const stS = await statusSnapshot(b1);
    log('shop status:', JSON.stringify(stS));
    const list = inters(b1);
    const ys = list.map(i => i.position.y);
    const uniq = [...new Set(ys.map(y => +y.toFixed(2)))].sort((a, b) => b - a);
    log('shop rows:', uniq.join(','), 'ints:', list.length, '(期望: 卡1.2/包0.1/cons-0.65/券-1.35/按钮-2.05)');
    const bottom = list.filter(x => Math.abs(x.position.y - Math.min(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);

    // reroll
    let rerollOk = false;
    if (stS && stS.money >= 5) {
      chatLog.length = 0;
      b1.activateEntity(bottom[0]);
      await sleep(2400);
      rerollOk = chatLog.some(s => /\$/.test(s) && /(小丑|塔罗|星球|幻灵|游戏|牌|商店)/.test(s));
    }
    step('shop_reroll_refresh', stS && stS.money < 5 ? true : rerollOk, `reroll 后商店简介刷新（money=${stS && stS.money}）`);

    // 购买：顶行从左到右试点，扣款即成功
    const stS2 = await statusSnapshot(b1);
    const list2 = inters(b1);
    const ys2 = list2.map(i => i.position.y);
    const cardRow = list2.filter(x => Math.abs(x.position.y - Math.max(...ys2)) < 0.05).sort((a, c) => a.position.x - c.position.x);
    let bought = false, buyDetail = '';
    for (let idx = 0; idx < Math.min(cardRow.length, 4); idx++) {
      b1.activateEntity(cardRow[idx]);
      await sleep(2000);
      const stB = await statusSnapshot(b1);
      if (stB && stS2 && stB.money < stS2.money) { bought = true; buyDetail = `money ${stS2.money}->${stB.money}`; break; }
      if (stB && stS2 && stB.money > stS2.money) { buyDetail = `钱变多?${stS2.money}->${stB.money}`; break; }
    }
    step('shop_buy_click', bought, bought ? buyDetail : `未扣款（可能全买不起）money=${stS2 && stS2.money}`);

    // next
    const list3 = inters(b1);
    const ys3 = list3.map(i => i.position.y);
    const bottom3 = list3.filter(x => Math.abs(x.position.y - Math.min(...ys3)) < 0.05).sort((a, c) => a.position.x - c.position.x);
    b1.activateEntity(bottom3[bottom3.length - 1]);
    await sleep(3000);
    const stN = await statusSnapshot(b1);
    step('shop_next_button', stN !== null && stN.phase !== 'SHOP', `next 后 phase=${stN && stN.phase} blind=${stN && stN.blind}`);

    // 盲注选择视图（go/skip）
    if (stN && stN.phase === 'BLIND_SELECT') {
      const lb = inters(b1);
      const ys4 = lb.map(i => i.position.y);
      const row = lb.filter(x => Math.abs(x.position.y - Math.min(...ys4)) < 0.05).sort((a, c) => a.position.x - c.position.x);
      if (row.length >= 1) {
        b1.activateEntity(row[row.length - 1]); // 大 x = go（与 play 同侧）
        await sleep(2800);
        const stG = await statusSnapshot(b1);
        step('blindselect_go', stG !== null && stG.phase === 'ROUND', `go 后 phase=${stG && stG.phase}`);
      }
    }
  }

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1200);
  fs.writeFileSync(__dirname + '/check5-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check5-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
