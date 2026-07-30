# Changelog

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

### 已知限制
- 自动部署因雨云（宿迁）网络限制无法正常工作：
  - 出站：`github.com` / `api.github.com` DNS 被劫持至 `218.93.206.123`
  - 入站：GitHub Actions 所在 IP（美国）无法 SSH 连接服务器
  - 替代方案：使用本地 `ship.py` 进行部署
- 前端 SSR 流式翻译功能已实现但尚未在前端界面中集成触发按钮
