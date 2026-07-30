# ShizukuTranslate - Server Deployment Guide (2026-07-05)

## Server Info
- Host: ad.rainplay.cn
- SSH Port: 22591
- OS: Windows Server
- User: Administrator

## Architecture
```
Browser (Vue SPA)
    ↓ port 5566
Java Spring Boot (API + Frontend)
    ↓ port 5557
Python OCR Worker (PaddleOCR 3.7.0 + PP-OCRv6)
    ↓
DeepSeek API (optional polish)
```

## Prerequisites on Server

### 1. Install Python 3.12
Python 3.12 is REQUIRED. PaddlePaddle 3.x + PaddleOCR 3.7.0 does NOT work on Python 3.14.

```powershell
# Download Python 3.12.9
curl -o C:\Python312-installer.exe https://www.python.org/ftp/python/3.12.9/python-3.12.9-amd64.exe

# Install silently
Start-Process C:\Python312-installer.exe -ArgumentList "/quiet InstallAllUsers=0 PrependPath=1 Include_test=0" -Wait

# Verify
C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe --version
# Expected: Python 3.12.9
```

### 2. Install Python Dependencies
```powershell
# Install paddlepaddle + paddleocr (takes ~5 min, large download)
C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe -m pip install paddlepaddle==3.3.1 paddleocr==3.7.0

# Install flask for the OCR microservice
C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe -m pip install flask
```

### 3. Deploy Files
Upload from local `deploy_package/` to server:

```
server:/opt/shizuku/
├── translator.jar          ← Java backend
├── ocr-worker/
│   ├── ocr_server.py       ← Python OCR microservice
│   └── requirements.txt
├── start.sh                ← Server startup script
└── logs/                   ← Log directory (auto-created)
```

## Startup (on Server)

### Step 1: Start Python OCR Service
```powershell
cd C:\path\to\deploy
C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe ocr-worker\ocr_server.py
```
First run downloads PP-OCRv6 models (~100MB). Wait for "OCR service starting on port 5557".

Verify: `curl http://localhost:5557/health` → `{"status":"ok"}`

### Step 2: Start Java Backend
```powershell
java -jar translator.jar
```
OR use the provided script:
```bash
./start.sh
```

Open `http://server-ip:5566` in browser.

## First-Time Model Download
On first run, PaddleOCR downloads several models:
- PP-LCNet_x1_0_doc_ori (~10MB)
- UVDoc (~32MB)
- PP-OCRv6_medium_det (~20MB)
- PP-OCRv6_medium_rec (~40MB)

Total: ~100MB. Internet required. Subsequent runs use cached files.

## Known Issues & Fixes

### Issue: PaddlePaddle OneDNN error
```
ConvertPirAttribute2RuntimeAttribute not support [pir::ArrayAttribute<pir::DoubleAttribute>]
```
**Fix (already applied)**: Set `use_textline_orientation=False` in ocr_server.py

### Issue: PaddlePaddle protobuf error
```
TypeError: Descriptors cannot be created directly.
```
**Fix (already applied)**: Set `PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python` in ocr_server.py

### Issue: Frontend SPA 404 on refresh
**Fix (already applied)**: Added WebMvcConfig.java to forward non-API routes to index.html

## Verification Checklist
- [ ] OCR health: `curl http://localhost:5557/health`
- [ ] Backend health: `curl http://localhost:5566/api/v1/ocr/health`
- [ ] Frontend: Open `http://server-ip:5566` in browser
- [ ] Upload test image → PaddleOCR → verify text extraction
- [ ] Toggle "修复分段" checkbox
- [ ] Adjust confidence threshold slider
- [ ] About page shows "2026.7.5"
- [ ] Refresh non-root pages (no 404 error)

## Useful Commands
```powershell
# Stop all services
taskkill /f /im python.exe
taskkill /f /im java.exe

# View OCR logs (stderr of ocr_server.py goes to console)
# View Java logs (stdout of java -jar)
```
