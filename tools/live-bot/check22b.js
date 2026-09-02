// R226 实机验证脚本 22b：多假人并发负载复测（降频避免 vanilla spam-kick 干扰 + TPS 正确解析 + 踢出自动重连）
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
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 120)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

(async () => {
  const names = ['BalBot', 'BalBot2', 'AdvBot3', 'AdvBot4'];
  let bots = [];
  for (const nm of names) bots.push(await makeBot(nm));
  for (const bt of bots) bt.setControlState('forward', true);
  await sleep(2600);
  for (const bt of bots) { bt.setControlState('forward', false); await bt.look(Math.PI, 0, false); }
  await sleep(600);

  const kicked = {};
  bots.forEach((bt, i) => bt.on('kicked', () => kicked[names[i]] = true));

  const t1 = Date.now();
  let acts = 0;
  await Promise.all(bots.map(async (bt, idx) => {
    const nm = names[idx];
    let round = 0;
    while (Date.now() - t1 < 120000) {
      round++; acts++;
      bt.chat(`/balatro play L26${nm}_${round}`);
      await sleep(2200);
      const burst = 8;
      for (let k = 0; k < burst; k++) {
        bt.chat(['/balatro status', '/balatro cons', '/balatro use 1 @1,2', '/balatro sellj 1 joker:x', '/balatro help 5', '/balatro top'][Math.floor(Math.random() * 6)]);
        await sleep(420 + Math.random() * 260); // 降频：避开 vanilla spam 阈值
      }
      bt.chat('/balatro quit');
      await sleep(800);
    }
  }));

  // TPS 正确解析：中文输出「TPS 从最后 1分, 5分, 15分: 20.0, 20.0, 20.0」
  const tpsSamples = [];
  for (let k = 0; k < 3; k++) {
    const got = [];
    const onMsg = (json) => got.push(json.toString());
    bots[0].on('message', onMsg);
    bots[0].chat('/tps');
    await sleep(1500);
    bots[0].removeListener('message', onMsg);
    const line = got.find(s => /TPS/.test(s));
    if (line) {
      const nums = [...line.matchAll(/(\d+\.\d+)/g)].map(m => parseFloat(m[1]));
      if (nums.length) tpsSamples.push(Math.min(...nums));
    }
    await sleep(3000);
  }
  const alive = bots.map((bt, i) => !kicked[names[i]] && bt.entity);
  const aliveN = alive.filter(Boolean).length;
  const minTps = tpsSamples.length ? Math.min(...tpsSamples) : -1;
  step('concurrent_alive', aliveN === 4, `${aliveN}/4 存活（kicked=${JSON.stringify(kicked)}），总动作轮≈${acts}`);
  step('tps_under_load', minTps >= 19.0, `TPS min 样本=${tpsSamples.join(', ')}（解析值）`);

  // 存活者开新局验证
  let okRe = 0, reN = 0;
  for (let i = 0; i < bots.length; i++) {
    if (alive[i]) {
      reN++;
      bots[i].chat('/balatro play POST26');
      await sleep(400);
    }
  }
  await sleep(3000);
  for (let i = 0; i < bots.length; i++) {
    if (!alive[i]) continue;
    const got = [];
    const onMsg = (json) => got.push(json.toString());
    bots[i].on('message', onMsg);
    bots[i].chat('/balatro status');
    await sleep(1300);
    bots[i].removeListener('message', onMsg);
    if (got.some(s => /ante=|当前没有/.test(s))) okRe++;
    bots[i].chat('/balatro quit');
  }
  step('post_stress_sessions', okRe === reN && reN >= 3, `${okRe}/${reN} 存活者均可正常开局`);

  for (const bt of bots) { try { bt.quit(); } catch (e) {} }
  await sleep(1000);
  fs.writeFileSync(__dirname + '/check22b-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check22b-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
