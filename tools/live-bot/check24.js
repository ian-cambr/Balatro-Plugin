// R229 实机验证脚本 24：GUI 种子聊天输入 60s 窗口 × 确认流时序交叉
// 场景：正常设置 / 取消 / 无效种子保留 / 命令混发不消耗窗口 / ESC 关闭清理 / 双发原子认领 / 60s 超时放行
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
const chatLog = [];
let rawSink = null;

function findSlotByLabel(window, label) {
  for (let i = 0; i < window.slots.length; i++) {
    const it = window.slots[i];
    if (!it) continue;
    const n = (it.customName ? JSON.stringify(it.customName) : '') + (it.name || '');
    const plain = it.customName ? JSON.stringify(it.customName).replace(/\\u00a7./g, '') : '';
    if (n.includes(label) || plain.includes(label)) return i;
  }
  return -1;
}
function jsonText(obj, out) {
  if (!obj || typeof obj !== 'object') return;
  if (typeof obj.text === 'string') out.push(obj.text);
  if (obj.extra) obj.extra.forEach(x => jsonText(x, out));
  if (obj.with) obj.with.forEach(x => jsonText(x, out));
  return out;
}
async function makeBot(username) {
  const bot = mineflayer.createBot({ host: HOST, port: PORT, username, version: VER, auth: 'offline' });
  bot.on('kicked', r => log(`${username} KICKED:`, JSON.stringify(r).slice(0, 160)));
  bot.on('error', e => log(`${username} ERROR:`, e.message));
  bot.on('message', (json) => {
    const s = json.toString();
    if (!s) return;
    chatLog.push(s);
    if (rawSink) rawSink.push(json.json ? json.json : json);
    if (!/^\[Interaction\]|操作说明|直接右键 = |Shift \+ 右键 = /.test(s)) log(`CHAT<${username}> ${s.slice(0, 150)}`);
  });
  await new Promise((res, rej) => { bot.once('spawn', res); setTimeout(() => rej(new Error('spawn timeout')), 30000); });
  return bot;
}

/** 打开向导走到确认页，返回当前窗口（通过 windowOpen 事件跟踪最新）。 */
async function toConfirmPage(bot) {
  let win = null;
  const onOpen = (w) => { win = w; };
  bot.on('windowOpen', onOpen);
  bot.chat('/balatro gui');
  await sleep(1800);
  const clickLabeled = async (labels) => {
    for (const label of labels) {
      const slot = findSlotByLabel(win, label);
      if (slot < 0) { log('slot miss:', label); return false; }
      await bot.clickWindow(slot, 0, 0);
      await sleep(1100);
    }
    return true;
  };
  await clickLabeled(['标准局']); await sleep(600);
  await clickLabeled(['红色牌组', '下一步']); await sleep(600);
  await clickLabeled(['0 白注', '下一步']); await sleep(900);
  bot.removeListener('windowOpen', onOpen);
  return win;
}

/** 收集一段时间内的「种子已设置为 X」消息全文。 */
function captureSeedReplies(bot, ms) {
  return new Promise(async (resolve) => {
    const hit = [];
    const onMsg = (json) => {
      const s = json.toString();
      if (/种子已设置为|已取消种子输入|无效种子/.test(s)) hit.push(s);
      if (/<BalBot>/.test(s)) hit.push('ECHO:' + s);
    };
    bot.on('message', onMsg);
    await sleep(ms);
    bot.removeListener('message', onMsg);
    resolve(hit);
  });
}

