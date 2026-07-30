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
| 服务器 | Windows （ad.rainplay.cn:22591 → :15066 端口转发） |
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
  - fix: xxx — bug 修复
  - feat: xxx — 新功能
  - refactor: xxx — 重构
  - docs: xxx — 文档
  - chore: xxx — 杂项

推送需要走代理：
```powershell
git -c http.proxy=socks5://127.0.0.1:10808 push
```
