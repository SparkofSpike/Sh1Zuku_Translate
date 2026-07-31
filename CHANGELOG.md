# Changelog

## [2026-07-31] 修复翻页小说翻译"卡住"（长文本 prefill 无反馈）

### 根因（端到端实测确认）
1. **翻页小说 = 超长文本**：Pixiv AJAX 返回全文（例：9 页小说 27623 字符），DeepSeek prefill 需数分钟才输出首 token（服务器日志：请求 created 10:52:46，首 chunk 10:56:14 才处理）
2. **SseEmitter 响应头不立即 flush**：浏览器 fetch 一直 pending，用户看不到任何反馈 → 以为卡住 → 取消 → 后端 `Client disconnected`
3. **DeepSeekClient 误报日志**：`onToken.accept()` 在 parse 的 try-catch 内，`emitter.send` 抛的断开异常被误记为 "Failed to parse SSE chunk"，且客户端断开后仍继续消费 DeepSeek 流浪费 API
4. **翻页小说 DOM 只渲染当前页**：inline 模式 `findNovelParagraphs` 只找到当前页段落（例：第 1 页仅 1 个 `<p>`），译文 9 页内容 merge 到 1 个 div

### 修复（提交 1add5d4）
- **TranslateController**：创建 SseEmitter 后立即 `send(comment("connected"))`，强制 flush 响应头 → 浏览器立刻收到连接确认，长 prefill 期间不再像卡死
- **DeepSeekClient**：识别 `Client disconnected / has already completed / ClientAbortException` 异常 → 停止消费 DeepSeek 流 + 跳过 completion send（省 API、日志不再误报）
- **content.js**：首 token 8 秒未到 → toast「AI 正在处理长文，可能需要几分钟，请稍候…」

### 验证
- 服务器本机 `test_api.bat`：SSE 流式 + HTTP 200 + done 正常
- 本地 15066 长文本（27623 字符）：响应头 5.7s、首 token 8.2s、流式输出正常
- `mvn compile` BUILD SUCCESS、`node --check` 通过
- 确认：**后端和网络链路本身健康**，"卡住"纯属长 prefill 期间前端无反馈

### 遗留
- 翻页小说 inline 模式：DOM 只渲染当前页，译文全文 merge 到当前页段落下方；可后续做"按页翻译"或提示用户用 paged 模式

## [2026-07-31] 修复 inline 模式容器定位（核心 bug）

### 问题根因（已实证，非猜测）
用 Edge headless 实际渲染 Pixiv 新版小说页（`show.php?id=28665545`）抓取 DOM 分析：
1. **旧版选择器全失效**：新版页面是 Next.js + styled-components，class 随机 `sc-xxx`（如 `sc-eldPxv`），`PIXIV_CONTAINER_SELECTORS` 里的 `.novel_view`/`#novel-body` 等全部匹配不到
2. **文本锚点 fallback 失效**：正文 AJAX 返回的是纯文本，但页面渲染时把每行拆成 `<span class="text-count" data-textcount="N">` + `<br>`，旧代码取跨段落连续 40 字符做 `startsWith` 永远失败
3. **4 秒轮询太短**：正文容器是客户端渲染的（SSR HTML 里正文只存在于 `<meta name="description">`），`document_end` 时 DOM 还没有正文，10×400ms 轮询经常超时 → fallback 弹侧边栏（用户反馈"点原文嵌入又弹侧边栏"）

### 修复方案（content.js）
- **新增稳定锚点定位**（不依赖随机 class）：
  - `#gtm-novel-work-scroll-begin-reading` — Pixiv GTM 埋点 id，稳定，是自闭合标记 div，正文 `<p>` 紧跟其后
  - `span.text-count` — Pixiv 业务 class（每行文本包装），稳定
  - `collectParagraphRun()` 收集连续 `<p>` 兄弟（遇到广告/footer 等块级元素停止）
- **文本锚点改进**：取原文第一段（而非跨段 40 字符），`normalizeText()` 归一化空白，`includes` 匹配
- **MutationObserver 替代轮询**：`waitForInlineContainer()` 监听 body 子树变化，防抖 250ms 重试，20 秒超时才 fallback（不再 4 秒就弹侧边栏）
- `buildInlineParagraphs()` 幂等化（防止 observer 重试时重复插入译文 div）
- 提取 `fillWindowFromNovel()` 公共函数

### 验证（非模拟，真实 DOM 实测）
- 两本不同结构的小说实测定位成功：
  - 28665545（多段小说）：找到 20 个正文段落，首段「やばっ、寝ちゃってた」
  - 28413730（诗歌，单段 33 行）：找到 1 个整段，全部正文
