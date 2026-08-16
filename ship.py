#!/usr/bin/env python3
"""
ShizukuTranslate — 本地一键部署脚本

用法:
    python ship.py              # 完整部署（pull + 编译 + 上传 + 重启）
    python ship.py --skip-pull  # 跳过 git pull
    python ship.py --upload-only # 只上传已编译好的包
    python ship.py --help       # 查看帮助

环境要求:
    - Python 3.8+
    - Node.js 20+  (前端构建)
    - JDK 21      (后端构建)
    - Maven       (后端构建)
    - OpenSSH     (scp/ssh 上传)
"""

import argparse
import glob
import os
import shutil
import subprocess
import sys
import time
from pathlib import Path

# ======================== 配置 ========================
# 服务器连接
SERVER_HOST = "ad.rainplay.cn"
SERVER_PORT = 22591
SERVER_USER = "Administrator"
SSH_KEY = str(Path.home() / ".ssh" / "id_rsa")
SERVER_PATH = "D:/Sh1ZukuTranslate"

# 项目路径
PROJECT_ROOT = Path(__file__).resolve().parent
FRONTEND_DIR = PROJECT_ROOT / "ShizukuTranslate-frontend"
BACKEND_DIR = PROJECT_ROOT / "ShizukuTranslate"
STATIC_DIR = BACKEND_DIR / "src" / "main" / "resources" / "static"
DEPLOY_DIR = PROJECT_ROOT / "deploy_package"


# ======================== 工具函数 ========================
def log(msg: str, ok: bool = True):
    """带颜色的日志输出"""
    icon = "✅" if ok else "❌"
    print(f"  {icon} {msg}")


def _resolve_tool(name: str) -> str | None:
    """查找工具的可执行文件路径（多策略回退）"""
    # Strategy 1: shutil.which 走当前 PATH + PATHEXT
    path = shutil.which(name)
    if path:
        return path

    # Strategy 2: where.exe (cmd.exe 的查找)
    try:
        result = subprocess.run(
            ["where.exe", name],
            capture_output=True, text=True, check=False,
            shell=True  # 通过 cmd.exe 获取完整系统 PATH
        )
        if result.returncode == 0:
            for line in result.stdout.strip().split("\n"):
                line = line.strip()
                if line and line.lower().endswith((".exe", ".cmd", ".bat", ".ps1")):
                    return line
    except Exception:
        pass

    return None


def _subprocess_run(cmd: list, cwd=None, capture=False):
    """执行子进程，自动处理 Windows GBK 编码"""
    kwargs = dict(cwd=cwd, capture_output=capture, check=False)
    try:
        return subprocess.run(cmd, text=True, errors='replace', **kwargs)
    except TypeError:
        # Python < 3.12 不支持 errors 参数时兜底
        return subprocess.run(cmd, text=True, **kwargs)


def run(cmd: list, cwd: Path | None = None, capture: bool = False) -> subprocess.CompletedProcess:
    """执行命令"""
    print(f"  $ {' '.join(cmd)}")
    try:
        return _subprocess_run(cmd, cwd=cwd, capture=capture)
    except FileNotFoundError as e:
        tool = cmd[0]
        resolved = _resolve_tool(tool)
        if resolved:
            cmd[0] = resolved
            return subprocess.run(cmd, cwd=cwd, capture_output=capture, text=True, check=False)
        print(f"    [ERROR] 命令未找到: {tool}（尝试了 PATH 查找、where.exe）")
        sys.exit(1)


def check_tool(name: str, cmd: list[str]) -> str | None:
    """检查工具是否可用"""
    # 直接运行时查找
    try:
        # 先用 os.environ 路径试
        result = subprocess.run(cmd, capture_output=True, text=True, check=False)
        if result.returncode == 0:
            return result.stdout.strip().split("\n")[0]
    except FileNotFoundError:
        pass

    # 回退：通过 _resolve_tool 找到绝对路径再跑
    path = _resolve_tool(name)
    if path:
        try:
            result = subprocess.run([path] + cmd[1:], capture_output=True, text=True, check=False)
            if result.returncode == 0:
                return result.stdout.strip().split("\n")[0]
        except FileNotFoundError:
            pass
    return None


