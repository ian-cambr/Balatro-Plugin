const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11,19)}]`, ...a);
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
let st = null;
bot.on('message', (j) => { const s = j.toString(); const m = s.match(/hands=(\d+) discards=(\d+)/); if (m) { st = { hands: +m[1], discards: +m[2] }; log('STATUS hands=', m[1], 'disc=', m[2]); } });
(async () => {
  await new Promise(r => bot.once('spawn', r));
  log('pos', bot.entity.position.toString());
  await bot.look(Math.PI, 0, false);
  bot.chat('/balatro play DIST1');
  await sleep(3000);
  const eye = bot.entity.position.offset(0, 1.62, 0);
  const ints = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'interaction' && e.position && e.position.distanceTo(bot.entity.position) < 16) ints.push(e);
  }
  const sorted = ints.slice().sort((a, b) => a.position.y - b.position.y);
  const [bA, bB] = sorted;
  log('btnA dist=', eye.distanceTo(bA.position).toFixed(3), 'btnB dist=', eye.distanceTo(bB.position).toFixed(3));
  log('bot yaw/pitch', bot.entity.yaw, bot.entity.pitch);
  // 原地点击
  bot.activateEntity(bB);
  await sleep(1500);
  const st1 = st && { ...st };
  // 贴近 1 格再点
  bot.setControlState('forward', true); await sleep(280); bot.setControlState('forward', false);
  await sleep(500);
  const ints2 = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'interaction' && e.position && e.position.distanceTo(bot.entity.position) < 16) ints2.push(e);
  }
  const s2 = ints2.slice().sort((a, b) => a.position.y - b.position.y);
  const eye2 = bot.entity.position.offset(0, 1.62, 0);
  log('after step: btnB dist=', eye2.distanceTo(s2[1].position).toFixed(3));
  bot.activateEntity(s2[1]);
  await sleep(1500);
  log('st after near click=', JSON.stringify(st), 'vs', JSON.stringify(st1));
  bot.chat('/balatro quit'); await sleep(1000);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
