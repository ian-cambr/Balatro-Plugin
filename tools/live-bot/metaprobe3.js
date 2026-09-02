const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
(async () => {
  await new Promise(r => bot.once('spawn', r));
  bot.chat('/tp BalBot 992.5 -60 400');
  await sleep(1500);
  await bot.look(Math.PI, 0, false);
  bot.chat('/balatro play META3');
  await sleep(3200);
  const dump = (tag) => {
    let ok = 0, zero = 0, undef = 0;
    for (const id in bot.entities) {
      const e = bot.entities[id];
      if (e && e.name === 'interaction' && e.position && e.position.distanceTo(bot.entity.position) < 16) {
        const w = e.metadata ? e.metadata[8] : undefined;
        if (typeof w !== 'number' || isNaN(w)) undef++;
        else if (w > 0) ok++; else zero++;
      }
    }
    console.log(tag, 'width>0:', ok, 'width=0:', zero, 'undefined:', undef);
  };
  dump('fresh ');
  bot.chat('/tp BalBot 992.5 -60 100');
  await sleep(6000);
  bot.chat('/tp BalBot 992.5 -60 400');
  await sleep(6000);
  dump('reloc ');
  bot.chat('/balatro quit'); await sleep(1000);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