# ======================== 构建步骤 ========================
def step_git_pull():
    """拉取最新代码"""
    print("\n[1/7] 拉取最新代码...")
    run(["git", "pull"], cwd=PROJECT_ROOT)
    log("代码已更新")


def step_build_frontend():
    """构建前端"""
    print("\n[2/7] 构建前端...")
    npm = check_tool("npm", ["npm", "--version"])
    if not npm:
        log("npm 未安装，跳过前端构建", ok=False)
        return False
    log(f"npm {npm}")

    # 安装依赖
    run(["npm", "ci"], cwd=FRONTEND_DIR, capture=True)
    # 构建
    run(["npm", "run", "build"], cwd=FRONTEND_DIR, capture=True)
    log("前端构建完成")
    return True


def step_copy_static():
    """复制前端产物到后端"""
    print("\n[3/7] 复制前端到后端静态目录...")
    if STATIC_DIR.exists():
        shutil.rmtree(STATIC_DIR)
    STATIC_DIR.mkdir(parents=True)

    dist_dir = FRONTEND_DIR / "dist"
    if not dist_dir.exists():
        log("dist 目录不存在，跳过", ok=False)
        return False

    for item in dist_dir.iterdir():
        dest = STATIC_DIR / item.name
        if item.is_dir():
            shutil.copytree(item, dest)
        else:
            shutil.copy2(item, dest)
    log("静态文件已复制")
    return True


def step_build_backend():
    """构建后端"""
    print("\n[4/7] 构建后端...")
    java = check_tool("java", ["java", "--version"])
    mvn = check_tool("mvn", ["mvn", "--version"])
    if not java:
        log("JDK 未安装", ok=False)
        return False
    if not mvn:
        log("Maven 未安装", ok=False)
        return False

    # Maven 构建
    run(["mvn", "clean", "package", "-DskipTests"], cwd=BACKEND_DIR, capture=True)

    # 复制为固定文件名
    target_jar = list((BACKEND_DIR / "target").glob("*.jar"))
    if target_jar:
        shutil.copy2(target_jar[0], BACKEND_DIR / "target" / "translator.jar")
        log(f"后端构建完成: {target_jar[0].name}")
    else:
        log("未找到构建产物", ok=False)
        return False
    return True


def step_package():
    """打包部署文件"""
    print("\n[5/7] 打包部署文件...")
    if DEPLOY_DIR.exists():
        shutil.rmtree(DEPLOY_DIR)
    DEPLOY_DIR.mkdir(parents=True)
    (DEPLOY_DIR / "logs").mkdir()

    # jar
    jar_src = BACKEND_DIR / "target" / "translator.jar"
    if jar_src.exists():
        shutil.copy2(jar_src, DEPLOY_DIR / "translator.jar")

    # ocr-worker
    ocr_src = PROJECT_ROOT / "ocr-worker"
    ocr_dst = DEPLOY_DIR / "ocr-worker"
    shutil.copytree(ocr_src, ocr_dst, ignore=shutil.ignore_patterns("venv", ".venv", "__pycache__"))

    # deploy 脚本
    ps1_src = PROJECT_ROOT / ".github" / "workflows" / "deploy.ps1"
    if ps1_src.exists():
        shutil.copy2(ps1_src, DEPLOY_DIR / "deploy.ps1")

    log("部署包已打包")
    return True


