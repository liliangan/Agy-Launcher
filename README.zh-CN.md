# Agy Launcher

[English](./README.md) | 简体中文

Antigravity CLI（`agy`）的非官方 IDE 辅助启动插件。在 JetBrains IDE（如 WebStorm）内置终端中快速启动 `agy`，并支持通过右键菜单直接向终端发送文件路径、选中行号范围与报错诊断信息。插件界面支持英文与简体中文（`zh_CN`）。

## 主要功能

- **一键 / 快捷键启动**：通过主工具栏图标、顶部菜单 **Tools -> Agy Launcher**（或中文界面 **工具 -> Agy Launcher**），或快捷键（Windows/Linux：`Alt + Shift + A`，macOS：`Option + Shift + A`）打开或聚焦 Antigravity 终端标签页。
- **发送文件与行号引用**：在项目视图（Project View）、编辑器（Editor）或编辑器标签页右键菜单中点击发送（或按 Windows/Linux：`Alt + Shift + S`，macOS：`Option + Shift + S`），将文件路径及选中行号范围（如 `#L10-L25`）写入 Antigravity 终端。
- **发送报错 / 诊断信息**：在编辑器报错或警告行右键菜单触发（或按 Windows/Linux：`Alt + Shift + D`，macOS：`Option + Shift + D`），将文件路径、行号及完整报错信息（如 `src/api/user.ts#L42 [TS2322: ...]`）一次性发入终端输入框。
- **状态栏上下文、命中缓存、速率与额度监控**：在底部状态栏以两行堆叠形式展示：第一行实时显示上下文占用率、命中缓存与输出速率（中文：`上下文 --% | 命中缓存 --% | 速率 -- tok/s`，英文：`Ctx --% | Cache --% | Speed -- tok/s`），第二行独立显示当前模型对应的 5h 剩余额度与周剩余额度（`5h额度 --% | 周额度 --%` / `5h Quota --% | Weekly --%`）；上下文达 `80%` 以上或额度低于 `20%` 时显示 `⚠️` 预警，鼠标悬停可查看全部配额池详情及刷新倒计时，左键刷新 `/context` 并自动关闭弹窗，右键可弹出菜单一键执行 `/compact` 压缩。
- **可自定义设置**：可在 **Settings -> Tools -> Agy Launcher** 中配置 `agy` 可执行文件路径、默认启动参数、终端标签页标题、`@` 前缀、相对路径、行号附带、末尾空格、自动拉起及状态栏监控开关。
- **中英文双语界面**：随 IDE 语言环境自动切换英文或简体中文。

## 快捷键

| 功能 | Windows / Linux | macOS |
| --- | --- | --- |
| 启动 / 聚焦 Antigravity CLI | `Alt + Shift + A` | `Option + Shift + A` |
| 发送文件引用到 Antigravity | `Alt + Shift + S` | `Option + Shift + S` |
| 发送报错 / 诊断信息到 Antigravity | `Alt + Shift + D` | `Option + Shift + D` |

所有快捷键均可在 **Settings -> Keymap -> Plugins -> Agy Launcher** 中自定义。