- E2E 测试：20 段落 → 插入 20 个译文 div，段落-译文映射正确
- `node --check` 语法通过

### 遗留（不在本次范围）
- 诗歌/单段多行：译文是整段一个 div（非逐行对照），可后续优化
- PHPSESSID 依赖：登录限定内容（R18/私人）仍可能拿不到全文

## [2026-07-31] Pixiv 小说翻译插件开发（v1.1.0+9170f8e）

### 插件功能（pixiv-novel-translator/）
- **流式翻译**：`background.js` 调用后端 `/api/v1/translate/stream` SSE，打字机效果输出，`AbortController` 支持取消
- **三态按钮**（照搬网站）：黑 `#1a1a1a`(idle) → 红 `#e03131`(preparing) → 蓝 `#1971c2`(ai-processing)，任意阶段点击取消
- **悬浮窗 UI**：可拖动窗口（展开态）+ 右下角胶囊按钮（缩小态）；标题/作者/版本号显示
- **三种显示模式**（popup 选择）：
  - `panel` 侧边面板（默认）
  - `inline` 原文内嵌对照（彩云小译式：原文段落下方插译文 div）
  - `paged` 分页模式（保留 Pixiv 的 `[newpage]` 分页符）
- **预设多选 + 自定义 Prompt**：popup 从后端 `GET /api/v1/presets` 拉取，与网页一致
- **API Key 鉴权**：后端新增 `X-API-Key` header 鉴权（`ApiKeyAuthenticationFilter`），Key 格式 `sk-st`+UUID（无连字符），永久有效
- **版本号**：`build_extension.py` 从 git commit 生成 `version.js`，悬浮窗头部 + popup 显示

### 后端
- 新增 ApiKey 实体/Repository/Service/Filter，AuthController 增加 Key 管理端点
- CORS 放行 `chrome-extension://*`
- `ApiKey.user` 改 `EAGER` 加载（修 Filter 中 LazyInitializationException）
- `expiresAt` null-safe（`Map.of` 不接受 null，改 `LinkedHashMap`）

### 已修复的关键 bug（历史）
- `startStreamingTranslation` 签名缺少 `selectedPresets/customPrompt` → ReferenceError
- 取消翻译后按钮卡死 → `cancelTranslation` 恢复 idle + 忽略迟到 token
- Extension context invalidated → 按钮幂等清理 + init 检测 + 友好提示
- autoTranslate 与手动点击冲突 → MANUAL_TRANSLATE 先 cancel 再 start
- 内联段落错位 → 改为在 Pixiv 自己的 `<p>` 后插译文 div，不重建 DOM
- Pixiv fetch Failed to fetch → **剥离 forbidden headers**（Cookie/UA/Referer，Edge 拒绝）

### ⚠️ 当前已知问题（未解决）
1. **inline 模式容器定位不可靠**：Pixiv 新版页面（Next.js + styled-components，class 随机 `sc-xxx`）导致 `findNovelContainer` 选择器全部匹配失败 → 4 秒轮询超时后**自动 fallback 到侧边栏**（用户反馈"点原文嵌入又弹侧边栏"）。文本锚点 fallback 也常失效（页面正文 AJAX 渲染、文本被拆分）。**这是下一个接手者要攻克的核心问题。**
2. **PHPSESSID Cookie 依赖**：为修 Failed to fetch 已剥离 Cookie header，登录限定内容（R18/私人小说）可能拿不到全文；若需要，改用 `credentials:'include'` 或 declarativeNetRequest 方案
3. 无单元测试；插件无自动化测试

### 部署
- `git push`（socks5 代理 127.0.0.1:10808）→ `python ship.py`
- 服务器：`ad.rainplay.cn:15066`（网页） / `:22591→52291`（SSH）
- 插件：Edge 手动 `edge://extensions` 加载 `pixiv-novel-translator/`

### 已知限制
- 自动部署因雨云（宿迁）网络限制无法正常工作：
  - 出站：`github.com` / `api.github.com` DNS 被劫持至 `218.93.206.123`
  - 入站：GitHub Actions 所在 IP（美国）无法 SSH 连接服务器
  - 替代方案：使用本地 `ship.py` 进行部署

## [2026-07-30] 大规模重构 + GitHub 接入 + CI/CD

### 基础设施
- 初始化 Git 仓库并推送到 GitHub (`SparkofSpike/Sh1Zuku_Translate`)
- 配置 GitHub Actions 自动构建（`mvn package` + `npm build`），每次 push 自动验证
- 新增 `ship.py` — 本地一键部署脚本（编译 → SCP → 重启）
- 配置 GitHub Secrets：`SERVER_HOST`、`SERVER_PORT`、`SERVER_USER`、`SERVER_SSH_KEY`、`SERVER_PATH`
- 新增 `README.md`（英文）、`CHANGELOG.md`