def step_upload():
    """SCP 上传到服务器"""
    print("\n[6/7] 上传到服务器...")
    if not Path(SSH_KEY).exists():
        log(f"SSH 密钥不存在: {SSH_KEY}", ok=False)
        return False

    # 先单独上传停止脚本（新文件不受 translator.jar 文件锁影响），
    # 确保服务器上已有它，再执行 stop，最后传其余部署文件。
    stop_script_src = PROJECT_ROOT / "_stop_services.ps1"
    if stop_script_src.exists():
        _subprocess_run([
            "scp", "-o", "StrictHostKeyChecking=no",
            "-P", str(SERVER_PORT),
            "-i", SSH_KEY,
            str(stop_script_src),
            f"{SERVER_USER}@{SERVER_HOST}:{SERVER_PATH}/_stop_services.ps1"
        ], capture=True)

    # 停止服务（按命令行精确杀，绝不误伤 Minecraft 等其它 Java 进程）
    stop_script = (
        'powershell -ExecutionPolicy Bypass -File '
        f'{SERVER_PATH}\\_stop_services.ps1'
    )
    _subprocess_run([
        "ssh", "-o", "StrictHostKeyChecking=no",
        "-p", str(SERVER_PORT),
        "-i", SSH_KEY,
        f"{SERVER_USER}@{SERVER_HOST}",
        stop_script
    ], capture=True)
    log("旧服务已停止")

    # SCP 上传
    result = _subprocess_run([
        "scp", "-o", "StrictHostKeyChecking=no",
        "-P", str(SERVER_PORT),
        "-i", SSH_KEY,
        "-r", str(DEPLOY_DIR / "*"),
        f"{SERVER_USER}@{SERVER_HOST}:{SERVER_PATH}/"
    ], capture=True)

    if result.returncode != 0:
        log(f"SCP 上传失败: {result.stderr.strip()}", ok=False)
        return False

    log("文件已上传")
    return True


def step_restart():
    """SSH 重启服务"""
    print("\n[7/7] 重启服务...")
    python_path = r"C:\Users\Administrator\AppData\Local\Programs\Python\Python312\python.exe"
    backend_bat = f"{SERVER_PATH}\\_task_backend.bat"

    # schtasks 方式：SSH 断开后进程仍在
    restart_script = (
        f'powershell -ExecutionPolicy Bypass -File {SERVER_PATH}\\_stop_services.ps1 & '
        f'schtasks /delete /tn SvcShizuku /f 2>nul & '
        f'schtasks /create /tn SvcShizuku /tr "{backend_bat}" /sc once /st 00:00 /f 2>nul & '
        f'schtasks /run /tn SvcShizuku 2>nul'
    )

    result = _subprocess_run([
        "ssh", "-o", "StrictHostKeyChecking=no",
        "-p", str(SERVER_PORT),
        "-i", SSH_KEY,
        f"{SERVER_USER}@{SERVER_HOST}",
        restart_script
    ], capture=True)

    if result.returncode != 0 and result.returncode != 1:
        # SSH 返回 1 在某些 Windows 版本是正常的
        log(f"SSH 重启可能有警告: {result.stderr.strip()}", ok=False)

    # 等几秒验证（schtasks 启动比 start /B 慢）
    time.sleep(20)

    # 验证
    verify = _subprocess_run([
        "ssh", "-o", "StrictHostKeyChecking=no",
        "-p", str(SERVER_PORT),
        "-i", SSH_KEY,
        f"{SERVER_USER}@{SERVER_HOST}",
        f"netstat -ano | findstr 5566"
    ], capture=True)

    if "LISTENING" in verify.stdout:
        log("服务已启动 (端口 5566)")
        log("网站: http://ad.rainplay.cn:15066")
        return True
    else:
        log("服务可能未正常启动，请手动检查服务器", ok=False)
        return False


# ======================== 主流程 ========================
def main():
    parser = argparse.ArgumentParser(description="ShizukuTranslate 一键部署")
    parser.add_argument("--skip-pull", action="store_true", help="跳过 git pull")
    parser.add_argument("--upload-only", action="store_true", help="只上传已编译好的包")
    args = parser.parse_args()

    print("=" * 52)
    print("  ShizukuTranslate — 一键部署")
    print("=" * 52)

    if args.upload_only:
        if not DEPLOY_DIR.exists():
            log("deploy_package 不存在，请先完整部署一次", ok=False)
            sys.exit(1)
        step_upload()
        step_restart()
        print("\n" + "=" * 52)
        print("  上传完成！")
        print("=" * 52)
        return

    if not args.skip_pull:
        step_git_pull()

    built = step_build_frontend()
    if built:
        step_copy_static()

    built_backend = step_build_backend()

    if built_backend:
        step_package()
        step_upload()
        step_restart()

    print("\n" + "=" * 52)
    print("  部署流程结束")
    print("=" * 52)


if __name__ == "__main__":
    main()
