# -*- coding: utf-8 -*-
"""用户显式运行 install 时才安装登录后自动启动的电脑守护；remove 只移除本工具的服务。"""
import argparse
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys

from adb_guardian import DEFAULT_CONFIG, GuardianError, Runner, load_config

LABEL = "dev.local.adbguardian.watch"


def make_plist(python, script, config, adb, log):
    return {
        "Label": LABEL,
        "ProgramArguments": [str(python), "-u", str(script), "watch", "--config", str(config),
                             "--adb", str(adb), "--log-file", str(log)],
        "RunAtLoad": True,
        "KeepAlive": {"SuccessfulExit": False},
        "ThrottleInterval": 30,
        "ProcessType": "Background",
        "EnvironmentVariables": {"PATH": str(Path(adb).parent) + ":/usr/bin:/bin:/usr/sbin:/sbin"},
    }


def launchctl(*args):
    return subprocess.run(["/bin/launchctl", *args], capture_output=True, text=True, timeout=15, check=False)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["install", "remove", "status"])
    parser.add_argument("--config", default=DEFAULT_CONFIG)
    parser.add_argument("--adb", default="adb")
    args = parser.parse_args()
    if sys.platform != "darwin":
        raise GuardianError("此入口仅用于 macOS；其他系统请直接运行 adb_guardian.py watch")
    folder = Path.home() / "Library/Application Support/ADBGuardian"
    script = folder / "adb_guardian.py"
    plist_path = Path.home() / "Library/LaunchAgents" / (LABEL + ".plist")
    target = "gui/" + str(os.getuid()) + "/" + LABEL
    loaded = launchctl("print", target).returncode == 0
    if args.action == "status":
        print("电脑守护已加载" if loaded else "电脑守护未加载")
        return 0 if loaded else 1
    if plist_path.exists():
        with plist_path.open("rb") as stream:
            existing = plistlib.load(stream)
        if existing.get("Label") != LABEL or str(script) not in existing.get("ProgramArguments", []):
            raise GuardianError("同路径存在其他配置，已保留；请先核对 LaunchAgent")
    elif loaded:
        raise GuardianError("发现来源不明的同名已加载服务，已保留；请先核对 LaunchAgent")
    if args.action == "install":
        config = Path(args.config).expanduser().resolve()
        if not load_config(config)["devices"]:
            raise GuardianError("先 activate 至少一台设备，再安装电脑守护")
        adb = Runner(args.adb).adb
        source = Path(__file__).with_name("adb_guardian.py")
        if not source.is_file():
            raise GuardianError("缺少配套 adb_guardian.py，请保持交付文件完整")
    if loaded and launchctl("bootout", target).returncode != 0:
        raise GuardianError("无法停止本工具的旧服务，未继续改写")
    if args.action == "remove":
        if plist_path.exists():
            plist_path.unlink()
        print("已停止并移除电脑自启动；设备登记、日志和手机设置均保留。")
        return 0
    folder.mkdir(parents=True, exist_ok=True, mode=0o700)
    shutil.copy2(source, script)
    log = Path.home() / "Library/Logs/ADBGuardian/guardian.log"
    log.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    plist_path.parent.mkdir(parents=True, exist_ok=True)
    content = make_plist(sys.executable, script, config, adb, log)
    plist_path.write_bytes(plistlib.dumps(content))
    plist_path.chmod(0o600)
    result = launchctl("bootstrap", "gui/" + str(os.getuid()), str(plist_path))
    if result.returncode != 0:
        raise GuardianError("配置已写入但系统未启动服务；请在当前 Mac 桌面用户的终端执行 install 重试")
    print("已安装电脑守护：登录后自动启动，退出终端后继续运行。")
    print("Mac 睡眠期间无法维持连接；唤醒后继续。每份日志最多 512 KiB，保留两份历史。")
    print("日志：" + str(log))
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (GuardianError, OSError, ValueError, subprocess.TimeoutExpired) as error:
        print("未完成：" + str(error), file=sys.stderr)
        sys.exit(2)
