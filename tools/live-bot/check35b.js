// R238 实机验证脚本 35b：xray 挑战开局回合即有面朝下牌 → 验证 0.4.62 身份保密修复的两个通道
// ① /balatro status 手牌显示「N:？」（不泄花色点数）② Shift+右键面朝下牌 → 「面朝下的牌（内容未知）」
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
function shiftInfo(bot, e) {
  return new Promise(async (resolve) => {
    let text = null;
    const onMsg = (json) => { const s = json.toString(); if (!text && /^(红桃|黑桃|梅花|方块|石头牌|面朝下)|增强|版本|蜡封/.test(s)) text = s; };
    bot.on('message', onMsg);
    bot._client.write('use_entity', { target: e.id, mouse: 0, sneaking: true, hand: 0 });
    await sleep(1200);
    bot.removeListener('message', onMsg);
    resolve(text);
  });
}
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 140)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);
  b1.chat('/balatro quit'); await sleep(1000);

  b1.chat('/balatro play xray XRAY2381');
  await sleep(3500);

  // status 手牌（xray：1/4 抽牌面朝下 → 手牌应含 ？）
  let handRaw = null;
  {
    const onMsg = (json) => { const m = json.toString().match(/^手牌: (.*)$/); if (m) handRaw = m[1]; };
    b1.on('message', onMsg);
    b1.chat('/balatro status');
    await sleep(1600);
    b1.removeListener('message', onMsg);
  }
  const fdCount = handRaw ? ((handRaw.match(/：?？/g) || handRaw.match(/:\?/g) || []).length) : 0;
  step('status_hides_facedown', handRaw !== null && fdCount >= 1,
      handRaw ? `${fdCount} 张面朝下 → ${handRaw.slice(0, 80)}` : '未取到手牌行');

  // Shift+右键全部手牌：任何一张都不泄露身份（面朝下的显示「面朝下的牌」，正常的显示花色点数）
  const list = inters(b1);
  const proj = boardAxes(b1, list);
  const ys = list.map(i => i.position.y);
  const min = Math.min(...ys);
  const hand = list.filter(x => Math.abs(x.position.y - min) >= 0.05).sort((a, b) => proj(a) - proj(b));
  let fdInfoSeen = false, leak = null;
  for (let k = 0; k < Math.min(8, hand.length); k++) {
    const info = await shiftInfo(b1, hand[k]);
    if (!info) continue;
    if (info.includes('面朝下')) { fdInfoSeen = true; }
    else if (!/^(红桃|黑桃|梅花|方块|石头牌)/.test(info)) { leak = info; }
  }
  step('shiftinfo_facedown_hidden', fdInfoSeen && !leak,
      `见到面朝下简介=${fdInfoSeen} 异常泄露=${leak ? leak.slice(0, 40) : '无'}`);

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check35b-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check35b-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
