#!/bin/bash
# ShizukuTranslate - Server startup script
# Usage: ./start.sh [foreground]
#   foreground : run in terminal (default)
#   otherwise  : daemon mode with nohup

MODE=${1:-foreground}
BASE_DIR="$(cd "$(dirname "$0")" && pwd)"
OCR_DIR="$BASE_DIR/ocr-worker"
JAVA_DIR="$BASE_DIR"
JAR_FILE=$(ls "$JAVA_DIR"/*.jar 2>/dev/null | head -1)

echo "========================================"
echo " ShizukuTranslate Server Startup"
echo " Mode: $MODE"
echo "========================================"

# Create logs dir
mkdir -p "$BASE_DIR/logs"

# ====== Start Python OCR Service ======
echo "[1/2] Starting Python OCR Service (port 5557)..."
cd "$OCR_DIR"
if [ -f venv/bin/activate ]; then
    source venv/bin/activate
fi

if [ "$MODE" = "foreground" ]; then
    python ocr_server.py &
else
    nohup python ocr_server.py > "$BASE_DIR/logs/ocr.log" 2>&1 &
fi
sleep 3

# Verify OCR health
if curl -s http://localhost:5557/health > /dev/null 2>&1; then
    echo "  [OK] OCR service is running"
else
    echo "  [WARN] OCR service not responding (check logs/ocr.log)"
fi

# ====== Start Java Backend ======
echo "[2/2] Starting Java Backend (port 5566)..."
cd "$JAVA_DIR"

if [ -z "$JAR_FILE" ]; then
    echo "  [ERROR] No jar file found! Run deploy.bat first."
    exit 1
fi

if [ "$MODE" = "foreground" ]; then
    java -jar "$JAR_FILE"
else
    nohup java -jar "$JAR_FILE" > "$BASE_DIR/logs/backend.log" 2>&1 &
    echo "  PID: $!"
fi

echo ""
echo "========================================"
echo " Startup complete!"
echo " Open: http://localhost:5566"
echo " OCR:  http://localhost:5557"
echo "========================================"
echo ""
echo " To stop: pkill -f 'ocr_server.py' && pkill -f 'translator.*\.jar'"
