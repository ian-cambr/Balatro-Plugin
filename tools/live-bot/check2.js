// R220 实机验证脚本 2：修复神谕（op+say 回显）/peaceful/点击距离差分实验
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
const countBy = (es) => es.reduce((m, e) => (m[e.name] = (m[e.name] || 0) + 1, m), {});

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (s) log(`CHAT<${username}> ${s.slice(0, 200)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

// 服务器侧计数：op 跑 /execute as @e ... run say，只统计 say 的回显行 <BalBot2> [CNT|type]
function serverCount(observer, type, waitMs = 2000) {
  return new Promise(async (resolve) => {
    const marks = [];
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/<BalBot2> \[CNT\|([a-z_]+)\]/);
      if (m) marks.push(m[1]);
    };
    observer.on('message', onMsg);
    observer.chat(`/execute as @e[type=minecraft:${type}] run say [CNT|${type}]`);
    await sleep(waitMs);
    observer.removeListener('message', onMsg);
    resolve(marks.length);
  });
}

// 解析 /balatro status 输出中的 hands=
function statusHands(bot) {
  return new Promise(async (resolve) => {
    let val = null;
    const onMsg = (json) => {
      const m = json.toString().match(/hands=(\d+)/);
      if (m) val = parseInt(m[1]);
    };
    bot.on('message', onMsg);
    bot.chat('/balatro status');
    await sleep(1500);
    bot.removeListener('message', onMsg);
    resolve(val);
  });
}

(async () => {
  const b2 = await makeBot('BalBot2');
  await sleep(1000);
  b2.chat('/difficulty peaceful');
  b2.chat('/gamerule doMobSpawning false');
  await sleep(1500);
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true);
  await sleep(2600);
  b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false);
  await sleep(800);

  b1.chat('/balatro play SEEDR220C');
  await sleep(3500);
  const own = balEntities(entitiesNear(b1, 16));
  const ints = own.filter(x => x.name === 'interaction');
  step('round_spawn', ints.length >= 9, `owner sees ${JSON.stringify(countBy(own))}`);

  // 神谕自检：服务器侧计数应等于客户端计数
  const srvInt = await serverCount(b2, 'interaction');
  step('oracle_server_count', srvInt === ints.length, `server=${srvInt} client=${ints.length}`);

  // 手牌行（y 最大）与按钮行（y 最小）
  const ys = ints.map(i => i.position.y);
  const hand = ints.filter(x => Math.abs(x.position.y - Math.max(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);
  const btns = ints.filter(x => Math.abs(x.position.y - Math.min(...ys)) < 0.05).sort((a, c) => a.position.x - c.position.x);
  log(`hand=${hand.length} btn=${btns.length}; bot at ${b1.entity.position}; play btn at ${btns[0] && btns[0].position} dist=${btns[0] && btns[0].position.distanceTo(b1.entity.position).toFixed(2)}`);

  // 距离差分实验 A：原距离（~2.6+）直接出牌 → 若交互距离受限则 hands 不变
  const h0 = await statusHands(b1);
  b1.activateEntity(btns[0]); // play（无选牌 → 若点击到达服务器且无选牌，行为=无操作或提示）
  b1.activateEntity(hand[0]); // 选第 1 张
  await sleep(600);
  b1.activateEntity(btns[0]); // play
  await sleep(2500);
  const h1 = await statusHands(b1);
  step('click_at_default_distance', h1 !== null && h1 < h0, `hands ${h0} -> ${h1}（减少=点击生效）`);

  // 距离差分实验 B：前移 1 格再点（若 A 失败而 B 成功 → 交互距离缺陷实锤）
  if (h1 === null || h1 >= h0) {
    b1.setControlState('forward', true);
    await sleep(350);
    b1.setControlState('forward', false);
    await sleep(800);
    b1.activateEntity(hand[1]);
    await sleep(600);
    const playBtn2 = balEntities(entitiesNear(b1, 16)).filter(x => x.name === 'interaction')
      .filter(x => Math.abs(x.position.y - Math.min(...ints.map(i => i.position.y))) < 0.05)
      .sort((a, c) => a.position.x - c.position.x);
    b1.activateEntity(playBtn2[0]);
    await sleep(2500);
    const h2 = await statusHands(b1);
    step('click_after_closer', h2 !== null && h2 < h1, `hands ${h1} -> ${h2}（前移后生效=距离缺陷实锤）`);
  }

  // 退出清理（神谕已修）
  b1.chat('/balatro quit');
  await sleep(2500);
  const q1 = await serverCount(b2, 'interaction'), q2 = await serverCount(b2, 'text_display');
  step('quit_cleanup', q1 + q2 === 0, `server after /quit: interaction=${q1} text_display=${q2}`);

  // 断线即弃
  b1.chat('/balatro play SEEDR220D');
  await sleep(3000);
  const p1 = await serverCount(b2, 'interaction');
  b1.quit();
  await sleep(3000);
  const d1 = await serverCount(b2, 'interaction'), d2 = await serverCount(b2, 'text_display');
  step('disconnect_cleanup', d1 + d2 === 0, `playing=${p1} -> after disconnect: interaction=${d1} text_display=${d2}`);

  b2.chat('/spark tps');
  await sleep(2500);
  b2.quit();
  await sleep(1200);
  fs.writeFileSync(__dirname + '/check2-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check2-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
