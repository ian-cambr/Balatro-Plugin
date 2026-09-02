// R221 实机验证：GUI 向导全流程(#13) + /reload 残留(#9) + 传送迁移(#7) + 死亡重生(#8)
const mineflayer = require('mineflayer');
const fs = require('fs');
const { rconCommand } = require('./rcon.js');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const RCON = (cmd) => rconCommand('127.0.0.1', 25575, 'balatro220', cmd, 15000);
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
const balCount = (bot, r = 16) => entitiesNear(bot, r).filter(e => e.name === 'interaction' || e.name === 'text_display').length;

function statusSnapshot(bot, waitMs = 1500) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { ante: +m[1], phase: m[3], score: +m[4], target: +m[5] };
      if (/没有进行中/.test(s)) v = v || { phase: 'NOSESSION' };
    };
    bot.on('message', onMsg);
    bot.chat('/balatro status');
    await sleep(waitMs);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
}

// 在当前窗口里按显示名找槽位
function findSlotByLabel(window, label) {
  for (let i = 0; i < window.slots.length; i++) {
    const it = window.slots[i];
    if (!it) continue;
    const n = (it.customName ? JSON.stringify(it.customName) : '') + (it.name || '');
    const plain = it.customName ? JSON.stringify(it.customName).replace(/\\u00a7./g, '') : '';
    if (n.includes(label) || plain.includes(label)) return i;
  }
  return -1;
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 200)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => { const s = json.toString(); if (s && !/^\[Interaction\]/.test(s)) log(`CHAT<${username}> ${s.slice(0, 130)}`); });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const b1 = await makeBot('BalBot');

  // ================= #13 GUI 向导全流程 =================
  let winType = null;
  b1.on('windowOpen', (w) => { winType = w; });
  b1.chat('/balatro gui');
  await sleep(2000);
  let guiStarted = false;
  const clickLabeled = async (labels, tries = 6) => {
    for (const label of labels) {
      const w = winType;
      if (!w) return false;
      const slot = findSlotByLabel(w, label);
      log(`GUI click "${label}" -> slot ${slot} (type=${w.type || '?'})`);
      if (slot < 0) return false;
      await b1.clickWindow(slot, 0, 0);
      await sleep(1200);
    }
    return true;
  };
  if (winType) {
    // 主菜单：标准局 →（牌组列表：红色牌组 + 下一步）→（赌注：0 白注 + 下一步）→（确认：开始游戏）
    const ok1 = await clickLabeled(['标准局']);
    await sleep(1000);
    const ok2 = await clickLabeled(['红色牌组', '下一步']);
    await sleep(1000);
    const ok3 = await clickLabeled(['0 白注', '下一步']);
    await sleep(1000);
    const ok4 = await clickLabeled(['开始游戏']);
    await sleep(3500);
    const st = await statusSnapshot(b1);
    const ents = balCount(b1);
    guiStarted = ok1 && ok2 && ok3 && ok4 && st !== null && st.phase !== 'NOSESSION' && ents > 5;
    step('gui_wizard_full_flow', guiStarted, `向导点击链完成，开局生效 status=${JSON.stringify(st)} entities=${ents}`);
  } else {
    step('gui_wizard_full_flow', false, 'windowOpen 未触发');
  }

  // 玩一手，制造非零分数用于迁移状态保持断言
  const stR = await statusSnapshot(b1);
  log('pre-migration status:', JSON.stringify(stR));

  // ================= #7 远距传送迁移 =================
  const posBefore = b1.entity.position.clone();
  const entsBefore = balCount(b1);
  b1.chat('/tp BalBot 200 -60 200');
  await sleep(3000);
  const moved = b1.entity.position.distanceTo(posBefore) > 100;
  const entsAfter = balCount(b1);
  const stM = await statusSnapshot(b1);
  step('teleport_relocate', moved && entsAfter >= 5 && stM && stM.phase === (stR && stR.phase),
    `传送 ${moved ? '成功' : '失败'}，实体 ${entsBefore}->${entsAfter}（新位置 16 格内），状态保持 phase=${stM && stM.phase} score=${stM && stM.score}`);

  // ================= #8 死亡重生迁移 =================
  await RCON('kill BalBot').catch(e => log('rcon kill err', e.message));
  await sleep(3500); // 死亡+重生
  let respawned = false;
  try { await b1.waitForTicks(5); } catch {}
  const entsD = balCount(b1);
  const stD = await statusSnapshot(b1);
  respawned = b1.entity !== null;
  step('death_respawn_relocate', respawned && entsD >= 5 && stD && stD.phase === (stR && stR.phase),
    `重生后实体 ${entsD}，状态 phase=${stD && stD.phase} score=${stD && stD.score}（应与迁移前一致）`);

  // ================= #9 /reload 残留 =================
  const entsPre = balCount(b1);
  const rl = await RCON('reload confirm').catch(e => 'ERR ' + e.message);
  log('RCON reload:', String(rl).slice(0, 100));
  await sleep(12000); // reload 需要时间
  const entsPost = balCount(b1);
  const stP = await statusSnapshot(b1);
  // reload 后：会话应被清理（onDisable shutdownAll），实体消失，status 无局
  step('reload_cleanup', entsPost === 0 && stP && stP.phase === 'NOSESSION',
    `reload 前实体 ${entsPre} -> 后 ${entsPost}，status=${stP && stP.phase}`);
  // reload 后插件仍可用：再开一局
  b1.chat('/balatro play AFTERRELOAD');
  await sleep(3500);
  const stA = await statusSnapshot(b1);
  const entsA = balCount(b1);
  step('reload_reusable', stA !== null && stA.phase !== 'NOSESSION' && entsA > 5, `reload 后再开局 entities=${entsA}`);

  b1.chat('/balatro quit'); await sleep(1200);
  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check11-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check11-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
