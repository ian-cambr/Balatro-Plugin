const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11,19)}]`, ...a);
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
let st = null;
bot.on('message', (j) => { const s = j.toString(); const m = s.match(/hands=(\d+) discards=(\d+)/); if (m) st = { hands: +m[1], discards: +m[2] }; });
const inters = () => { const o = []; for (const id in bot.entities) { const e = bot.entities[id]; if (e && e.name === 'interaction' && e.position && e.position.distanceTo(bot.entity.position) < 16) o.push(e); } return o; };
(async () => {
  await new Promise(r => bot.once('spawn', r));
  await bot.look(Math.PI, 0, false);
  bot.chat('/balatro play NEAR1');
  await sleep(3000);
  // 先贴近 1 格
  bot.setControlState('forward', true); await sleep(300); bot.setControlState('forward', false);
  await sleep(600);
  const eye = bot.entity.position.offset(0, 1.62, 0);
  let list = inters().sort((a, b) => a.position.y - b.position.y);
  const btnA = list[0], btnB = list[1]; // y 最低两个
  const hand = list.filter(x => x !== btnA && x !== btnB).sort((a, b) => a.position.x - b.position.x);
  log('近距: btnA', eye.distanceTo(btnA.position).toFixed(2), 'btnB', eye.distanceTo(btnB.position).toFixed(2), 'hand0', eye.distanceTo(hand[0].position).toFixed(2));
  const pre = st && { ...st };
  // 选一张 + 点弃牌（两个按钮都各来一轮，每轮重新选牌）
  bot.activateEntity(hand[0]);
  await sleep(600);
  bot.activateEntity(btnB);
  await sleep(1500);
  log('btnB 后:', JSON.stringify(st), 'pre=', JSON.stringify(pre));
  if (st && pre && st.discards === pre.discards) {
    // 可能 btnB 是 play（无选中→无效）或点击被拒。重新选牌点 btnA
    const l2 = inters().sort((a, b) => a.position.y - b.position.y);
    const h2 = l2.filter(x => x !== l2[0] && x !== l2[1]);
    bot.activateEntity(h2[0]);
    await sleep(600);
    bot.activateEntity(btnA);
    await sleep(1500);
    log('btnA 后:', JSON.stringify(st));
  }
  bot.chat('/balatro quit'); await sleep(800);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
