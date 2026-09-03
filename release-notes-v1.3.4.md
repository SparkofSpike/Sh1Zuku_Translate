# Pixiv Novel Translator v1.4.0

## Highlights

- Added inline translation scope options: translate only the current page or the whole novel.
- Added configurable inline splitting for Pixiv content using paragraph boundaries, with `<br>`-aware splitting for line-based novels and poetry.
- Improved paragraph matching and page-flip handling so translations stay aligned when Pixiv re-renders or changes pages.
- Added completeness checks for numbered translation responses and a **Repair missing paragraphs** action when the model omits entries.
- Improved retranslations by bypassing the translation cache, and strengthened handling of partial and final streaming responses.
- Updated the popup and inline toolbar to expose the new controls and repair workflow.

## Installation

Download the release zip and extract it, then run `tranShilator-plugin/CheckUpdate.exe`, or load the extracted `tranShilator-plugin` directory through `edge://extensions` or `chrome://extensions` with Developer mode enabled. After updating an unpacked extension, click **Reload** and refresh the Pixiv tab.

---

# Pixiv Novel Translator v1.4.0（中文）

## 更新内容

- 新增内嵌翻译范围选择：仅翻译当前页，或翻译整篇小说。
- 新增内嵌分隔方式：按段落分隔，或同时识别 `<br>` 换行，适配诗歌、对白等逐行内容。
- 改进段落匹配与翻页处理，Pixiv 重新渲染或切换页面时译文更不容易错位或残留。
- 新增编号翻译完整性检查；模型遗漏段落时，可通过“补译缺失段落”定向修复。
- 重新翻译时绕过翻译缓存，并改进流式响应中间结果与最终结果的处理。
- 更新插件弹窗和内嵌工具栏，提供新的翻译选项和补译入口。

## 安装方式

下载 release zip 并解压，运行 `tranShilator-plugin/CheckUpdate.exe`；也可以在开启开发者模式后，通过 `edge://extensions` 或 `chrome://extensions` 加载解压缩的 `tranShilator-plugin` 目录。更新解压缩扩展后，请点击扩展卡片上的“重新加载”，并刷新 Pixiv 页面。
