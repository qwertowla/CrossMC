# CrossMC 实机验证清单

> 目的：按顺序验证协议 v3 的各条链路。**前一步没通过就不要进入下一步**，否则问题会叠加难以定位。
> 每一步都给出：操作 / 预期 / 怎么检查 / 失败排查。
>
> 前提：本轮实现只编译通过、**尚未实机验证**；本清单用于逐步确认。

---

## 0. 前置与安装

### 构建

两个独立仓库（容器内同级）：`CrossMC\`（框架）与 `HowToFishMC\`（宿主适配器）。

```powershell
# 1) CrossMC 框架
# Minecraft（需要 JDK 25 跑 Gradle，模组目标 Java 21）
$env:JAVA_HOME='<JDK 25 路径>'
cd CrossMC\minecraft
.\gradlew.bat clean build
# -> build\libs\crossmc-fabric-0.1.0.jar

# C# 绑定（框架仓库内）
cd ..\bindings\csharp
dotnet build -c Release
# -> bin\Release\netstandard2.1\CrossMC.Bindings.dll

# 2) HowToFishMC 宿主适配器（独立仓库，引用 ..\CrossMC 的绑定）
#    当前目录是 <容器>\CrossMC\bindings\csharp，因此上三级到 <容器>
cd ..\..\..\HowToFishMC
dotnet build -c Release
# -> bin\Release\CrossMC.HowToFish.dll
```

### 安装

- MC 模组放到**版本隔离目录**：`C:\PCL\PCL CE\.minecraft\versions\1.21.1-Fabric_0.19.5\mods\`
  （`crossmc-fabric-0.1.0.jar` + `fabric-api`）。
- 宿主插件放到：`...\How to Fish\BepInEx\plugins\`
  （`CrossMC.HowToFish.dll` + `CrossMC.Bindings.dll` + `host.properties`）。

### 重置（每次验证前建议）

```powershell
Remove-Item "$env:LOCALAPPDATA\CrossMC\bridge_v3.bin" -ErrorAction SilentlyContinue
```

- 修改 `CrossMC.HowToFish.dll` 后**必须重启 How to Fish**（BepInEx 启动时加载）。
- 修改 MC 模组后重启 Minecraft。

### 日志位置

- Minecraft：`logs/latest.log`（过滤 `CrossMC`）。
- How to Fish：BepInEx 控制台 / `BepInEx\LogOutput.log`（过滤 `CrossMC`）。

---

## 1. 共享内存握手（无画面、无实体，先证明通信）

**操作**：先启动 Minecraft 进入一个世界，再启动 How to Fish（顺序无所谓，两边都会创建/加入 header）。

**预期**：
- Minecraft 日志出现 `CrossMC bridge ready: ...\bridge_v3.bin`，以及 `created/joined shared memory header (mcPid=...)`。
- How to Fish 日志出现 `CrossMC host ready. config=...` 与 `created/joined shared memory header (hostPid=...)`。
- `%LOCALAPPDATA%\CrossMC\bridge_v3.bin` 大小 = **100,581,376** 字节。

**检查**：
```powershell
(Get-Item "$env:LOCALAPPDATA\CrossMC\bridge_v3.bin").Length   # 期望 100581376
```

**失败排查**：
- 文件没生成 → 配置路径问题：查 `CrossMC.Bridge.Config.ConfigSource()` 的日志；确认 `config/crossmc.properties` 或 jar 内 `/crossmc.properties` 可读。
- 大小不对 → 旧版本文件残留，先删除再重启。
- 两侧路径不同 → 两边必须解析到同一绝对路径（用 `CROSSMC_CONFIG` 指同一文件最稳）。

---

## 2. 画面链路（Minecraft → 共享内存 → 宿主 overlay）

**操作**：进入世界，走动/转头，观察 How to Fish 左上角矩形。

**预期**：矩形里是 Minecraft 世界实时画面，随移动/转头更新。

**检查**：
- 画面**朝向**是否正确（若上下颠倒 → 底向上标志/翻转问题）。
- 是否**含 HUD/手**：当前 hook 在 GUI 之前，预期**不含** HUD 和手。
- 帧率：默认 `-Dcrossmc.exportFps=15`（启动器 JVM 参数）。观察游戏是否卡顿。

**失败排查**：
- 没有矩形 → 宿主没 `Acquire()` 到帧：确认 MC 侧 `FrameExporter` 有发布（日志无 `frame export failed`），且两边版本都是 v3。
- 花屏/尺寸错 → 槽宽高与 stride 不一致；查宿主日志里的 overlay 尺寸。
- 全黑 → `glReadPixels` 格式/读缓冲问题（`GL_BGRA`、只绑 `GL_READ_FRAMEBUFFER`）。

---

## 3. 状态链路（HostState / McState）

**操作**：走动、转视角、切第一/第三人称。

**预期**：宿主日志（或后续 HUD）能反映 MC 玩家位置/朝向在变化；MC 侧能读到宿主玩家 `HostState`（当前 MC 未消费，只写 `McState`；可在日志加一条打印确认）。

**检查**：临时在宿主 `Plugin.Update` 里打印 `_memory.ReadMcState()` 的关键字段，确认数值随 MC 玩家变化。

**失败排查**：seqlock 读抛异常 → 结构偏移不一致（Java/C#/头文件三处必须一致）。

---

## 4. 碰撞代理（宿主 Collider → MC 原生碰撞）

**操作**：把 MC 玩家移动到宿主某面墙/地形前（先在 `HowToFishMC\host.properties` 校准
`transform.origin*`、`transform.scale`、`transform.flipX`，让宿主坐标对到 MC），尝试走进宿主空间。

**预期**：MC 玩家被**挡住**（不可穿过），且周围的真实 MC 方块不受影响。

**检查**：
- 宿主日志有 `ExportColliders` 产生的数量（可临时打印）。
- MC 侧 `HostCollisionManager` 日志（可临时打印 `cellCount/colliderCount`）。
- 可临时按一个键打印玩家前方是否为 phantom 单元。

**失败排查**：
- 完全没碰撞 → mixin 没生效：确认 `fabric.mod.json` 有 `mixins` 且 `crossmc.mixins.json` 被打进 jar；确认是**客户端**世界。
- 坐标不对 → `transform.*` 未校准（宿主空间原点/比例与 MC 落地位置不一致）。
- 撞到不该撞的位置 → AABB 忽略旋转导致偏大；缩小 `collider.radius` 或后续实现 OBB。

**已知限制**：碰撞只在客户端；服务端不认 → 可能出现回弹（服务端权威校正），本轮不处理。

---

## 5. 实体映射（宿主 Creature ↔ MC 代理实体）

**操作**：靠近宿主中的鱼/鸟/蟹/Boss（`collider.radius`/`entity.radius` 覆盖范围），观察 MC 中是否出现**隐藏**代理（无渲染、但占位/可被攻击）。

**预期**：
- MC 日志出现 `spawned proxy entity for host entity <id> at (...)`。
- 代理数量随宿主生物进出范围增减；宿主生物死亡后代理被移除（`discard`）。

**检查**：
- 用 F3 调试或临时命令列出带 `crossmc_proxy` 标签的实体。
- 确认宿主日志导出的实体数与 MC 日志生成数一致。

**失败排查**：
- 没生成 → 是否单机/局域网（**集成服务端**）？远程专用服务器无法生成服务端实体（已知限制）。
- 位置不对 → `transform.*` 未校准。
- `ObjectId` 为 0 → FishNet 尚未初始化该对象。

---

## 6. 伤害链路（MC 原生伤害 → 宿主 + 倍率）

**操作**：对 MC 中的代理实体造成伤害：近战攻击、弓箭、TNT 爆炸、摔落（若可行）。先设
`host.properties` 的 `damage.*` 为明显值（如 `damage.explosion=0.5`）。

**预期**：
- MC 日志出现 `damage on host entity <id> (mc <n>) type=<kind> amount=<raw>`。
- 宿主日志出现 `CrossMC damage: ... raw=<raw> x<mult> => <scaled>`。
- 玩家受击时宿主对 `Player.LocalPlayer.Vitals.TakeDamage` 生效。
- 生物受击时——**本项最不确定**——若反射调用成功，日志出现 `invoked creature damage`；否则 `no creature damage method`/`failed`。

**失败排查**：
- MC 没有伤害日志 → 代理实体没收到原生伤害：确认代理存在、是集成服务端、且伤害确实作用于代理（不是附近方块）。
- 宿主动物伤害失败 → `Creature.LocalHit` 签名不匹配（见下）。

---

## 7. 已知阻塞点 / 需要实机定夺

1. **`Creature.LocalHit(...)` 签名**：未验证。若日志显示反射失败，需要抓一次真实伤害调用参数（Harmony patch 或反编译）来对齐。
2. **服务端权威校正**：客户端碰撞可能被服务端回弹；是否要做服务端侧代理是后续决策。
3. **远程服务器**：代理实体方案仅适用于集成服务端。
4. **Collider 精度**：当前只用 `Physics.OverlapSphereNonAlloc` 的 AABB、忽略旋转；Box/Sphere/Capsule 未区分。
5. **反向约束**（MC 方块 → 宿主）只有 `BlockEditRing` 预留，未实现。
6. **Overlay** 目前是 IMGUI；若需要更贴合 URP 画面，再换 `CommandBuffer` 路径。

---

## 记录模板

| 步骤 | 结果 | 关键日志/现象 | 问题 |
|---|---|---|---|
| 1 握手 |  |  |  |
| 2 画面 |  |  |  |
| 3 状态 |  |  |  |
| 4 碰撞 |  |  |  |
| 5 实体 |  |  |  |
| 6 伤害 |  |  |  |
