const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
bot.on('kicked', r => console.log('KICKED', JSON.stringify(r)));
bot.on('error', e => console.log('ERR', e.message));
(async () => {
  await new Promise(r => bot.once('spawn', r));
  bot.setControlState('forward', true); await sleep(2600); bot.setControlState('forward', false);
  await bot.look(Math.PI, 0, false); await sleep(600);
  bot.chat('/balatro play META1');
  await sleep(3500);
  // 进入回合后 next 一轮太贵：直接看回合视图的 interaction 元数据结构
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'interaction' && e.position.distanceTo(bot.entity.position) < 16) {
      const md = e.metadata || [];
      const brief = md.map((m, i) => (m !== undefined && m !== null) ? `${i}:${typeof m === 'number' ? m.toFixed(2) : String(m).slice(0, 12)}` : '').filter(Boolean).join(' ');
      console.log('ENT', e.id, 'md[', brief, ']');
    }
  }
  bot.chat('/balatro quit'); await sleep(1200);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
