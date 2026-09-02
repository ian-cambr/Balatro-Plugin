# live-bot — 实机假人验证工具（R220 建立）

mineflayer（MC 协议级假人客户端）驱动的**真实服务器**验证脚本集。
R220 审计轮（2026-08-17）首次建立，用于执行 `note/release/实机验证清单.md`
中纯逻辑测试无法覆盖的 Bukkit/网络/渲染层验证。

## 环境要求

- 真实 Paper 26.2 服务器（本轮用 `F:\paper-test-26.2`，offline 模式）
- Node.js + `npm install mineflayer`（1.21.11 时为协议 774；26.2 请按 mineflayer 支持的版本号调整）
- 服务器插件目录放入 balatro jar

## 假人接入要点（踩坑记录）

1. **offline UUID**：`ops.json` 需写入 `UUID.nameUUIDFromBytes("OfflinePlayer:<名>")`（MD5 v3），
   否则 op 命令全部伪装成 "Unknown command"。
2. **服务器侧实体计数神谕**：op 假人发
   `/execute as @e[type=minecraft:interaction] run say [CNT|interaction]`，
   从聊天回显计数——可见性无关的服务器真值。
3. **私有可见验证**：观察者假人的 `bot.entities` 收不到 `setVisibleByDefault(false)`
   实体的 spawn 包——客户端侧 0 实体即为证明。
4. **点击**：`bot.activateEntity(e)` 发 use_entity 包（`sneaking:false` 硬编码）；
   Shift+右键需手写包 `bot._client.write('use_entity', {target, mouse:0, sneaking:true, hand:0})`。
5. **陈旧实体过滤**：牌桌 Interaction 池复用 + 相位切换后零尺寸残留（服务器侧已摘标签，
   无害），假人按 `metadata[8] > 0`（宽度）过滤活跃命中盒。
6. **聊天按钮**：Paper 26.2 组件序列化为 NBT 风格 `click_event.command`
   （非老式 `clickEvent.value`）；从 `message.json` 提取后 `bot.chat(cmd)` 即等价点击。
7. **布局分类（旋转无关）**：牌桌 `right = dir×up = (−fz, 0, fx)`；按钮/手牌按
   `dot(pos−center, right)` 投影排序——**主操作按钮（play/go/reroll）恒在投影最小侧**；
   mineflayer 每次 activateEntity 会 lookAt 漂移视向，绝对 x 排序会翻车。
8. **协议层防线**：>256 字符命令被 Netty 解码拒绝（DecoderException 踢出）；
   `§`（U+00A7）触发 `illegal_characters` 踢出——都是**服务器自带防线**，预期行为。
9. **RCON 环境陷阱（重大，R221 发现）**：**Paper 26.2 启用 RCON 后 use_entity
   全灭**（A/B 四组对照：boot1/2/6 无 RCON 交互全部正常，boot3/4 有 RCON 时连
   原版右键上船都不行、服务器侧玩家坐标冻结的假象、mineflayer 确认包正常发出）。
   机理未定位（疑与该 Paper 构建的 RCON 实现有关）。**规约：假人验证一律在
   RCON 关闭的服务器上执行**；需要控制台时用「假人 op 直跑命令」或
   stop-and-restart 替代。相关假阴性排查记录：无交易村民右键无窗口（假阴性
   探针）；soak 脚本的「手数」是乐观计数——在 RCON 死服上会掩盖交互失效，
   必须以状态变化（score/hands/money）为断言。
10. **Interaction 的 `position.y` 是脚底坐标**（R225 check21 踩坑）：命中盒 foot 在
    锚点 y − 半高处（placeInteraction 约定），按牌面锚点绝对值（如消耗品行 0.78）
    过滤会得到 0 个实体——回合视图按「最上行」等相对聚类定位，或用锚点减 hh 推算。
11. **板面世界高度不定，行定位必须相对化**（R225 check21b 踩坑）：牌桌锚定玩家
    眼位（本服出生点地形 y≈-58），绝对 y 过滤全部失效；以最上行（商品行脚底）为
    基准做行间偏移（如持有消耗品行 = 基准 −1.79）才稳。
