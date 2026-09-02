const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
(async () => {
  await new Promise(r => bot.once('spawn', r));
  bot.chat('/tp BalBot 992.5 -60 400');
  await sleep(2000);
  await bot.look(Math.PI, 0, false);
  bot.chat('/balatro play META2');
  await sleep(3500);
  let withMeta = 0, noMeta = 0, wPos = 0;
  for (const id in bot.entities) {
    const e = bot.entities[id];
    if (e && e.name === 'interaction' && e.position && e.position.distanceTo(bot.entity.position) < 16) {
      const w = e.metadata ? e.metadata[8] : undefined;
      if (typeof w === 'number' && w > 0) withMeta++; else { noMeta++; if (noMeta <= 3) console.log('NO-META ent', e.id, 'md len', e.metadata && e.metadata.length); }
      wPos++;
    }
  }
  console.log(`interactions=${wPos} withMeta=${withMeta} noMeta=${noMeta}`);
  bot.chat('/balatro quit'); await sleep(1000);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
