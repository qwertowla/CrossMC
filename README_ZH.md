# CrossMC

[English](README.md) | **中文**

**CrossMC** 是一个跨进程桥接框架：把 **Minecraft（Java 版，Fabric）** 当作后台渲染器 / 工具侧，
接入一个**独立的宿主游戏**。

第一个宿主实现是 **CrossMC-HowToFish**（位于 `hosts/HowToFish/`）：它把 Minecraft 渲染出的画面
合成进 **How to Fish**（Unity 6 / Mono / BepInEx）。

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