12. **vanilla `disconnect.spam` 对非 op 假人踢出**（R226 check22 发现）：持续
    ~1-2 条/秒的命令流即可让非 op 客户端被原版聊天反刷屏踢出（op 豁免——A/B
    实证：同速率 op 假人零踢出、/op 后复测 4/4 存活）。**非插件缺陷**（对真实
    玩家宏刷屏是预期防线）。规约：soak/负载假人一律入 ops.json；另注意 mineflayer
    分散出生点会触发「moved too quickly」位置纠正（harness 物理漂移，无害）。

## 脚本清单（R220 战役）

| 脚本 | 验证内容 | 结果 |
|---|---|---|
| check1/check2 | 牌桌生成/私有可见/退出即弃（/quit+断线）/点击链/服务器计数神谕自检 | 通过（check1 神谕配置错误，check2 修正后全过） |
| check3/check4 | 种子复现（同种子两局同手牌）/出牌计分/失败结算 | 通过（Shift 简介断言正则误报，实际「梅花 9」输出成功） |
| check5/check6 | 智能选牌（对/两对/三条+弃牌钓鱼）/清盲进商店 | 通过（旋转无关投影修正后） |
| check7/check8 | 购买成功路径/确认框两步流（click_event 提取→执行→入账）/150 连点 3s 轰炸/30 命令刷屏 | 5/5 通过 |
| check9 | 23+1 合法长度恶意命令/4 假人并发 soak 150s/TPS/内存 | 通过（evil 输入含超长项需分离，见 check10） |
| check10 | 恶意输入分层：插件层（合法长度 23 条全安全）+ 协议层（超长/§ 注入双防线） | 3/3 通过 |
| check21 | R225 #80：目标类消耗品全息链路（买战车→回合选中→确认框 `@id` 快照→使用成功→卡面变钢铁，无「请选择」错） | 10/10 通过 |
| check21b | R225：商店右键持有星球→`[确认使用][确认出售][取消]` 双按钮→商店内使用成功 | 5/5 通过 |
| check22/22b | R226：use 新语法敌意输入电池 26 条 + 单 bot 118 条高频混合 + 4 假人并发负载（TPS/存活/压力后开局） | 26/26 + 3/3 通过 |
| check23 | R228：GUI 向导六步开局 × 目标类消耗品链路交叉（`tarot:hanged @50` 快照→使用成功→效果落地）+ 双通道竞态（命令抢先开局后 GUI 开始被拒、单会话） | 9/9 通过 |
| check24 | R229：种子聊天 60s 窗口七场景（设置回显/取消/无效保留/命令混发不消耗/返回 GUI 清理/双发原子认领/超时放行） | 10/10 通过 |
| check25 | R229：Vault+EssentialsX 3 假人并发——清盲档 $1 精确到账、失败局 Δ$0、stats.txt 新增行==失败局数全 7 段、wins.txt 不变 | 5/5 通过 |
| check26 | R230：离线种子搜索（真实引擎镜像策略 2/4000 命中 W26A1446）→ 命令驱动实机复现**完整通关**——首个底注 Δ=13（3×1+10）、整局 Δ=204（24×1+8×10+100）三档分毫不差 | 4/4 通过 |
| check27 | R231：通关后无尽模式——Δ=205==25×1+8×10+100（无尽清盲 +$1、失败不发通关奖）、stats 恰 +2 行（win+endless loss）、winCounter 恰=通关次数（无尽失败不重复）；⚠ 脚本 UUID 须按 MD5 v3 设 version/variant 位（直接拼 hex 会误报 wins=0） | 语义 9/9 |
| check28 | R231：迁移矩阵×选中/确认态——远距传送/跨世界(下界)/死亡重生/跨区块卸载重载四场景：牌桌重建 + 选中保持（再右键仍「将作用于已选牌」）+ 迁移前 @ids 命令跨迁移执行成功 | 4 场景全过（两轮合并） |
| check29 | R232：4 假人同一通关种子并发竞速——四局 trace 逐字一致（110 段）、四 Δ=204 互不串扰、TPS 十样本全 20、stats 恰 +4 行 wins 各 +1 | 6/6 通过 |
| check30 | R233：20 假人满载 soak（8min）复验 v0.4.61 新路径——目标确认链 16 试 7 成功、商店使用按钮 18 试 9 成功、TPS 八样本全 20、stats 155==失败局全 7 段、零踢出 | 6/6 通过（c 轮） |
| check31 | R234：jokerless 禁入 × 新路径——两轮商店零小丑行/零审判幽灵灵魂、非禁入塔罗仍售（3/6 过项）；贴纸采样 18 只 0 命中（联合 ~6% 运气偏差）→ 由 `ShopStickerStakeTest` 固定种子锁补位；⚠ 本地探针 classpath 须含 `build/resources/main`（缺则 jokermeta 加载失败→桶空→商店全塔罗，易误判为引擎缺陷） | 禁入面过/贴纸面引擎锁 |
| check32 | R235：psychic Boss × @ids 链——种子 B2351 小盲实机 3 手可清（317/300），但 big 盲真实路径手牌不可清（220/450×7 手）→ Boss 回合未达；⚠ **种子预验必须模拟真实流路径**（`nextBlind="big"` 伪推进的手牌 ≠ 真实小盲消耗后的手牌）——语义由 `BossPsychicConsumableTargetTest` 引擎锁闭合 | 引擎锁闭合 |
| check33 | R236：真实流路径预验（RealPathScan 逐阶段推进）命中 R236X51 → 双盲实机推进到 psychic Boss 回合——出 1 张被拒（通灵者文案）、确认框 `tarot:star @37` 快照、使用成功、梅花 Q→方块 Q 效果落地 | 6/6 通过 |
| check34 | R237：medusa 石头局 × 目标确认链——石头手牌（`N:石头`）渲染/推进/小盲可清、商店购买（禁入子集生效）、确认框 `tarot:star @26` 快照、使用成功、红桃 A→方块 A 落地；⚠ medusa 持永恒大理石小丑 → 最上行=小丑行，消耗品行=小丑行脚底−0.62（行距偏移法）；bell 为终结者 Boss（仅 ante8，`FINISHERS` 池），ante1 采样不存在 → `BossBellConsumableTargetTest` 引擎锁补位 | 7/7 通过 |
| check35/35b | R238：**#81 缺陷发现与修复实证**——xray 挑战开局回合 3 张面朝下：status 手牌 `？`×3、8 张按序 Shift 简介中面朝下牌显示「面朝下的牌（内容未知）」位置精确对应、身份零泄露（0.4.62）；water/mark Boss 回合的实机推进不稳定（脚本策略镜像仍与实机有微差），语义由 `BossWaterNoDiscardTest` 引擎锁闭合 | 修复实证 + 引擎锁 |
| check36 | R240：**命令驱动推进（分叉结构性消除）+ 逐动作对账日志落地**——water W238w369 与 mark W238m603 双盲命令推进到 Boss 回合：water 弃牌被拒（「没有剩余弃牌次数」）+ `tarot:moon @26` 使用成功；mark status 3 张 ？ + `tarot:sun @39` 使用成功；`check36-actions.log` 为实机侧对账基准（规约 #13 基建） | 8/8 通过 |
| probe/metaprobe/jsonprobe | 盲注 go 点击聚焦探针/交互元数据/聊天 JSON 结构 | 诊断工具 |

`*-results.json` 为各轮断言结果存档。

## 复跑方式

```bash
cd <服务器目录>/bot && npm install mineflayer
node check2.js   # 退出即弃等
node check8.js   # 购买/确认框/轰炸
node check9.js   # 恶意输入 + 4 bot soak
```

服务器需已部署 balatro jar；ops.json 按要点 1 配置 BalBot/BalBot2。

13. **模拟镜像必须逐动作对账**（R239）：实机脚本的动作通道是实体点击（use_entity
    包），**命令日志不可见**——对账基准必须是聊天 status 快照（动作前 hand → 动作
    → 动作后 score/hands/discards），模拟侧输出同格式 `ACTION [hand] params ->
    post-state`，首个不等行即分叉点；禁止只比「种子可清/不可清」结论不比过程
    （R238 的 W238w183「预验通过实机失败」曾因此无法定位）。
