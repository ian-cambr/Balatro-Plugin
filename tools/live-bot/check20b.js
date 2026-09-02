const mineflayer = require('mineflayer');
const fs = require('fs');
const log = (...a) => console.log(`[${new Date().toISOString().slice(11,19)}]`, ...a);
const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const results = [];
function step(n, ok, d) { results.push({ n, ok, d }); log(`STEP ${ok?'OK  ':'FAIL'} ${n} :: ${d}`); }
function balCount(bot, r = 16) { let c=0; for (const id in bot.entities) { const e=bot.entities[id]; if (e && e.position && e!==bot.entity && e.position.distanceTo(bot.entity.position)<=r && (e.name==='interaction'||e.name==='text_display')) c++; } return c; }
async function makeBot(u) {
  const bot = mineflayer.createBot({ host:'127.0.0.1', port:25565, username:u, version: '26.2', auth:'offline' });
  bot.on('kicked', r => log(u,'KICKED',JSON.stringify(r).slice(0,120)));
  bot.on('error', e => log(u,'ERR',e.message));
  bot.on('message', j => { const s=j.toString(); if (s) log(`CHAT<${u}> ${s.slice(0,150)}`); });
  await new Promise((res,rej)=>{ bot.once('spawn',res); setTimeout(()=>rej(new Error('timeout '+u)),40000); });
  return bot;
}
(async () => {
  const np1 = await makeBot('NoPlay1'); // 先入服（LP 用户建立）
  await sleep(1500);
  const admin = await makeBot('BalBot');
  await sleep(1500);
  admin.chat('/lp user NoPlay1 parent add noplay'); // 在线时授权
  await sleep(3000);
  admin.chat('/lp user NoPlay1 permission info');
  await sleep(2000);
  let denied = false;
  const onM = j => { if (/权限/.test(j.toString())) denied = true; };
  np1.on('message', onM);
  np1.chat('/balatro play DENY2');
  await sleep(3000);
  const ents = balCount(np1);
  step('online_assign_denied', denied, `在线授权后拒绝消息=${denied}，实体=${ents}`);
  // LuckPerms 权限检查（LP 自带权限查看）
  admin.chat('/lp user NoPlay1 permission check balatro.play');
  await sleep(2000);
  np1.quit(); admin.quit(); await sleep(1000);
  fs.writeFileSync(__dirname+'/check20b-results.json', JSON.stringify(results,null,2));
  const fails = results.filter(r=>!r.ok).length;
  log('RESULT', (results.length-fails)+'/'+results.length);
  process.exit(fails?1:0);
})().catch(e=>{log('FATAL',e);process.exit(2);});
