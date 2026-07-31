# Agents Workflow Reference

> 本项目（ShizukuTranslate）的 AI Agent 工作流程和约定。
> 每次接手此项目时，先快速过一遍本文件。

---

## 1. 项目摘要

AI 小说翻译工具：DeepSeek + PaddleOCR + Spring Boot + Vue 3 + CI/CD

| 层级 | 技术 |
|------|------|
| 前端 | Vue 3 + TypeScript + Vite + Pinia + Vue Router |
| 后端 | Java 21 + Spring Boot 3.2 + Maven + H2 + JWT |
| OCR | Python 3.12 + Flask + PaddleOCR（日语模型） |
| 服务器 | Windows （ad.rainplay.cn:15066 网页, :22591→52291 SSH） |
| 代码 | GitHub SparkofSpike/Sh1Zuku_Translate（私有仓库） |
| 部署 | ship.py 本地一键部署（因雨云网络限制无法走 CI/CD） |

---

## 2. 本地开发流程

```powershell
# 1. 拉取最新
git pull

# 2. 构建前端
cd ShizukuTranslate-frontend
npm ci && npm run build

# 3. 复制前端到后端静态目录
Remove-Item ..\ShizukuTranslate\src\main\resources\static -Recurse -Force
Copy-Item dist\* ..\ShizukuTranslate\src\main\resources\static\ -Recurse

# 4. 构建后端
cd ..\ShizukuTranslate
mvn clean package -DskipTests

# 5. 部署
cd ..
scp -P 22591 -i ~/.ssh/id_rsa ShizukuTranslate\target\translator-*.jar Administrator@ad.rainplay.cn:D:\Sh1ZukuTranslate\translator.jar
```

或一键部署：
```powershell
python ship.py
```

---

## 3. 服务器部署方案

### 3.1 服务管理

服务器是 Windows，SSH 断开后 start /B 启动的进程会被杀掉。
唯一可靠的方式：schtasks 直接跑 Java（不经过 start /B）。

#### 重启服务的步骤：

```powershell
# 0. 传文件
scp -P 22591 -i ~/.ssh/id_rsa translator.jar Administrator@ad.rainplay.cn:D:/Sh1ZukuTranslate/translator.jar

# 1. 停旧服务
ssh -P 22591 -i ~/.ssh/id_rsa Administrator@ad.rainplay.cn "taskkill /f /im java.exe 2>nul"

# 2. 重启（schtasks 方式）
ssh -P 22591 -i ~/.ssh/id_rsa Administrator@ad.rainplay.cn `
  "schtasks /delete /tn SvcShizuku /f 2>nul & `
   schtasks /create /tn SvcShizuku /tr D:\Sh1ZukuTranslate\_task_backend.bat /sc once /st 00:00 /f 2>nul & `
   schtasks /run /tn SvcShizuku 2>nul"
```

### 3.2 关键文件

| 文件 | 用途 |
|------|------|
| D:\Sh1ZukuTranslate\translator.jar | Spring Boot 后端（含前端页面） |
| D:\Sh1ZukuTranslate\ocr-worker\* | Python OCR 服务 |
| D:\Sh1ZukuTranslate\logs\backend.log | 后端日志 |
| D:\Sh1ZukuTranslate\logs\ocr.log | OCR 日志 |
| D:\Sh1ZukuTranslate\data\translatordb.mv.db | H2 数据库（15MB） |
| D:\Sh1ZukuTranslate\_task_backend.bat | schtasks 用的启动脚本（不要用 start /B） |
| D:\Sh1ZukuTranslate\_start_svc.bat | 旧版启动脚本（会因 SSH 断开被杀） |
| D:\Sh1ZukuTranslate\deploy.ps1 | PowerShell 手动部署脚本 |

### 3.3 SSH 注意

- SSH 连接默认走 cmd.exe（不是 PowerShell）
- powershell -Command xxx 中如果命令包含 | 管道，需要转义
- 建议简短的 cmd 命令直接用，复杂逻辑写在批处理文件里上传再执行

---

## 4. 已知限制

### 4.1 网络限制（雨云 宿迁）

- 出站：github.com / api.github.com DNS 被劫持 → 服务器无法拉取 GitHub
- 入站：海外 IP 连不上（GitHub Actions 美国节点超时）
- 后果：CI/CD 自动部署不可用，只能用本地 ship.py
- 建议：换区域（上海/北京）或换服务商可解

### 4.2 技术债务

| 事项 | 状态 | 说明 |
|------|------|------|
| 流式输出 | 后端已实现 | /translate/stream SSE + SseTranslateResult 组件 |
| 流式输出前端集成 | 可用 | 默认勾选流式输出复选框 |
| 预设管理 | 硬编码中 | 预设 prompt 在 application.yml，建议迁到数据库 |
| 生产数据库 | H2 | 开发用 H2 可接受，但生产建议 MySQL/PostgreSQL |
| 单元测试 | 无 | 需要补 TranslationServiceTest、UserServiceTest |
| 管理员预设 CRUD | 无 | 预设只能改配置文件，不够方便 |

---

## 5. 部署脚本修复（ship.py）

ship.py 在本地 PowerShell 环境中运行 subprocess.run 时可能找不到 npm / mvn，
是因为 PowerShell 的 PATH 没有传递给 Python 子进程。
解决方法：直接用 Python 原生 os.environ[PATH] 合并系统 PATH。

