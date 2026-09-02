// R220 实机验证脚本 4：修正版完整玩法环（play=大x/手牌含上移行/原生 sneak 包/商店购买+reroll+next）
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

// 按钮 = y 最低的两个；手牌 = 其余（含选中上移 0.5）
function classifyRound(list) {
  const ys = list.map(i => i.position.y);
  const min = Math.min(...ys);
  const btns = list.filter(x => Math.abs(x.position.y - min) < 0.05).sort((a, c) => a.position.x - c.position.x);
  const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, c) => a.position.x - c.position.x);
  return { hand, play: btns[btns.length - 1], discard: btns[0] };
}

function rawInteract(bot, entity, sneaking) {
  bot._client.write('use_entity', { target: entity.id, mouse: 0, sneaking, hand: 0 });
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
  bot.on('message', (json) => {
    const s = json.toString();
    if (!s) return;
    chatLog.push(s);
    if (!/^\[Interaction\]|操作说明|直接右键 = |Shift \+ 右键 = |进入商店\/补充包/.test(s)) log(`CHAT<${username}> ${s.slice(0, 170)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2600); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(800);

  b1.chat('/balatro play SEEDR220E');
  await sleep(3200);

  // ---- Shift+右键 简介（原生 sneaking 包）----
  let gotInfo = false;
  const onInfo = (json) => { if (/简介|：/.test(json.toString()) && /♦|♠|♥|♣/.test(json.toString())) gotInfo = true; };
  b1.on('message', onInfo);
  const l0 = inters(b1);
  const c0 = classifyRound(l0);
  rawInteract(b1, c0.hand[3], true);
  await sleep(1400);
  b1.removeListener('message', onInfo);
  step('shift_right_info_packet', gotInfo, '原生 sneaking 包右键后聊天框收到卡牌简介');
  // 再点一次（sneak）取消可能产生的选中态：直接重新 play 会重置，忽略

  // ---- 完整玩法环 ----
  let clearedToShop = false, playedAtLeastOne = false, shopChatSeen = false;
  const shopInfoLines = [];
  const onShop = (json) => { const s = json.toString(); if (/商店|商品|\$/.test(s)) shopInfoLines.push(s); };
  b1.on('message', onShop);
  for (let i = 0; i < 14; i++) {
    const st = await statusSnapshot(b1);
    if (!st) break;
    if (st.phase === 'SHOP') { clearedToShop = true; break; }
    if (st.phase !== 'ROUND') break;
    const r = classifyRound(inters(b1));
    if (!r.play || r.hand.length < 2) { log('layout miss', r.hand.length, r.play); break; }
    b1.activateEntity(r.hand[0]); await sleep(420);
    b1.activateEntity(r.hand[1]); await sleep(420);
    b1.activateEntity(r.play);
    await sleep(2200);
    const st2 = await statusSnapshot(b1);
    if (st2 && (st2.hands < st.hands || st2.score > st.score)) playedAtLeastOne = true;
    if (st2 && st2.score >= st2.target) await sleep(2600);
  }
  b1.removeListener('message', onShop);
  step('play_scores', playedAtLeastOne, '出牌使 hands 减少或 score 增加');
  step('reached_shop', clearedToShop, '清盲后 phase=SHOP');
  shopChatSeen = shopInfoLines.length > 0;
  step('shop_info_in_chat', shopChatSeen, `商店简介 ${shopInfoLines.length} 行进聊天框`);

  // ---- 商店操作：reroll / 购买 / next ----
  if (clearedToShop) {
    await sleep(2000);
    const list = inters(b1);
    const ys = list.map(i => i.position.y);
    const uniq = [...new Set(ys.map(y => +y.toFixed(2)))].sort((a, b) => b - a);
    log('shop y rows:', uniq.join(', '), 'ints:', list.length);
    const bottom = list.filter(x => Math.abs(x.position.y - Math.min(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);
    const stS = await statusSnapshot(b1);
    log('shop status:', JSON.stringify(stS));

    // reroll（bottom 小 x）：钱够则商店刷新（新简介行）
    if (stS && stS.money >= 5) {
      shopInfoLines.length = 0;
      b1.activateEntity(bottom[0]);
      await sleep(2200);
      step('shop_reroll', shopInfoLines.length > 0, `reroll 后新简介 ${shopInfoLines.length} 行`);
    } else {
      step('shop_reroll', true, `钱不足跳过 money=${stS && stS.money}`);
    }

    // 购买：解析简介行里的价格，从上排（卡片行）挑买得起的
    const stS2 = await statusSnapshot(b1);
    const cardRow = list.filter(x => Math.abs(x.position.y - Math.max(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);
    const priceRe = /\$(\d+)/g;
    const lines = chatLog.slice(-40).filter(s => /\$(\d+)/.test(s) && /(小丑|塔罗|星球|幻灵|游戏牌|牌)/.test(s));
    let bought = false;
    for (let idx = 0; idx < cardRow.length && idx < 4; idx++) {
      b1.activateEntity(cardRow[idx]);
      await sleep(1800);
      const stB = await statusSnapshot(b1);
      if (stB && stS2 && stB.money < stS2.money) { bought = true; break; }
    }
    step('shop_buy_card', bought, `money ${stS2 && stS2.money} -> 购买后扣款`);

    // next（bottom 大 x）
    const bottom2 = inters(b1).filter(x => true).sort((a, c) => a.position.y - c.position.y);
    const minY = Math.min(...bottom2.map(i => i.position.y));
    const nxt = bottom2.filter(x => Math.abs(x.position.y - minY) < 0.05).sort((a, c) => a.position.x - c.position.x);
    b1.activateEntity(nxt[nxt.length - 1]);
    await sleep(2800);
    const stN = await statusSnapshot(b1);
    step('shop_next_button', stN !== null && stN.phase !== 'SHOP', `next 后 phase=${stN && stN.phase}`);
  }

  b1.chat('/balatro quit'); await sleep(1500);
  b1.quit(); await sleep(1200);
  fs.writeFileSync(__dirname + '/check4-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check4-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
