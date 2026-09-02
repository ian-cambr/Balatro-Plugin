const mineflayer = require('mineflayer');
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const bot = mineflayer.createBot({ host: '127.0.0.1', port: 25565, username: 'BalBot', version: '26.2', auth: 'offline' });
bot.on('kicked', r => console.log('KICKED', JSON.stringify(r)));
bot.on('error', e => console.log('ERR', e.message));
bot.on('message', (json) => {
  const s = json.toString();
  if (s.includes('/balatro') || /确认/.test(s)) {
    console.log('=== MSG toString:', s.slice(0, 100));
    try { console.log('=== json:', JSON.stringify(json.json).slice(0, 600)); } catch (e) { console.log('json prop err', e.message, Object.keys(json)); }
  }
});
(async () => {
  await new Promise(r => bot.once('spawn', r));
  bot.setControlState('forward', true); await sleep(2600); bot.setControlState('forward', false);
  await bot.look(Math.PI, 0, false); await sleep(600);
  // 直接命令通道拿消耗品：/balatro play 后用 buy 不可控——改用挑战 omelette？太重。
  // 最简：开局 round 阶段 cons 区无物——改用命令 sellj？也没有 joker。
  // 用「确认框」最短路径：play 后直接 /balatro skipack？无包。
  // 实测：盲注 skip 后商店……直接用上一局的思路太长——试试 use 确认框：无需持有物？
  // 结论：为触发确认框，打一小局买塔罗（同 check8 路径）太长；这里直接静态验证 JSON 形状——
  // 触发任意带按钮的消息：/balatro help 有 hover+click 令牌！用它 dump。
  bot.chat('/balatro help');
  await sleep(2500);
  bot.quit(); process.exit(0);
})().catch(e => { console.log('FATAL', e); process.exit(1); });
