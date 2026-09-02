const mineflayer = require('mineflayer');
const { rconCommand } = require('./rcon.js');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11,19)}]`, ...a);
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
(async () => {
  await new Promise(r => bot.once('spawn', r));
  let windowOpened = null;
  bot.on('windowOpen', (w) => { windowOpened = w; log('WINDOW OPENED type=', w.type); });
  // 在面前召唤村民
  const p = bot.entity.position;
  const yaw = bot.entity.yaw;
  const fx = -Math.sin(yaw), fz = -Math.cos(yaw); // 面向
  const vx = (p.x + fx * 2).toFixed(1), vz = (p.z + fz * 2).toFixed(1);
  await rconCommand('127.0.0.1', 25575, 'balatro220', `summon minecraft:villager ${vx} ${p.y} ${vz} {NoAI:1b}`);
  await sleep(1500);
  // 找村民并点击
  let villager = null;
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'villager' && e.position && e.position.distanceTo(bot.entity.position) < 8) villager = e;
  }
  log('villager found:', !!villager, villager && villager.position.toString());
  if (villager) {
    bot.activateEntity(villager);
    await sleep(2500);
    log('trade window opened:', windowOpened !== null ? 'YES ✓ (use_entity 管线通)' : 'NO ✗ (服务器拒绝该玩家全部实体交互)');
    if (windowOpened) try { bot.closeWindow(); } catch {}
  }
  await rconCommand('127.0.0.1', 25575, 'balatro220', `kill @e[type=minecraft:villager,distance=..8]`).catch(()=>{});
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
