// R220 实机验证脚本 10：合法长度恶意输入（插件层防线）+ 超长输入（协议层防线）分离验证
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
  let kicked = null;
  bot.on('kicked', r => { kicked = r; log(`${username} KICKED:`, JSON.stringify(r).slice(0, 160)); });
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.kickInfo = () => kicked;
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout ' + username)), 30000); });
  return bot;
}
function statusSnapshot(bot, waitMs = 1400) {
  return new Promise(async (resolve) => {
    let v = null;
    const onMsg = (json) => {
      const s = json.toString();
      if (/没有进行中/.test(s)) v = { phase: 'NOSESSION' };
      const m = s.match(/ante=(\d+) blind=(\w+) phase=(\w+) score=(\d+)\/(\d+) hands=(\d+) discards=(\d+) \$(\-?\d+)/);
      if (m) v = { phase: m[3] };
    };
    bot.on('message', onMsg);
    bot.chat('/balatro status');
    await sleep(waitMs);
    bot.removeListener('message', onMsg);
    resolve(v);
  });
}

(async () => {
  // ---- A 组：合法长度（≤256）恶意/畸形命令，插件层防线 ----
  const b = await makeBot('BalBot');
  const evil = [
    '/balatro play seed-with-$pecial!chars',      // 非法种子字符
    '/balatro play ' + 'A'.repeat(32),             // 恰好 32 合法上限
    '/balatro play ' + 'A'.repeat(33),             // 33 超种子上限
    '/balatro playcard 0 -1 99 1000000 2147483648',// 越界/溢出序号
    '/balatro playcard abc def',                   // 非数字
    '/balatro disc 99999999999999999999',          // 超 long
    '/balatro use 1 0 -5',
    '/balatro sellj -100',
    '/balatro sellc 99999',
    '/balatro buy 2147483647',
    '/balatro buybag 0',
    '/balatro buyvoucher -1',
    '/balatro pick 1000000000',
    '/balatro help ' + 'x'.repeat(200),
    '/balatro',
    '/balatro unknownsubcommand123',
    '/balatro reroll',
    '/balatro next',
    '/balatro go',
    '/balatro skip',
    '/balatro endless',
    '/balatro top',
    '/balatro play black gold 999 xyz',            // 非法赌注/种子混参
  ];
  for (const c of evil) { b.chat(c); await sleep(300); }
  await sleep(2500);
  const st = await statusSnapshot(b);
  step('evil_legal_length_survives', !b.kickInfo() && st !== null, `${evil.length} 条畸形命令后未踢、可交互（${b.kickInfo() ? '被踢!' : '在线'}，status=${st && st.phase}）`);
  b.chat('/balatro quit'); await sleep(1000);
  b.quit(); await sleep(1000);

  // ---- B 组：超长命令 → 协议层拒绝（预期踢出）→ 服务器仍健康 ----
  const b2 = await makeBot('BalBot2');
  b2.chat('/balatro play ' + 'A'.repeat(300));
  await sleep(4000);
  const kicked2 = !!b2.kickInfo();
  step('oversize_command_protocol_reject', kicked2, `313 字符命令被协议层拒绝踢出（${JSON.stringify(b2.kickInfo()).slice(0, 90)}）`);
  // 重连验证服务器仍正常
  if (kicked2) {
    try { b2.quit(); } catch {}
    await sleep(1500);
    const b3 = await makeBot('BalBot');
    const st3 = await statusSnapshot(b3);
    step('server_healthy_after_reject', st3 !== null, '拒绝后重连，插件仍响应');
    b3.quit();
  } else {
    step('server_healthy_after_reject', true, '未被踢（跳过重连验证）');
  }

  fs.writeFileSync(__dirname + '/check10-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check10-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
