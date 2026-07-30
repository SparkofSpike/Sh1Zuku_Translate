# ShizukuTranslate - OCR 功能部署指南

## 概览

在现有翻译链路上增加了一步 OCR 预处理：
```
用户上传/粘贴图片 → Python OCR 微服务 → 提取日文文本 → DeepSeek 翻译
```

## 新增文件清单

### 1. Python OCR 微服务（新建）
```
ocr-worker/
├── ocr_server.py       # Flask 服务，端口 5557
└── requirements.txt    # Python 依赖
```

### 2. Java 后端（新建 3 个文件）
```
src/main/java/com/shizuku/translate/
├── controller/OcrController.java    # 接收图片上传
├── service/OcrService.java          # 调用 Python OCR
└── dto/OcrResponse.java             # 响应 DTO
```

### 3. Java 配置（修改 1 个文件）
```
src/main/resources/application.yml
  └─ 新增 ocr.worker-url: http://localhost:5557
  └─ 新增 spring.servlet.multipart 配置（最大 20MB）
```

### 4. Vue 前端（修改 2 个文件）
```
src/
├── api/index.js          # 新增 ocrImage() / checkOcrHealth()
└── views/TranslateView.vue  # 新增：拖拽上传、剪贴板粘贴、图片预览
```

---

## 部署步骤

### 第一步：安装 Python OCR 环境（仅在服务器上执行）

```bash
# 1. 进入 ocr-worker 目录
cd ocr-worker

# 2. 安装依赖（建议用 venv 虚拟环境）
python -m venv venv
source venv/bin/activate    # Linux
# 或 venv\Scripts\activate   # Windows

# 3. 安装 paddlepaddle + paddleocr
pip install -r requirements.txt

# 4. 验证安装（首次运行会自动下载模型，约 200MB）
python -c "from paddleocr import PaddleOCR; ocr = PaddleOCR(lang='japan'); print('OK')"
```

> ⚠️ 首次运行会下载模型文件，需要网络，后续离线可用。
> ⚠️ 如果服务器在中国大陆外下载慢，可以设置镜像：
> ```bash
> pip install paddlepaddle -i https://mirror.baidu.com/pypi/simple
> ```

### 第二步：启动 Python OCR 服务

```bash
# 启动（建议用 systemd / supervisor 管理，保证开机自启）
cd ocr-worker
source venv/bin/activate
python ocr_server.py

# 默认端口 5557，可通过环境变量修改：
# OCR_PORT=5558 python ocr_server.py
```

验证：
```bash
curl http://localhost:5557/health
# 应返回: {"status":"ok"}
```

### 第三步：重新打包 Java 后端

```bash
cd ShizukuTranslate
mvn clean package -DskipTests

# 启动后端
java -jar target/translator-0.0.1-SNAPSHOT.jar
```

### 第四步：重新打包前端

```bash
cd ShizukuTranslate-frontend
npm run build
# 将 dist/ 目录部署到 Nginx 或您的静态服务器
```

---

## 使用流程

1. 用户打开翻译页面
2. 在页面上方看到「拖拽图片到此处」的上传区
3. **三种上传方式**：
   - **拖拽**：从文件管理器拖拽图片到虚线框
   - **点击**：点击「点击选择文件」按钮
   - **粘贴**：Ctrl+V 粘贴剪贴板中的图片截图
4. 图片预览出现，点击「OCR 识别 (竖排)」按钮
5. 识别出的文字自动填入文本输入框
6. 点击「开始翻译」→ DeepSeek 翻译 → 显示结果

---

## 验证 OCR 是否正常工作

### 检查 OCR 服务状态

```bash
# 从浏览器访问
http://your-server:5566/api/v1/ocr/health

# 或命令行
curl http://localhost:5566/api/v1/ocr/health

# 正常返回: {"success":true,"text":"OCR 服务运行正常","lines":0}
# 异常返回: 503 + error 说明
```

### 测试上传图片

```bash
# 直接测试 Python OCR 服务
curl -X POST http://localhost:5557/ocr \
  -F "image=@test_vertical_jp.jpg"

# 正常返回: {"text":"日本語の縦書きテキスト...","lines":5}
```

---

## 常见问题

### Q: OCR 识别结果乱码/顺序不对？

PaddleOCR `lang='japan'` 模式对竖排日文有原生支持。如果仍有问题：
- 确保图片清晰，不要倾斜超过 15 度
- 试试 manga-ocr（专门为日文漫画/小说设计）作为替代
- 可以调整 `use_angle_cls=True` 参数自动矫正方向

### Q: Python OCR 服务挂了怎么办？

翻译仍然正常可用——只是图片上传功能不可用，用户手动输入文本不受影响。

### Q: 同时启动两个服务太麻烦？

可以写一个 `start.sh` 启动脚本：

```bash
#!/bin/bash
# 启动 Python OCR
cd /path/to/ocr-worker
source venv/bin/activate
python ocr_server.py &

# 启动 Java 后端
cd /path/to/ShizukuTranslate
java -jar target/translator-0.0.1-SNAPSHOT.jar
```

或者后续用 Docker Compose 统一管理。

### Q: 可以用 Docker 统一管理吗？

可以。后续可以把 Python OCR 和 Java 后端都 Docker 化：

```yaml
# docker-compose.yml
services:
  ocr:
    build: ./ocr-worker
    ports:
      - "5557:5557"
  backend:
    build: ./ShizukuTranslate
    ports:
      - "5566:5566"
    environment:
      - OCR_WORKER_URL=http://ocr:5557
    depends_on:
      - ocr
```

---

## 技术总结

| 组件 | 技术选型 | 端口 | 费用 |
|------|---------|:----:|:----:|
| OCR 引擎 | PaddleOCR (lang='japan') | - | 免费，本地运行 |
| OCR 服务 | Flask | 5557 | 免费 |
| Java 后端 | Spring Boot 3.2 | 5566 | - |
| 图片上传 | Multipart (20MB 上限) | - | - |
| 前端上传 | 拖拽 / 点击 / Ctrl+V | - | - |
