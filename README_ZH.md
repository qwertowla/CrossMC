# CrossMC

[English](README.md) | **中文**

> ⚠️ **本项目尚未完成（开发中）。** 协议布局、bindings 与仓库结构均不稳定，可能随时调整。

**CrossMC** 是一个通用的、与具体游戏无关的**跨进程桥接框架**：把 **Minecraft（Java 版，Fabric）**
与一个**独立的宿主游戏**连接起来。两个进程通过共享内存交换渲染帧、状态与输入：Minecraft 作为渲染 /
工具侧，宿主游戏负责显示与合成。

第一个宿主实现是 **CrossMC-HowToFish**（位于 `hosts/HowToFish/`）：它把 Minecraft 渲染出的画面
合成进 **How to Fish**（Unity 6 / Mono / BepInEx）。

**CrossMC 目前主要面向 Minecraft 1.21.1 + Fabric**，这是现阶段唯一支持的 Minecraft 环境。

> **状态：Phase 0 完成。Phase 1 的 Minecraft 取帧端已实现，宿主消费端是下一步。**
> 尚未做玩家 / 相机 / 输入 / 深度同步。

长期形态：

```text
                ┌─ HowToFish        （第一个宿主适配器，目前唯一）
Minecraft ─ Core ─┤─ <未来的宿主>
                └─ <未来的宿主>
```

**不重写两个游戏。** Minecraft 跑自己的游戏逻辑；宿主游戏跑自己的世界 / 渲染器。桥只负责在两者之间
做「翻译」。

---

## 目录结构

单仓库。框架（协议 + bindings + Minecraft 模组）与游戏无关；每个宿主适配器放在 `hosts/` 下，是唯一
的游戏相关代码。

```text
CrossMC/
├─ protocol/bridge_protocol.h     # 共享内存布局的唯一真相源
├─ bindings/
│  ├─ java/                       # Minecraft 模组使用的薄运行时（已实现）
│  └─ csharp/                     # C# 宿主使用的薄运行时（占位）
├─ minecraft/                     # Fabric 模组（基本与游戏无关）
├─ hosts/
│  └─ HowToFish/                  # 第一个宿主适配器（游戏相关）
├─ config/
│  └─ crossmc.properties          # 配置（共享内存路径等）
├─ docs/
│  ├─ ARCHITECTURE.md
│  ├─ PORTING.md
│  └─ ROADMAP.md
├─ LICENSE
├─ README.md
└─ README_ZH.md
```

新宿主以 `hosts/<游戏>/`（例如 `hosts/EldenRing/`）加入，**不是**分支，**也不是**独立仓库。

`bindings/cpp/` 暂**不创建**——等真正要移植第一个原生（非托管）宿主时再加。

---

## 配置

`config/crossmc.properties` 控制共享内存的位置（`mapping.path`）。两个进程必须解析到同一个绝对路径。
值支持 `%VAR%` 占位符与前导 `~`，例如 `%LOCALAPPDATA%/CrossMC/bridge_v3.bin`。

配置文件的查找顺序（先匹配者优先）：

1. `-Dcrossmc.config=<路径>`（JVM）或 `CROSSMC_CONFIG=<路径>`（环境变量）；
2. `./config/crossmc.properties`（相对于工作目录）；
3. `%LOCALAPPDATA%/CrossMC/crossmc.properties`（用户级覆盖）；
4. 打包内置的 `crossmc.properties` 资源（mod jar 内已含 `config/crossmc.properties`）；
5. 内置默认值。

因此 Minecraft mod 始终能从自己的 jar 拿到可用的默认配置；用户文件或环境变量可覆盖。

---

## 核心 = 协议 + bindings（不是一个进程内共享库）

两个进程、不同语言、不同地址空间。真正共享的是：

- `protocol/` —— 字节布局、magic、version、seqlock、三缓冲、帧格式，以及通用状态结构体；
- `bindings/` —— 每种语言的薄运行时，负责打开映射并实现内存原语。

一切游戏相关内容（玩家访问、相机 hook、渲染 / 呈现 hook、输入 hook）都放在宿主适配器里，不放进核心。

---

## 第一个里程碑（Phase 1）

```text
Minecraft Fabric ── FrameExporter ──▶ 共享内存 ──▶ How to Fish (BepInEx) ──▶ Unity 叠加层
```

目标：Minecraft 的实时画面经文件后备共享内存传输，在 How to Fish 里显示为一个实时矩形。这一条垂直
切片即可验证：协议、共享内存、三缓冲、帧传输、宿主合成。

Phase 0/1 固定：**仅 Windows**、**Minecraft 1.21.1 + Fabric**、**文件后备共享内存**、**CPU 回读**、
**三缓冲**、**How to Fish 作为唯一宿主**。

进度：Java 绑定与 Minecraft 取帧端（`minecraft/FrameExporter`）已实现且可构建；C# 宿主消费端尚未开始。
参见 `minecraft/README.md` 与 `docs/ROADMAP.md`。

---

## 碰撞 / 实体 / 伤害（协议 v3）

在画面链路之外，CrossMC 把两个世界互相映射，同时保持 Minecraft 作为**逻辑/规则侧**：

- **宿主 Collider → Minecraft 碰撞代理。** 宿主发布 Collider 的 AABB；Minecraft 体素化后通过
  `World#getBlockState` 把它们当作不可见实体方块返回，于是 Minecraft **原生**的碰撞、射线与方块放置
  都能感知宿主空间（不重写规则，也绝不遮挡真实方块）。
- **实体。** 宿主生物通过稳定宿主实体 id（`NetworkObject.ObjectId`）映射为隐藏的 Minecraft 代理实体。
- **伤害。** 代理实体上的 Minecraft 原生伤害事件（近战、投射物、爆炸/TNT、摔落、火焰、模组）会被转发
  给宿主，由宿主按自己的规则/倍率处理。伤害倍率只存在于 `hosts/HowToFish`，绝不进 `protocol/`。

状态：各模块均能编译；尚未实机验证。详见 `docs/ARCHITECTURE.md` §12 与 `docs/ROADMAP.md`。

---

## 参考项目

架构**受启发于**（并非照抄）：

- **SkyCraft** —— https://github.com/chasmlol/SkyCraft
- **minecraft-crossover-bridge** —— https://github.com/justbustin/minecraft-crossover-bridge

概念上借鉴了什么、以及许可证 / 署名策略，见 `docs/ARCHITECTURE.md`。

---

## 原型

更早的实验在仓库之外，**作为原型保留**：

- `HowToFishMC` 原型（BepInEx 插件 + UDP）
- `HowToFishMC-Fabric` 原型（Fabric 模组 + HUD）

它们不会被整体迁移进 CrossMC；只把已验证的事实（玩家 / 相机访问、坐标映射）作为参考带过来。

---

## 许可证

MIT —— 见 [LICENSE](LICENSE)。CrossMC 是独立实现；署名策略见 `docs/ARCHITECTURE.md`。
