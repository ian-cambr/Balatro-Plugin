const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11,19)}]`, ...a);
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
let st = null;
bot.on('message', (j) => { const s = j.toString(); const m = s.match(/hands=(\d+) discards=(\d+)/); if (m) st = { hands: +m[1], discards: +m[2] }; });
const inters = () => { const o = []; for (const id in bot.entities) { const e = bot.entities[id]; if (e && e.name === 'interaction' && e.position && e.position.distanceTo(bot.entity.position) < 16) o.push(e); } return o; };
async function tryLoc(name, tpCmd) {
  bot.chat('/balatro quit'); await sleep(900);
  if (tpCmd) { bot.chat(tpCmd); await sleep(1800); }
  await bot.look(Math.PI, 0, false);
  await sleep(300);
  bot.chat('/balatro play LOC-' + name);
  await sleep(3000);
  bot.setControlState('forward', true); await sleep(280); bot.setControlState('forward', false);
  await sleep(600);
  const list = inters().sort((a, b) => a.position.y - b.position.y);
  if (list.length < 3) { log(name, ': interactions=', list.length, 'ABORT'); return; }
  const [bA, bB] = list;
  const hand = list.filter(x => x !== bA && x !== bB);
  const pre = st && { ...st };
  bot.activateEntity(hand[0]); await sleep(600);
  bot.activateEntity(bB); await sleep(1400);
  let worked = st && pre && (st.discards < pre.discards || st.hands < pre.hands);
  if (!worked) {
    const l2 = inters().sort((a, b) => a.position.y - b.position.y);
    if (l2.length >= 3) {
      const h2 = l2.filter(x => x !== l2[0] && x !== l2[1]);
      if (h2.length) { bot.activateEntity(h2[0]); await sleep(600); bot.activateEntity(l2[0]); await sleep(1400); }
      worked = st && pre && (st.discards < pre.discards || st.hands < pre.hands);
    }
  }
  log(name, ': clicks', worked ? 'WORK ✓' : 'DEAD ✗', JSON.stringify(st), 'pre', JSON.stringify(pre), 'ints', list.length);
}
(async () => {
  await new Promise(r => bot.once('spawn', r));
  await tryLoc('spawn', '/tp BalBot -6.5 -60 9.5');
  await tryLoc('far992', '/tp BalBot 992.5 -60 400');
  await tryLoc('other', '/tp BalBot -500.5 -60 -500.5');
  bot.chat('/balatro quit'); await sleep(800);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