如果 python ship.py 报错，手动走 2-3-4-5 步。

---

## 6. Git 工作流

- 主分支：main
- 重构/bugfix 分支：codex/agent-*（子 agent 自动创建，完成后删掉）
- 提交规范：
  - fix: xxx — bug fix
  - feat: xxx — new feature
  - refactor: xxx — refactoring
  - docs: xxx — documentation
  - chore: xxx — misc
  - **⚠️ commit message 一律用英文（用户硬性要求，无论消息正文写什么都必须英文）**

推送需要走代理：
```powershell
git -c http.proxy=socks5://127.0.0.1:10808 push
```

---

## 7. 提交后发布流程（标准操作）

每次完成 feature/fix 并本地 commit 后，按此流程发布到生产：

### Step 1：推送到 GitHub

```powershell
git -c http.proxy=socks5://127.0.0.1:10808 push
```

> 本地用 socks5 代理连 GitHub，服务器不直接拉代码（因雨云 DNS 劫持）。

### Step 2：本地构建并部署到服务器

```powershell
python ship.py
```

`ship.py` 会自动执行：前端构建 → 后端打包 → SCP 传到服务器 → SSH 重启服务。

### Step 3：验证

```powershell
# 检查后端是否存活
curl https://ad.rainplay.cn:22591/api/v1/presets

# 检查登录页可访问
curl https://ad.rainplay.cn:22591/
```

> **以后每个 commit 的发布流程都是：`git push → python ship.py`**

---

## 8. 浏览器插件项目

### 8.1 项目位置

`pixiv-novel-translator/` — Chrome MV3 浏览器扩展，一键翻译 Pixiv 日文小说。

### 8.2 技术栈

| 层 | 技术 |
|----|------|
| 框架 | Manifest V3 (Service Worker) |
| 触发 | Content Script 注入 Pixiv 小说页 |
| 授权 | `chrome.cookies.get` 读取 PHPSESSID |
| 后端鉴权 | `X-API-Key` header（不同于 JWT 登录） |

### 8.3 数据流

```
用户点击翻译 → content.js 提取 novel_id → background.js:
  1. fetch(https://www.pixiv.net/ajax/novel/{id}) → 原文 (bare fetch, 不设 forbidden headers)
  2. fetch({后端}/api/v1/translate/stream, X-API-Key) → SSE 流式译文
→ content.js 渲染（三种模式：panel 侧边栏 / inline 原文内嵌 / paged 分页）
```

> **重要经验（踩坑记录）**：
> - 扩展 fetch pixiv **不能手动设置 Cookie/User-Agent/Referer**（forbidden headers），Edge 会直接抛 `Failed to fetch`。bare fetch 即可（pixiv AJAX API 无需登录可访问公开小说）。
> - 修改插件文件后，用户必须在 `edge://extensions` 点「重新加载」，且**刷新 Pixiv 页面**才能让新 content.js 生效（否则报 `Extension context invalidated`）。
> - 用户浏览器是 **Edge**（不是 Chrome），v2rayN 开了 TUN 模式（`xray_tun` 网卡），系统代理 `127.0.0.1:10808`。
> - 用户后端地址：`http://ad.rainplay.cn:15066`（不是 22591，那是 SSH 端口）。

> **新版 Pixiv 小说页 DOM 结构（2026-07-31 实证，Edge headless 渲染后抓取）**：
> - 页面是 Next.js + styled-components，正文容器/段落 class 全部随机（`sc-xxx`），**不能依赖任何 class 定位**
> - **稳定锚点**：`<div id="gtm-novel-work-scroll-begin-reading" data-novel-id="...">`（GTM 埋点，自闭合标记 div，正文 `<p>` 紧跟其后）+ 正文每行包装 `<span class="text-count" data-textcount="N">`
> - 正文**客户端渲染**：SSR HTML 里正文只存在于 `<meta name="description">`，`document_end` 时 DOM 可能还没有正文 → 必须 MutationObserver 等待
> - 正文段落：多段小说 = 连续 `<p>` 兄弟；诗歌 = 单 `<p>` 内多 `<br>` 行
> - 定位逻辑已实现于 `content.js` 的 `findNovelParagraphs()` + `collectParagraphRun()` + `waitForInlineContainer()`（20s 超时）

### 8.4 显示模式（popup 设置）

| 模式 | 行为 |
|------|------|
| `panel`（默认） | 右侧悬浮窗输出，可拖动，缩小后变右下角胶囊按钮 |
| `inline` | **原文内嵌对照**（彩云小译式），译文插到 Pixiv 原文段落下方 |
| `paged` | 保留 Pixiv 的 `[newpage]` 分页符，分页块显示 |

### 8.5 API Key 管理

API Key 由 Web 后端的登录用户生成，通过以下端点管理：

| 方法 | 端点 | 说明 |
|------|------|------|
| POST | `/api/v1/auth/api-key` | 生成新 Key（需 Bearer JWT） |
| GET | `/api/v1/auth/api-keys` | 列出所有 Key |
| DELETE | `/api/v1/auth/api-key/{id}` | 删除指定 Key |

插件配置时将 Key 填入弹窗设置即可。
