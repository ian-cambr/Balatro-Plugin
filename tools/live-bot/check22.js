// R226 实机验证脚本 22：use 新语法敌意输入电池 + 高频混合压力 + 4 假人并发循环
// 镜头：不信任任何用户输入（改客户端伪造命令）+ 长时高负载稳定性。
const mineflayer = require('mineflayer');
const fs = require('fs');

const HOST = '127.0.0.1', PORT = 25565, VER = '26.2';
const log = (...a) => console.log(`[${new Date().toISOString().slice(11, 23)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(name, ok, detail) {
  results.push({ name, ok, detail });
  log(`STEP ${ok ? 'OK  ' : 'FAIL'} ${name} :: ${detail}`);
}

async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 200)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

/** 发命令并收集窗口内回复（前缀匹配任一期望即算应答）。 */
async function fire(bot, cmd, expects, waitMs = 1200) {
  const got = [];
  const onMsg = (json) => got.push(json.toString());
  bot.on('message', onMsg);
  bot.chat(cmd);
  await sleep(waitMs);
  bot.removeListener('message', onMsg);
  const hit = got.find(s => expects.some(e => s.includes(e)));
  return { hit, got };
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2600); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(800);

  // ---------- 阶段 1：敌意输入电池（无消耗品的回合中） ----------
  b1.chat('/balatro play ADV22A');
  await sleep(3500);
  const battery = [
    ['/balatro use 0', ['无效', '用法', '没有']],
    ['/balatro use -1', ['无效', '用法']],
    ['/balatro use 99', ['无效', '当前没有', '请选择', '无效消耗品', '列表已变化']],
    ['/balatro use abc', ['无效', '用法']],
    ['/balatro use', ['用法']],
    ['/balatro use 1 @', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @,', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @1,', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @1,1', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @0', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @-5', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @999999999999', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @1,2,3,4,5,6', ['无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @1 @2', ['无效参数', '无效的目标令牌', '无效消耗品', '列表已变化']],
    ['/balatro use 1 tarot:nonexistent @1', ['列表已变化', '无效消耗品']],
    ['/balatro use 1 tarot:x:y @1', ['列表已变化', '无效消耗品']],
    ['/balatro use 1 2 @1', ['不能同时', '无效消耗品', '列表已变化']],
    ['/balatro use 1 @1 2', ['不能同时', '无效消耗品', '列表已变化']],
    ['/balatro use 1 2 2', ['无效消耗品', '请选择', '列表已变化']],
    ['/balatro use 1 999999', ['手牌序号越界', '无效消耗品', '列表已变化']],
    ['/balatro use 1 0000000002', ['无效消耗品', '请选择', '列表已变化']], // 10 位前导零 → 值 2
    ['/balatro use 1 1 2 3 4 5 6', ['无效消耗品', '请选择', '列表已变化']],
    ['/balatro use 1 ＠1', ['无效的手牌序号', '无效的目标令牌', '无效消耗品', '列表已变化']], // 全角@
    ['/balatro use 1 ２', ['无效的手牌序号', '无效消耗品', '列表已变化']], // 全角数字
    ['/balatro use 1 @2147483647', ['无效的目标令牌', '无效消耗品', '列表已变化', '手牌已变化']],
    ['/balatro use 1 @２', ['无效的目标令牌', '无效消耗品', '列表已变化']],
  ];
  let passN = 0; const fails = [];
  for (const [cmd, exp] of battery) {
    const r = await fire(b1, cmd, exp);
    const alive = await fire(b1, '/balatro status', ['ante=', '当前没有'], 900);
    if (r.hit && alive.hit) passN++;
    else fails.push({ cmd, got: r.got.slice(0, 3) });
  }
  step('adversarial_battery', passN === battery.length, `${passN}/${battery.length}${fails.length ? ' :: ' + JSON.stringify(fails.slice(0, 3)) : ''}`);

  // ---------- 阶段 2：高频混合压力（单 bot，120 条 / ~20s） ----------
  const t0 = Date.now();
  let n = 0;
  const mix = ['/balatro status', '/balatro cons', '/balatro use 1 @1', '/balatro use 1 2 2', '/balatro sellj 1 x', '/balatro sellc 1 planet:x', '/balatro help 3', '/balatro top'];
  let i = 0;
  while (Date.now() - t0 < 20000) {
    b1.chat(mix[i++ % mix.length]); n++;
    await sleep(160);
  }
  const st2 = await fire(b1, '/balatro status', ['ante=', '当前没有'], 1500);
  step('highfreq_single', !!st2.hit && b1.entity, `${n} 条命令后 status=${!!st2.hit} 在线=${!!b1.entity}`);

  b1.chat('/balatro quit'); await sleep(1000);

  // ---------- 阶段 3：4 假人并发循环（play→命令轰炸→quit，150s） ----------
  const names = ['BalBot', 'BalBot2', 'AdvBot3', 'AdvBot4'];
  const bots = [b1];
  for (const nm of names.slice(1)) bots.push(await makeBot(nm));
  for (const bt of bots) { bt.setControlState('forward', true); }
  await sleep(2600);
  for (const bt of bots) { bt.setControlState('forward', false); await bt.look(Math.PI, 0, false); }
  await sleep(600);

  const tpsSamples = [];
  const t1 = Date.now();
  let round = 0;
  const stop = {};
  await Promise.all(bots.map(async (bt, idx) => {
    const nm = names[idx];
    while (Date.now() - t1 < 150000 && !stop[nm]) {
      round++;
      bt.chat(`/balatro play ST22${nm}_${round}`);
      await sleep(2500);
      const burst = Math.floor(Math.random() * 15) + 15;
      for (let k = 0; k < burst; k++) {
        bt.chat(['/balatro status', '/balatro cons', '/balatro use 1 @1,2', '/balatro sellj 1 joker:x', '/balatro help 5', '/balatro top'][Math.floor(Math.random() * 6)]);
        await sleep(70 + Math.random() * 100);
      }
      bt.chat('/balatro quit');
      await sleep(700);
    }
  }));
  // TPS 采样（op bot）
  for (let k = 0; k < 4; k++) {
    const r = await fire(b1, '/tps', ['TPS', 'tps'], 1500);
    const m = r.hit && r.hit.match(/([\d.]+),?\s*([\d.]+)?/);
    if (m) tpsSamples.push(parseFloat(m[1]));
    await sleep(5000);
  }
  const aliveN = bots.filter(bt => bt.entity).length;
  const minTps = tpsSamples.length ? Math.min(...tpsSamples) : -1;
  step('concurrent_4bots_alive', aliveN === 4, `${aliveN}/4 存活，轮次≈${round / 4 | 0}/bot`);
  step('tps_under_load', minTps >= 19.0, `TPS 样本=${tpsSamples.join(',')} min=${minTps}`);

  // 压力后状态完整性：每人可正常开新局并退出
  let okRe = 0;
  for (const bt of bots) { bt.chat('/balatro play POST22'); }
  await sleep(3000);
  for (const bt of bots) { const r = await fire(bt, '/balatro status', ['ante=', '当前没有'], 1200); if (r.hit) okRe++; }
  step('post_stress_sessions', okRe === 4, `${okRe}/4 可开新局`);
  for (const bt of bots) bt.chat('/balatro quit');
  await sleep(1200);
  for (const bt of bots) bt.quit();
  await sleep(1000);

  fs.writeFileSync(__dirname + '/check22-results.json', JSON.stringify(results, null, 2));
  const fails2 = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails2}/${results.length} steps OK`);
  process.exit(fails2 ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check22-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
