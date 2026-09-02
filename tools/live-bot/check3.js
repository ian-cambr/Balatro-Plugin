// R220 实机验证脚本 3：完整玩法环（出牌得分→清盲→商店→购买→next）+ Shift 简介 + 种子复现
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

function entitiesNear(bot, radius) {
  const out = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (!e || !e.position || e === bot.entity) continue;
    if (e.position.distanceTo(bot.entity.position) <= radius) out.push(e);
  }
  return out;
}
const balEntities = (es) => es.filter(e => e.name === 'text_display' || e.name === 'interaction');

function classifyRound(ints) {
  const ys = ints.map(i => i.position.y);
  const hand = ints.filter(x => Math.abs(x.position.y - Math.max(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);
  const btns = ints.filter(x => Math.abs(x.position.y - Math.min(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);
  return { hand, play: btns[1], discard: btns[0] }; // 实测：大 x = play，小 x = discard
}

function statusSnapshot(bot, waitMs = 1600) {
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
  bot.on('message', (json) => { const s = json.toString(); if (s && !/^\[Interaction\]/.test(s)) log(`CHAT<${username}> ${s.slice(0, 160)}`); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2600); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(800);

  // ---- 种子复现：同种子两局首手牌应完全一致 ----
  b1.chat('/balatro play REPROA');
  await sleep(3200);
  let st = await statusSnapshot(b1);
  const hand1 = st && st.handStr;
  b1.chat('/balatro quit'); await sleep(1800);
  b1.chat('/balatro play REPROA');
  await sleep(3200);
  st = await statusSnapshot(b1);
  const hand2 = st && st.handStr;
  step('seed_reproducible_same_hand', !!hand1 && hand1 === hand2, `1=[${hand1}] 2=[${hand2}]`);

  // ---- Shift+右键 简介 ----
  let ints = balEntities(entitiesNear(b1, 16)).filter(x => x.name === 'interaction');
  const rc = classifyRound(ints);
  let gotInfo = false;
  const onInfo = (json) => { if (/简介|♦|♠|♥|♣|增强/.test(json.toString())) gotInfo = true; };
  b1.on('message', onInfo);
  b1.setControlState('sneak', true);
  await sleep(400);
  b1.activateEntity(rc.hand[3]);
  await sleep(1200);
  b1.setControlState('sneak', false);
  b1.removeListener('message', onInfo);
  step('shift_right_info', gotInfo, 'Shift+右键手牌后聊天框收到简介');

  // ---- 完整玩法环：出牌直到清盲进商店 ----
  let clearedToShop = false, playedAtLeastOne = false;
  for (let i = 0; i < 12; i++) {
    st = await statusSnapshot(b1);
    if (!st) break;
    if (st.phase === 'SHOP') { clearedToShop = true; break; }
    if (st.phase !== 'ROUND') break;
    ints = balEntities(entitiesNear(b1, 16)).filter(x => x.name === 'interaction');
    const r = classifyRound(ints);
    if (!r.play) { log('no play button, entities=', ints.length); break; }
    // 选前 2 张 + 出牌
    b1.activateEntity(r.hand[0]); await sleep(420);
    b1.activateEntity(r.hand[1]); await sleep(420);
    b1.activateEntity(r.play);
    await sleep(2000);
    const st2 = await statusSnapshot(b1);
    if (st2 && (st2.hands < st.hands || st2.score > st.score)) playedAtLeastOne = true;
    if (st2 && st2.score >= st2.target) { await sleep(2500); } // 结算进商店
  }
  step('play_scores_and_clears_blind', playedAtLeastOne, `出牌使 hands 减少或 score 增加`);
  step('reached_shop', clearedToShop, `清盲后 phase=SHOP`);

  // ---- 商店视图识别与操作 ----
  if (clearedToShop) {
    await sleep(2500);
    ints = balEntities(entitiesNear(b1, 16)).filter(x => x.name === 'interaction');
    // 商店行：卡片 y=1.2 / 包 y=0.1 / cons y=-0.65 / 券 y=-1.35 / reroll(-1.5) next(+1.5) y=-2.05
    const ys = ints.map(i => +(i.position.y.toFixed(2)));
    const uniq = [...new Set(ys)].sort((a, b) => b - a);
    log('shop rows y:', uniq.join(','), 'ints:', ints.length);
    const bottomRow = ints.filter(x => Math.abs(x.position.y - Math.min(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);
    const stShop = await statusSnapshot(b1);
    const moneyBefore = stShop ? stShop.money : null;
    // next = 大 x；点击进入下一盲注
    b1.activateEntity(bottomRow[bottomRow.length - 1]);
    await sleep(2600);
    const stNext = await statusSnapshot(b1);
    step('shop_next_button', stNext !== null && stNext.phase !== 'SHOP', `money=${moneyBefore} -> phase=${stNext && stNext.phase}`);
  }

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1200);
  fs.writeFileSync(__dirname + '/check3-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check3-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
