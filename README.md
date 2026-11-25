# Agy Launcher

English | [简体中文](./README.zh-CN.md)

Unofficial launcher for Antigravity CLI (`agy`). Launch `agy` inside JetBrains IDE's built-in Terminal and send file, line-range, or diagnostic references directly from context menus. Supports English and Simplified Chinese (`zh_CN`).

## Features

- **One-click / Shortcut Launch**: Open or focus the Antigravity Terminal tab via the toolbar icon, **Tools -> Agy Launcher**, or shortcut (`Alt + Shift + A` on Windows/Linux, `Option + Shift + A` on macOS).
- **Send File & Line References**: Right-click files in Project View, Editor, or Editor Tabs (or press `Alt + Shift + S` on Windows/Linux, `Option + Shift + S` on macOS) to send the file path and selected line range (e.g. `#L10-L25`) to the active Antigravity terminal.
- **Send Diagnostic / Error**: Right-click on a line with errors or warnings in the Editor (or press `Alt + Shift + D` on Windows/Linux, `Option + Shift + D` on macOS) to send the file path, line number, and diagnostic message (e.g. `src/api/user.ts#L42 [TS2322: ...]`).
- **Status Bar Context, Cache & Speed Monitor**: Passively monitors the `Antigravity` terminal buffer to display context usage, cache hit rate, and output speed (`Agy: Ctx --% | Cache --% | Speed -- tok/s`) with a `⚠️` alert at `>= 80%`. Left-click runs `/context` and auto-closes the popup; right-click opens actions to refresh `/context` or compact `/compact`.
- **Customizable Settings**: Configure the `agy` executable path, default CLI arguments, terminal tab title, `@` prefix, relative paths, auto-launch, and status bar monitor options under **Settings -> Tools -> Agy Launcher**.
- **Bilingual UI**: Automatically follows the IDE language (English / Simplified Chinese).

## Shortcuts

| Action | Windows / Linux | macOS |
| --- | --- | --- |
| Launch / Focus Antigravity CLI | `Alt + Shift + A` | `Option + Shift + A` |
| Send File Reference to Antigravity | `Alt + Shift + S` | `Option + Shift + S` |
| Send Diagnostic / Error to Antigravity | `Alt + Shift + D` | `Option + Shift + D` |
