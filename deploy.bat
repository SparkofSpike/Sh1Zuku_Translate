@echo off
title ShizukuTranslate - Build and Deploy

set SERVER_IP=your-server-ip
set SERVER_USER=root
set SERVER_PATH=/opt/shizuku

echo ========================================
echo  ShizukuTranslate - Build and Deploy
echo ========================================
echo.

echo [1/4] Building Vue Frontend...
cd /d G:\Sh1Zuku_Translate\ShizukuTranslate-frontend
call npm run build
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Vue build failed!
    pause
    exit /b 1
)

echo  Copying frontend to backend static...
if exist G:\Sh1Zuku_Translate\ShizukuTranslate\src\main\resources\static rmdir /s /q G:\Sh1Zuku_Translate\ShizukuTranslate\src\main\resources\static
mkdir G:\Sh1Zuku_Translate\ShizukuTranslate\src\main\resources\static
xcopy G:\Sh1Zuku_Translate\ShizukuTranslate-frontend\dist G:\Sh1Zuku_Translate\ShizukuTranslate\src\main\resources\static /E /I >nul
echo  [OK] Frontend ready

echo [2/4] Building Java Backend...
cd /d G:\Sh1Zuku_Translate\ShizukuTranslate
call mvn clean package -DskipTests
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Java build failed!
    pause
    exit /b 1
)
echo  [OK] Java build success

echo [3/4] Collecting deploy files...
set DEPLOY_DIR=G:\Sh1Zuku_Translate\deploy_package
if exist %DEPLOY_DIR% rmdir /s /q %DEPLOY_DIR%
mkdir %DEPLOY_DIR%

copy G:\Sh1Zuku_Translate\ShizukuTranslate\target\*.jar %DEPLOY_DIR%\ >nul
xcopy G:\Sh1Zuku_Translate\ocr-worker %DEPLOY_DIR%\ocr-worker\ /E /I >nul
copy G:\Sh1Zuku_Translate\start.sh %DEPLOY_DIR%\ >nul

echo  Deploy package ready: %DEPLOY_DIR%

if not "%SERVER_IP%"=="your-server-ip" (
    echo  Uploading to %SERVER_IP%...
    scp -r %DEPLOY_DIR%\* %SERVER_USER%@%SERVER_IP%:%SERVER_PATH%
    echo  [OK] Upload complete
) else (
    echo  [WARN] Server not configured. Manually upload %DEPLOY_DIR% content.
)

echo [4/4] Cleaning temp files...
rmdir /s /q %DEPLOY_DIR% 2>nul
echo  [OK] Cleanup done

echo.
echo ========================================
echo  Build and deploy complete!
echo ========================================
pause
