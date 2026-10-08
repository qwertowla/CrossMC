# CrossMC

[English](README.md) | **中文**

> ⚠️ **开发中。** 协议、bindings 与仓库结构均不稳定，可能随时调整。

**CrossMC** 是一个通用的、与具体游戏无关的**跨进程桥接框架**，连接 **Minecraft（Java 版，Fabric）**
与一个**独立的宿主游戏**。两个进程通过共享内存交换渲染帧、状态与事件。Minecraft 始终是**逻辑/规则侧**
（玩家、方块、实体规则）；宿主游戏是**世界/表现侧**；桥只负责在两者之间**翻译**——不重写任何一个游戏。

第一个宿主适配器是 **HowToFishMC**（独立的同级仓库）：它桥接 **How to Fish**（Unity 6 / Mono /
BepInEx）。以后接入更多游戏时各自新建同级仓库（`EldenRingMC` 等）。

目前主要面向 **Minecraft 1.21.1 + Fabric**，这是现阶段唯一支持的 Minecraft 环境。

```text
                    ┌─ HowToFishMC   （第一个宿主适配器，独立仓库）
Minecraft ──────────┼─ <未来的宿主>   每个宿主是依赖 CrossMC 的独立仓库
  (CrossMC)         └─ <未来的宿主>
```

---

## 目录结构

CrossMC 是**框架**仓库。每个宿主适配器都是**独立的同级仓库**，也是唯一存放游戏相关代码的地方。

```text
CrossMC/
├─ protocol/bridge_protocol.h     # 共享内存布局的唯一真相源（v5）
├─ bindings/
│  ├─ java/                       # Minecraft 模组使用的薄运行时
│  └─ csharp/                     # C# 宿主使用的薄运行时
├─ minecraft/                     # Fabric 客户端模组（与游戏无关）
├─ config/
│  └─ crossmc.properties          # 配置（共享内存路径等）
├─ tools/TestHost/                # 极简 dotnet 测试 Host（无 Unity）
├─ docs/
│  ├─ PROTOCOL.md                 # 语义：坐标、身份、State/Event、能力
│  ├─ ARCHITECTURE.md
│  ├─ PORTING.md
│  ├─ ROADMAP.md
│  └─ VERIFICATION.md
├─ LICENSE
├─ README.md
└─ README_ZH.md
```

`bindings/cpp/` 暂**不创建**——等真正要移植第一个原生（非托管）宿主时再加。

---

## 框架提供什么

- **协议**（`protocol/bridge_protocol.h`，v5）——C-compatible 字节布局：能力位、心跳、
  `HostState`/`McState` seqlock、overlay **三缓冲**、`ColliderTable`（id + revision + 生命周期）、
  带稳定 **`CrossEntityId`** 的 `EntityTable`、原生伤害 `DamageRing`、`InputRing`，以及预留的
  `DepthFrame`/`BlockEditRing`。详见 `docs/PROTOCOL.md`。
- **Bindings**——`bindings/java`（Minecraft 模组用）与 `bindings/csharp`（C# 宿主用）：文件后备映射、
  原子操作、seqlock、三缓冲、表与环的访问、能力/存活辅助。两端都镜像头文件；改动时三处一起升 `VERSION`。
- **Minecraft 模组**——取帧（`FrameExporter`）、发布 `McState`、宿主碰撞代理（体素化，经
  `World#getBlockState` 注入）、宿主实体的隐藏代理实体、原生伤害捕获——全部走共享内存协议，不含宿主专属代码。
- **测试 Host**（`tools/TestHost`）——极简 .NET 控制台 Host，无需 Minecraft/Unity 即可验证共享内存、
  能力、序号、实体生命周期、State/Event 与断开。

---

## 配置

`config/crossmc.properties` 控制共享内存的位置（`mapping.path`）。两个进程必须解析到同一个绝对路径。
值支持 `%VAR%` 占位符与前导 `~`，例如 `%LOCALAPPDATA%/CrossMC/bridge_v5.bin`。

配置文件的查找顺序（先匹配者优先）：

1. `-Dcrossmc.config=<路径>`（JVM）或 `CROSSMC_CONFIG=<路径>`（环境变量）；
2. `./config/crossmc.properties`（相对于工作目录）；
3. `%LOCALAPPDATA%/CrossMC/crossmc.properties`（用户级覆盖）；
4. 打包内置的 `crossmc.properties` 资源（mod jar 内已含 `config/crossmc.properties`）；
5. 内置默认值。

因此 Minecraft mod 始终能从自己的 jar 拿到可用的默认配置；用户文件或环境变量可覆盖。

---

## 能力与状态

| 能力 | 协议 | Minecraft 侧 | 宿主适配器 |
|---|---|---|---|
| Frame | ✅ | ✅ 生产者 | ✅（HowToFishMC overlay） |
| State（`HostState`/`McState`） | ✅ | ✅ 发布 `McState` | ✅ 发布 `HostState` |
| Collision（`ColliderTable`） | ✅ | ✅ 经 `World#getBlockState` 代理 | ✅ 导出 Collider |
| Entity（`CrossEntityId`） | ✅ | ✅ 代理实体 + 伤害 | ✅ 分配 id |
| Damage（`DamageRing`） | ✅ | ✅ 捕获原生伤害 | ✅ 施加倍率 |
| Input（`InputRing`） | ✅ 通用 | ✅ 可注入 `KeyBinding`/`Mouse`¹ | ⛔ 默认关闭 |
| Depth / BlockEdit | ✅ 预留 | ⛔ | ⛔ |

¹ `InputRing` 是 CrossMC 的**通用**能力，不是玩家控制路径。若宿主使用它，`HostInputConsumer` 会注入
Minecraft **自身**的输入；release / `RELEASE_ALL` / 心跳超时会清空按住的输入。玩家正常游玩使用
Minecraft 自己的输入。

**玩家权威：** Minecraft 是主游戏，Minecraft 玩家是唯一权威玩家。宿主**玩家与相机镜像 `McState`**
（固定坐标映射，Health/Hunger 同理）；宿主 Transform **绝不**回写 Minecraft 玩家。`HostState` 只是宿主
环境/表现信息。

各模块均能编译、Java 绑定自检通过；**尚未实机验证**。详见 `docs/ROADMAP.md`、`docs/VERIFICATION.md`、
`docs/PROTOCOL.md`。

---

## 参考项目

架构**受启发于**（并非照抄）：

- **SkyCraft** —— https://github.com/chasmlol/SkyCraft
- **minecraft-crossover-bridge** —— https://github.com/justbustin/minecraft-crossover-bridge

概念上借鉴了什么、以及许可证 / 署名策略，见 `docs/ARCHITECTURE.md`。

---

## 原型

更早的实验在仓库之外，**作为原型保留**：

- 早期的 BepInEx + UDP 原型
- Fabric + HUD 原型

它们不会被整体迁移进 CrossMC；只把已验证的事实（玩家 / 相机访问、坐标映射）作为参考带过来。

---

## 许可证

MIT —— 见 [LICENSE](LICENSE)。CrossMC 是独立实现；署名策略见 `docs/ARCHITECTURE.md`。
