const mineflayer = require('mineflayer');
const { rconCommand } = require('./rcon.js');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log(`[${new Date().toISOString().slice(11,19)}]`, ...a);
(async () => {
  const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
  bot.on('kicked', r => log('KICKED', JSON.stringify(r).slice(0, 120)));
  await new Promise(r => bot.once('spawn', r));
  bot.chat('/balatro play GRACE1');
  await sleep(3000);
  log('in-game, requesting stop');
  try { await rconCommand('127.0.0.1', 25575, 'balatro220', 'stop', 10000); } catch (e) { log('rcon stop:', e.message); }
  await sleep(8000);
  log('server should be down; bot disconnect:', bot.entity === null || 'still-alive?');
  process.exit(0);
})().catch(e => { log('FATAL', e); process.exit(1); });
