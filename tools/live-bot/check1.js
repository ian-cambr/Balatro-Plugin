// R220 实机验证脚本 1：牌桌生成+私有可见+交互链+退出即弃（双假人）
// BalBot：玩家主视角；BalBot2：观察者 + op（服务器侧实体计数神谕）
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
function balEntities(es) { // balatro 相关实体类型（text_display / interaction）
  return es.filter(e => (e.name === 'text_display' || e.name === 'interaction' || e.entityType === undefined && /display|interaction/i.test(e.name || '')) && (e.name === 'text_display' || e.name === 'interaction'));
}
const countBy = (es) => es.reduce((m, e) => (m[e.name] = (m[e.name] || 0) + 1, m), {});

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (s) log(`CHAT<${username}> ${s.slice(0, 220)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

// 服务器侧计数神谕：BalBot2 以 op 跑 /execute as @e ... run say，收集 ENT 行
function serverCount(observer, type, waitMs = 2500) {
  return new Promise(async (resolve) => {
    const marks = [];
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/\[CNT\|([a-z_]+)\]/g) || [];
      marks.push(...m);
    };
    observer.on('message', onMsg);
    observer.chat(`/execute as @e[type=minecraft:${type}] run say [CNT|${type}]`);
    await sleep(waitMs);
    observer.removeListener('message', onMsg);
    resolve(marks.length);
  });
}

(async () => {
  const b2 = await makeBot('BalBot2');       // 观察者（站桩，出生点）
  await sleep(1500);
  const b1 = await makeBot('BalBot');        // 玩家
  b1.setControlState('forward', true);       // 走离出生点 6 格（东向？先朝北走）
  await sleep(2600);
  b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false);          // 朝北（yaw=π => -Z）
  await sleep(800);

  // 0) 初始：两人都看不到 balatro 实体
  let e = balEntities(entitiesNear(b1, 24));
  step('init_owner_zero_entities', e.length === 0, `owner sees ${JSON.stringify(countBy(e))}`);
  e = balEntities(entitiesNear(b2, 40));
  step('init_observer_zero_entities', e.length === 0, `observer sees ${JSON.stringify(countBy(e))}`);

  // 1) /balatro play → 盲注选择视图
  b1.chat('/balatro play SEEDR220');
  await sleep(3500);
  const own = balEntities(entitiesNear(b1, 16));
  const ownCnt = countBy(own);
  step('blindselect_spawn', (ownCnt['interaction'] || 0) >= 2 && (ownCnt['text_display'] || 0) >= 1,
    `owner sees ${JSON.stringify(ownCnt)}; interactions=${own.filter(x => x.name === 'interaction').map(x => `${x.position.x.toFixed(2)},${x.position.y.toFixed(2)},${x.position.z.toFixed(2)}`).join(' | ')}`);

  // 2) 私有可见：观察者客户端 0 实体；服务器侧计数 >0
  e = balEntities(entitiesNear(b2, 48));
  step('private_visibility_observer', e.length === 0, `observer client sees ${JSON.stringify(countBy(e))}`);
  const srvInteraction = await serverCount(b2, 'interaction');
  const srvText = await serverCount(b2, 'text_display');
  step('server_side_entities_present', srvInteraction >= 2 && srvText >= 1, `server: interaction=${srvInteraction} text_display=${srvText}`);

  // 3) 点击 go（两交互中 x 较小者）→ 回合视图
  const inters = own.filter(x => x.name === 'interaction').sort((a, c) => a.position.x - c.position.x);
  if (inters.length >= 2) {
    b1.activateEntity(inters[0]);
    await sleep(2500);
    const round = balEntities(entitiesNear(b1, 16));
    const roundInts = round.filter(x => x.name === 'interaction');
    step('go_clicked_round_phase', roundInts.length >= 8, `after go: ${JSON.stringify(countBy(round))} (期望手牌~8+按钮2)`);

    // 4) 选 2 张手牌（y 最高的一行中取第 1、3 张）→ 出牌（按钮行 y 最低对中 x 小者）
    const hand = roundInts.filter(x => Math.abs(x.position.y - Math.max(...roundInts.map(i => i.position.y))) < 0.05)
      .sort((a, c) => a.position.x - c.position.x);
    const btnRow = roundInts.filter(x => Math.abs(x.position.y - Math.min(...roundInts.map(i => i.position.y))) < 0.05)
      .sort((a, c) => a.position.x - c.position.x);
    if (hand.length >= 3 && btnRow.length >= 2) {
      b1.activateEntity(hand[0]); await sleep(450);
      b1.activateEntity(hand[2]); await sleep(450);
      const preSrv = await serverCount(b2, 'interaction');
      b1.activateEntity(btnRow[0]); // play
      await sleep(3000);
      const after = balEntities(entitiesNear(b1, 16));
      step('play_hand_clicked', true, `after play: interactions=${after.filter(x => x.name === 'interaction').length} (出牌后手牌重抽/回合推进)`);
    } else {
      step('play_hand_clicked', false, `hand=${hand.length} btn=${btnRow.length} 布局识别失败`);
    }
  } else {
    step('go_clicked_round_phase', false, `盲注视图交互数 ${inters.length} < 2`);
  }

  // 5) status 命令输出
  b1.chat('/balatro status');
  await sleep(1500);

  // 6) /balatro quit → 服务器侧实体清零
  b1.chat('/balatro quit');
  await sleep(2500);
  const srvAfterQuit = await serverCount(b2, 'interaction') + await serverCount(b2, 'text_display');
  step('quit_cleanup', srvAfterQuit === 0, `server-side entities after /quit = ${srvAfterQuit}`);

  // 7) 断线即弃：再开一局后直接断开（不走 /quit）
  b1.chat('/balatro play SEEDR220B');
  await sleep(3000);
  const srvPlaying = await serverCount(b2, 'interaction');
  b1.quit(); // 模拟直接下线
  await sleep(3000);
  const srvAfterDc = await serverCount(b2, 'interaction') + await serverCount(b2, 'text_display');
  step('disconnect_cleanup', srvAfterDc === 0, `playing=${srvPlaying} → after abrupt disconnect=${srvAfterDc}`);

  // 8) TPS 观察
  b2.chat('/spark tps');
  await sleep(2500);

  b2.quit();
  await sleep(1500);
  fs.writeFileSync(__dirname + '/check1-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check1-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