(async () => {
  const b1 = await makeBot('BalBot');
  b1.setControlState('forward', true); await sleep(2400); b1.setControlState('forward', false);
  await b1.look(Math.PI, 0, false); await sleep(600);
  b1.chat('/balatro quit'); await sleep(1000);

  // ---- 1. 正常设置：种子图标左键 → 聊天输入 → 设置成功 + 确认页重开 + 图标显示该种子 ----
  {
    let win = await toConfirmPage(b1);
    step('confirm_page_reached', !!win, win ? '向导到确认页' : '未到确认页');
    const seedSlot = win ? findSlotByLabel(win, '种子') : -1;
    step('seed_slot_found', seedSlot >= 0, `slot=${seedSlot}`);
    const p = captureSeedReplies(b1, 3500);
    await b1.clickWindow(seedSlot, 0, 0); // 左键 → promptSeed（程序化关界面，保留等待）
    await sleep(900);
    b1.chat('SEEDOK1');
    const replies = await p;
    const setOk = replies.some(s => s.includes('种子已设置为') && s.includes('SEEDOK1'));
    step('seed_set_reply', setOk, replies.filter(s => !s.startsWith('ECHO')).join(' | ').slice(0, 90));
    // 确认页重开 + 图标显示新种子
    await sleep(1500);
    let win2 = b1.currentWindow || null;
    const labelShown = win2 ? findSlotByLabel(win2, 'SEEDOK1') >= 0 : false;
    step('confirm_reopen_with_seed', labelShown, labelShown ? '图标含 SEEDOK1' : `win=${!!win2}`);
    if (win2) { try { b1.closeWindow(win2); } catch (e) {} }
    await sleep(600);
  }

  // ---- 2. 取消：prompt → 聊天「取消」 ----
  {
    let win = await toConfirmPage(b1);
    const slot = win ? findSlotByLabel(win, '种子') : -1;
    if (slot < 0) { step('seed_cancel', false, '种子槽未找到'); return; }
    const p = captureSeedReplies(b1, 3000);
    await b1.clickWindow(slot, 0, 0);
    await sleep(900);
    b1.chat('取消');
    const replies = await p;
    step('seed_cancel', replies.some(s => s.includes('已取消种子输入')), replies.filter(s => !s.startsWith('ECHO')).join(' | ').slice(0, 80));
    await sleep(1200);
    if (b1.currentWindow) { try { b1.closeWindow(b1.currentWindow); } catch (e) {} }
    await sleep(500);
  }

  // ---- 3. 无效种子：原设置保留 ----
  {
    let win = await toConfirmPage(b1);
    const slot = win ? findSlotByLabel(win, '种子') : -1;
    if (slot < 0) { step('seed_cancel', false, '种子槽未找到'); return; }
    const p = captureSeedReplies(b1, 3000);
    await b1.clickWindow(slot, 0, 0);
    await sleep(900);
    b1.chat('bad seed!'); // 空格+叹号 → 非法字符集
    const replies = await p;
    step('seed_invalid_kept', replies.some(s => s.includes('无效种子')) && !replies.some(s => s.includes('种子已设置为')), replies.filter(s => !s.startsWith('ECHO')).join(' | ').slice(0, 90));
    await sleep(1200);
    if (b1.currentWindow) { try { b1.closeWindow(b1.currentWindow); } catch (e) {} }
    await sleep(500);
  }

  // ---- 4. 命令混发：prompt 中发 /balatro status（命令不被吞、窗口仍有效） ----
  {
    let win = await toConfirmPage(b1);
    const slot = win ? findSlotByLabel(win, '种子') : -1;
    if (slot < 0) { step('seed_cmd_mix', false, '种子槽未找到'); return; }
    await b1.clickWindow(slot, 0, 0);
    await sleep(900);
    const p = captureSeedReplies(b1, 5000);
    const cmdOk = await new Promise(async (resolve) => {
      let ok = false;
      const onMsg = (json) => { if (/ante=|当前没有进行中的局/.test(json.toString())) ok = true; };
      b1.on('message', onMsg);
      b1.chat('/balatro status');
      await sleep(1600);
      b1.removeListener('message', onMsg);
      resolve(ok);
    });
    b1.chat('SEEDCMD9');
    const replies = await p;
    const setAfter = replies.some(s => s.includes('种子已设置为') && s.includes('SEEDCMD9'));
    step('seed_cmd_mix', cmdOk && setAfter, `命令执行=${cmdOk} 窗口仍有效=${setAfter}`);
    await sleep(1400);
    if (b1.currentWindow) { try { b1.closeWindow(b1.currentWindow); } catch (e) {} }
    await sleep(500);
  }

  // ---- 5. 返回 GUI 清理：promptSeed 已自动关界面（无窗可 ESC）——真实放弃路径是再开 GUI（openMenu 清 pendingSeeds） ----
  {
    let win = await toConfirmPage(b1);
    const slot = findSlotByLabel(win, '种子');
    await b1.clickWindow(slot, 0, 0);
    await sleep(1000);
    b1.chat('/balatro gui'); // 返回向导（openMenu 应清除种子等待）
    await sleep(2000);
    try { if (b1.currentWindow) b1.closeWindow(b1.currentWindow); } catch (e) {}
    await sleep(800);
    const p = captureSeedReplies(b1, 3000);
    b1.chat('HELLOWORLD');
    const replies = await p;
    const notSwallowed = !replies.some(s => s.includes('种子已设置为')) && replies.some(s => s.startsWith('ECHO'));
    step('seed_gui_return_cleanup', notSwallowed, `未吞=${!replies.some(s => s.includes('种子已设置'))} 有回显=${replies.some(s => s.startsWith('ECHO'))}`);
  }

  // ---- 6. 双发原子认领：连发两条，仅第一条被吞为种子 ----
  {
    let win = await toConfirmPage(b1);
    const slot = win ? findSlotByLabel(win, '种子') : -1;
    if (slot < 0) { step('seed_atomic_claim', false, '种子槽未找到'); return; }
    await b1.clickWindow(slot, 0, 0);
    await sleep(900);
    const p = captureSeedReplies(b1, 4000);
    b1.chat('SEEDFIRST');
    b1.chat('SEEDSECOND');
    const replies = await p;
    const setMsgs = replies.filter(s => s.includes('种子已设置为'));
    const firstClaimed = setMsgs.length === 1 && setMsgs[0].includes('SEEDFIRST');
    const secondPassed = replies.some(s => s.startsWith('ECHO') && s.includes('SEEDSECOND'));
    step('seed_atomic_claim', firstClaimed && secondPassed, `设置=${setMsgs.length}条首条=${firstClaimed} 次条放行=${secondPassed}`);
    await sleep(1400);
    if (b1.currentWindow) { try { b1.closeWindow(b1.currentWindow); } catch (e) {} }
    await sleep(500);
  }

  // ---- 7. 60s 超时放行 ----
  {
    let win = await toConfirmPage(b1);
    const slot = findSlotByLabel(win, '种子');
    await b1.clickWindow(slot, 0, 0);
    await sleep(1000);
    // （上一 close 已清等待）
    // 重新 prompt（上一 close 已清等待）
    win = await toConfirmPage(b1);
    const slot2 = findSlotByLabel(win, '种子');
    await b1.clickWindow(slot2, 0, 0);
    await sleep(1000);
    log('等待 61s 超时...');
    await sleep(61000);
    const p = captureSeedReplies(b1, 3500);
    b1.chat('SEEDLATE7');
    const replies = await p;
    const notSet = !replies.some(s => s.includes('种子已设置为'));
    const passed = replies.some(s => s.startsWith('ECHO') && s.includes('SEEDLATE7'));
    step('seed_timeout_pass', notSet && passed, `未误吞=${notSet} 聊天放行=${passed}`);
  }

  b1.quit(); await sleep(1000);
  fs.writeFileSync(__dirname + '/check24-results.json', JSON.stringify(results, null, 2));
  const fails = results.filter(r => !r.ok).length;
  log(`RESULT: ${results.length - fails}/${results.length} steps OK`);
  process.exit(fails ? 1 : 0);
})().catch(ex => { log('FATAL', ex); fs.writeFileSync(__dirname + '/check24-results.json', JSON.stringify(results, null, 2)); process.exit(2); });
