# DG-LAB V4 WebSocket 协议公开资料调研（Phase 3，只读）

来源：本地子智能体 `protocol-research-2` 于 2026-11-20 通过 web_search / web_fetch 调研
[dungeonlab-open/dglab-kit](https://github.com/dungeonlab-open/dglab-kit)（GPL-3.0）与
[dungeonlab-open/dglab-bluetooth-protocol](https://github.com/dungeonlab-open/dglab-bluetooth-protocol)。
仅作协议事实记录与独立实现依据；**未复制其 TypeScript 代码或波形数据库**。
（注：首个调研子智能体被 Provider `finish_reason: content_filter` 中断，未产出；本报告来自重试成功的第二个子智能体。）

## 1. device.op 操作类型参数结构

通用字段 `DeviceOperate`：`s`(slotId, 必填)、`t`(ActionType, 必填)、`c`(0=A/1=B, 必填)、
`p`(优先级 0/1/2, 默认 1)、`d`(持续时间 ms, 默认 0)、`im`(bool, 是否替换同设备/通道/类型的已有任务)。
任务 ID 即外层 RPC `reqId`。

| t | 类型 | 额外字段 | 生命周期 | 语义 |
|---|---|---|---|---|
| 0 | AppendPulseData | `v`,`ver?`,`seq?` | Continuous | 推送波形帧 |
| 3 | AddIntensity | `v`(可正负) | oneShot | 相对增减强度 |
| 4 | SetTempIntensity | `v` | Continuous | 临时强度，任务结束自动归 0 |
| 7 | SetIntensity | `v` | oneShot | **只接受 `v=0`**（绝对清零） |

**t=5：公开资料未找到**。dglab-kit 明确写“当前公开的 V4ActionType 仅包含 0/3/4/7 四种操作”。

**t=0 AppendPulseData 完整结构**：
- `s` 目标设备 slotId；`c` 通道；`d` 最长播放时长（ms）；持续任务 `d:0` 表示不按时长自动结束，
  但**帧列表消费完仍会完成**。
- `v`：`number[][] | string[]` 裸波形帧列表；字符串为十六进制完整字节对（如 `"0A0A0A0A00000000"`）。
- `ver`：可选，`2`=V2 帧；**省略或 `3`=V3 帧**。
- `seq`：可选，旧协议兼容的帧序列号。
- **tick≈100ms 消费机制**：每个 tick 消费 `v` 中一帧，tick 间隔通常 100ms
  （与官方 BLE 的 100ms 写入周期一致；V3 BLE 每 100ms 发 4 组 25ms 窗口数据）。
- 调度规则：不同设备/通道/任务类型/优先级独立调度；同键按队列执行；同 tick 连续入队的 oneShot
  在该 tick 内执行完；`im:true` 先替换同键已有任务。

**帧格式**：
- V2 帧 `[a, b, interval]`：a/b 为两通道波形数据，interval 为帧间隔。结合官方 BLE 文档
  （V2：X 脉冲数/Y 间隙/Z 脉宽=脉冲强度，官方 App Z 取 0–20），V2 帧经 APP 转换为 BLE 的 X/Y/Z 写入；
  **逐字段精确映射公开资料未找到**，仅知格式为三元组。
- V3 帧 `[a1..a4, b1..b4]`：a1–a4 为 A 通道 4 组（每组 25ms，合计 100ms），b1–b4 为 B 通道 4 组。
  对照官方 V3 BLE B0 命令（4×频率值+4×强度值/通道，频率 10–240、脉冲强度 0–100），
  **8 元帧如何拆分为频率/强度两参数公开资料未明确**（a1..a4 的确切语义未找到，不臆测）。

## 2. device.op 响应 reason 与 device.op.clear

`resp.result = {type, reason, slotId?, channel?}`。**入队不立即响应**，仅在任务结束时返回：
- `completed`：任务正常完成（oneShot 执行完 / 持续任务 `d` 到期 / AppendPulseData 帧消费完）。
- `cleared`：被 `device.op.clear` 清除。
- `replaced`：被 `im:true` 的同设备/通道/类型新任务替换。
- `cancelled`：连接断开时被取消。

`device.op.clear`：可无参（清全部）、`{s}`（清单设备全部通道）、`{s,c}`（清单设备单通道）。
成功返回 `{}`；**每个被清的带 reqId 队列任务各自收到 `reason:'cleared'` 的 device.op 响应**。
仅传 `{c}` 不带 `s` → `invalid_params`。RPC 错误码：`duplicate_request_id` / `invalid_params` /
`slot_not_found` / `invalid_operate` / `method_not_found` / `internal_error`。

## 3. APP→控制端事件与设备字段

均为应用消息 `{t:'ev', ev:...}`（外层 message 由服务器补 `clientId`）：
- **devices.snapshot**：APP attach 后立即全量上报 `{devices:[...]}`（空设备也发空数组），
  控制方**整体替换**本地列表。设备描述符：`id?`,`slotId`(真 ID),`name`,`type`(如 `COYOTE_030`),
  `props?`(完整快照),`slotState?`(完整快照)。
- **devices.patch**：`{added:[完整设备], removed:[slotId...]}`，增量更新，无需再请求全量。
- **slots.patch**：按 tick 批量状态变更 `{slots:[{slotId, props:{变更字段}, slotState:{变更字段}}]}`，
  **仅含变化字段，需按 slotId 深合并**。
- **client_attached / client_disconnected**：服务器帧；另有 `custom.action`（`{t:'ev',ev:'custom.action',action:0..9}`）。

**props（COYOTE_020/030）**：`power`(电量 0–100)、`version`、`label`、`intensityA/B`（A/B 通道当前强度）、
`connectState`（如 `'connected'`/`'disconnected'`）；COYOTE_030 额外：
`channelAStatus`/`channelBStatus`（0 无输出/过低、1 开路、2 正常、3 输出故障、4 通道屏蔽）、`updateValue`。

**slotState**：通用 `markLight`（yellow/green/red/purple/blue/cyan/null）、`hasDevice`（蓝牙是否已连接）；
`channelA/B`（同构）：`isMuted`（静音）、`warmUpScale`（冷启动缩放 0–1）、`intensityMax`
（由 comfortLimit 计算的当前强度上限）、`comfortLimit.{mode('simple'|'complex'), comfortMax, absoluteMax,
overheat, overheatPercent, autoIncr, autoIncrMax, autoIncrScope(1/2/3), totalIncr}`。

## 4. Coyote V2/V3 对 t=0 的支持与生命周期反馈

- V4 `AppendPulseData` 通过 `ver:2`（V2 帧）/`ver:3`（V3 帧，默认）**同时覆盖 COYOTE_020（V2）
  与 COYOTE_030（V3）**；SDK 内置 `COYOTE_WAVEFORMS` 提供合法十六进制帧。
  **逐设备类型的 t=0 能力矩阵官方未单独声明**。
- 波形任务生命周期反馈：仅通过 device.op 的终态 `resp`（reason 四值）+ `slots.patch` 的强度/状态增量；
  **播放过程中的进度/剩余帧回执公开资料未找到**。
- BLE 层补充：V3 波形无记忆，需每 100ms 持续写 B0；V2 同样每 100ms 写波形特征。

## 5. 许可证 / 使用限制

- **dglab-kit**：GPL v3（2026-07-10 加入）。衍生/分发须整体 GPL-3.0 开源；
  本工程只读协议文档并独立实现，不复制其代码/波形库。
- **DG-LAB 官方 Bluetooth Protocol 仓库**：README 声明面向爱好者开放集成，但
  **“Commercial use of this content requires prior authorization”**；未标注 OSI 开源许可证，
  商用集成前须取得官方授权。本工程 MIT 许可不改变上游材料的使用条件。
- 第三方 `DG-LAB-OPENSOURCE`（BLE，BETA）等社区项目非官方。

## 6. 未找到项汇总

- t=5 操作类型；
- V2/V3 帧逐字段（a/b/interval、a1..a4/b1..b4）精确语义；
- 波形播放进度回执；
- V4 协议的独立正式规范文档（dglab-kit README 即目前最完整的公开参考）。

## 7. 实际访问来源

1. https://github.com/dungeonlab-open/dglab-kit （README 全文，含 V4 协议参考）
2. https://github.com/dungeonlab-open/dglab-kit/blob/main/LICENSE （GPL-3.0 全文）
3. https://github.com/dungeonlab-open/dglab-bluetooth-protocol （官方 BLE 协议仓库 + 商用限制声明）
4. https://github.com/dungeonlab-open/dglab-bluetooth-protocol/blob/main/coyote/README.md
5. https://github.com/dungeonlab-open/dglab-bluetooth-protocol/blob/main/coyote/v3/README.md
6. 搜索结果聚合：npmjs.com/package/dglab-kit、socket.dev/npm/package/dglab-kit、
   cdn.jsdelivr.net/npm/dglab-kit、crates.io/crates/pulselink-ws、github.com/semo/dg-lab-opensource-english
   （社区项目，非官方，仅参考）
