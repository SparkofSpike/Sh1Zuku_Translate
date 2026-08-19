# Memory draft

## 公告 Markdown 支持（2026-08-20）

- 前端没有现成的 Markdown 依赖；公告 Markdown 使用 `ShizukuTranslate-frontend/src/utils/markdown.ts` 的轻量渲染器，避免引入额外包。
- 渲染器支持标题、加粗、斜体、删除线、列表、引用、行内代码、代码块、分隔线和安全外链。
- 公告内容通过 `v-html` 展示前，必须经过 `renderMarkdown`：原始 HTML 会转义，链接只允许 `http`、`https`、`mailto`，并加上 `target="_blank"` 与 `rel="noopener noreferrer"`。
- `AnnouncementPanel.vue` 的移动端公告仍默认限制约三行，并保留展开/收起；桌面端不折叠。
- `AdminView.vue` 的公告发布输入框提示 Markdown 语法，已发布公告也使用相同渲染器预览。
- 公告 API 和数据库继续保存原始 Markdown 文本，不需要数据迁移；旧公告会自动获得 Markdown 展示能力。
- 验证命令：在 `ShizukuTranslate-frontend` 执行 `npm run build` 通过。

## README audit and English rewrite (2026-08-20)

- `README.md` was rewritten in English and audited against the current controllers, model client, profile APIs, extension scripts, OCR worker, `application.yml`, `pom.xml`, and `ship.py`.
- The documentation now covers multiple DeepSeek/OpenAI-compatible/Anthropic-compatible model profiles, token usage logs and historical backfill, Markdown announcements, the v1.2.0 extension, and the actual local deployment workflow.
- The OCR source currently imports PaddlePaddle/PaddleOCR, while `ocr-worker/requirements.txt` still contains the older Flask/EasyOCR list; the README documents the explicit PaddleOCR installation command and calls out this mismatch.
- The README update is intended to be committed together with the pending announcement Markdown implementation so the remote documentation does not describe code that was left unpushed.