### 后端重构 (Spring Boot)
- **Lombok**：所有 Entity (`TranslationRecord`, `User`, `SurveyRecord`) 改用 `@Data/@Builder`，删除手写 getter/setter
- **PromptTemplateService**：将硬编码的翻译 prompt 抽取为独立服务，消除 `translate()` 和 `translateStream()` 之间的提示词不一致
- **DeepSeekClient**：统一使用单一 HTTP 客户端，移除 `catch(Exception ignored)` 吞错误
- **异常体系**：新增 `BusinessException`、`ResourceNotFoundException`(404)、`UnauthorizedException`(401)、`OcrServiceException`(502)
- **GlobalExceptionHandler**：覆盖所有异常类型，不再简单返回 500
- **SSE JSON 序列化**：不再手工拼 JSON string，改用 Jackson `ObjectMapper` + DTO（`SseTokenEvent`、`SseDoneEvent`、`SseErrorEvent`）
- **SecurityConfig**：收紧 `/api/v1/stats/**` 权限，仅 admin 可访问
- **DTO 验证**：补充 `@NotBlank`、`@Size`、`@Min/@Max` 注解

### 前端重构 (Vue 3 → TypeScript)
- **TypeScript 迁移**：`main.js` → `main.ts`，全项目 TS 严格模式，`tsconfig.json` 配置
- **组件拆分**：355 行 `TranslateView.vue` 拆为 5 个独立组件：
  - `ImageUploader.vue` — 拖拽/粘贴/点击上传
  - `OcrPreview.vue` — 图片预览 + 置信度滑块
  - `PresetSelector.vue` — 多选预设
  - `TranslateResult.vue` — 结果展示 + 复制
  - `SseTranslateResult.vue` — SSE 流式输出
- **SSE 流式翻译**：`api/index.ts` 新增 `translateStream()` 函数，实现打字机效果
- **CSS 规整**：自定义属性 + utility class 提取到 `style.css`

### OCR 重构 (Python)
- **模块拆分**：`ocr_server.py`（Flask 入口） + `ocr_service.py`（OcrService 类） + `config.py`（配置管理）
- **类型注解**：全方法签名增加 type hints
- **配置外化**：端口/阈值通过环境变量 `OCR_PORT`、`OCR_THRESHOLD` 控制

### DevOps
- 修复 `deploy.bat`、`debug.bat`、`start-dev.bat` 中错误路径 `G:\Sh1Zuku_Translate` → `G:\Github\Sh1Zuku_Translate`
- `.github/workflows/deploy.yml`：从 `easingthemes/ssh-deploy` 改为原生 SCP + `appleboy/ssh-action`
- `.github/workflows/deploy.ps1`：Windows 服务器手动重启脚本
- CI 构建验证通过：后端 `mvn compile` ✅，前端 `npm run build` ✅

### 后续修复 (2026-07-30)
- **SSE 流式修复**：`api/index.ts` 中 `translateStream()` 的 `fetch` 请求缺少 JWT Token 头，导致后端返回 401 卡死。同时 Spring Boot 的 `SseEmitter` 发送 `data:{...}`（冒号后无空格），前端 `startsWith('data: ')` 无法匹配，所有 SSE 事件被静默忽略。修复 Token 传递 + 改用 `startsWith('data:')` 兼容两种格式
- **doneReceived 标记**：后端关闭 SSE 连接后 `fetch` 误抛网络错误覆盖翻译结果，增加 `doneReceived` 标记，done 后忽略所有后续错误
- **三态按钮**：翻译按钮改为三态：
  - `idle` → 「开始翻译」（默认）
  - `preparing` → 「网页处理中... 点击取消」（红色，`#e03131`）
  - `ai-processing` → 「AI 处理中... 点击取消」（蓝色，`#1971c2`）
  - 任意阶段点击按钮取消翻译（中断 HTTP 请求或 SSE 流）
- **服务器持久化**：解决 Windows SSH 断开后 `start /B` 进程被杀问题，改用 `schtasks /run` 直接运行 Java，确保服务持续运行

### 已知限制
- 自动部署因雨云（宿迁）网络限制无法正常工作：
  - 出站：`github.com` / `api.github.com` DNS 被劫持至 `218.93.206.123`
  - 入站：GitHub Actions 所在 IP（美国）无法 SSH 连接服务器
  - 替代方案：使用本地 `ship.py` 进行部署
