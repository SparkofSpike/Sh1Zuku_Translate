## What's new

- **Added inline translation scope selection** — choose whether to translate only the current Pixiv page or the entire novel, while keeping global paragraph IDs aligned across page breaks in full-novel mode.
- **Added line-aware inline splitting** — inline translation can now split content by `<p>` paragraphs or by both `<p>` and `<br>` boundaries, improving support for poetry, dialogue, and line-based layouts.
- **Improved Pixiv paragraph alignment** — source text and rendered DOM content are matched more reliably, including after Pixiv re-renders the novel body or changes pages; stale translations are cleared or refilled instead of being left behind.
- **Added incomplete-response detection and targeted repair** — numbered JSON Lines responses are checked for missing, invalid, or duplicate paragraph IDs, and the inline toolbar now offers **Repair missing paragraphs** without retranslating completed paragraphs.
- **Improved retranslations and streaming reliability** — retranslations bypass the translation cache, final SSE payloads are used as the authoritative result, and partial entries no longer overwrite complete translations.
- Manifest and build metadata updated to 1.4.0.
- The release package contains the complete extension and updater source code.

## Install

Download the zip, extract it, then run `tranShilator-plugin/CheckUpdate.exe`, or load via `edge://extensions` (Developer mode → Load unpacked) and pick the `tranShilator-plugin` folder.

---

## 更新内容

- **新增内嵌翻译范围选择** —— 可选择仅翻译当前 Pixiv 页面，或翻译整篇小说；全文模式下会使用全局段落编号，翻页后仍能正确对应译文。
- **新增按行分隔的内嵌翻译** —— 可按 `<p>` 段落分隔，也可同时按 `<p>` 和 `<br>` 换行分隔，更好地支持诗歌、对白及逐行排版内容。
- **改进 Pixiv 段落对齐** —— 原文与页面 DOM 现在能更可靠地匹配；Pixiv 重新渲染正文或翻页后，旧译文会被正确清理或重新填充，不再残留错位。
- **新增翻译结果完整性检查与定向补译** —— 自动检查编号 JSON Lines 结果中缺失、无效或重复的段落编号；内嵌工具栏新增“补译缺失段落”，无需重新翻译已完成的段落。
- **改进重新翻译与流式响应稳定性** —— 重新翻译会绕过缓存；使用 SSE 最终结果作为权威内容；部分结果不会再覆盖已经完成的译文。
- manifest 与构建元数据更新至 1.4.0。
- Release 压缩包包含完整插件及更新器源码。

## 安装

下载 zip 并解压，运行 `tranShilator-plugin/CheckUpdate.exe` 一键安装 / 更新；或通过 `edge://extensions`（开发人员模式 → 加载解压缩的扩展）选择 `tranShilator-plugin` 目录加载。

---

SHA-256: 295e477dd6ba2785236f541c686a416a153ed15a13c63c15fd93290353cdcce5
