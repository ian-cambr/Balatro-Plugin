const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11,19)}]`, ...a);
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
let handStr = null, st = null;
bot.on('message', (j) => {
  const s = j.toString();
  const m = s.match(/phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+)/);
  if (m) { st = { phase: m[1], hands: +m[4], discards: +m[5] }; log('STATUS', s.slice(0, 80)); }
  const h = s.match(/^手牌: (.*)$/);
  if (h) { handStr = h[1]; log('HAND', h[1]); }
});
(async () => {
  await new Promise(r => bot.once('spawn', r));
  await bot.look(Math.PI, 0, false);
  bot.chat('/balatro play MINI1');
  await sleep(3000);
  const inters = [];
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'interaction' && e.position && e.position.distanceTo(bot.entity.position) < 16) {
      inters.push(e);
      log('ENT', e.id, 'y=', e.position.y.toFixed(2), 'w=', e.metadata && e.metadata[8]);
    }
  }
  const ys = inters.map(i => i.position.y);
  const min = Math.min(...ys);
  const sorted = inters.slice().sort((a, b) => a.position.y - b.position.y);
  log('min-y=', min.toFixed(2), 'count=', inters.length);
  // 按 y 排序后：最低 2 个 = 按钮。点第 2 个（play 侧——不确定左右就都试）
  const btnA = sorted[0], btnB = sorted[1];
  const handCard = sorted[sorted.length - 1]; // 最高 y 的一张手牌（无选中时手牌行即最高）
  log('click hand card', handCard.id);
  bot.activateEntity(handCard);
  await sleep(800);
  const pre = st;
  log('click button B', btnB.id);
  bot.activateEntity(btnB);
  await sleep(2000);
  log('after B: st=', JSON.stringify(st), 'pre=', JSON.stringify(pre));
  if (st && pre && (st.hands < pre.hands || st.discards < pre.discards)) {
    log('BUTTON B IS ACTIVE (play or discard)');
  } else {
    log('click button A', btnA.id);
    bot.activateEntity(btnA);
    await sleep(2000);
    log('after A: st=', JSON.stringify(st));
  }
  bot.chat('/balatro quit'); await sleep(1000);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
