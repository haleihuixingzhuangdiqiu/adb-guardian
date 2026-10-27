# -*- coding: utf-8 -*-
"""免 root ADB 伴随工具；只管理显式登记的设备，保留 Android 无线配对认证。

python3 adb_guardian.py activate --serial USB_SERIAL --config /absolute/devices.json
python3 adb_guardian.py watch --config /absolute/devices.json --once

配置只含公开设备标识，不存密码、配对码或密钥。不要将真实配置加入版本库。
"""

import argparse
import datetime
import ipaddress
import json
import logging
from logging.handlers import RotatingFileHandler
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import time
from dataclasses import dataclass


PACKAGE = "dev.local.adbguardian"
PERMISSION = "android.permission.WRITE_SECURE_SETTINGS"
DEFAULT_CONFIG = "~/.config/adb-guardian/devices.json"
CONNECT = "_adb-tls-connect._tcp"
PAIRING = "_adb-tls-pairing._tcp"
TIMEOUT = 6.0
MAX_CANDIDATES = 4  # 每类候选每台每轮上限；多余候选轮转，限制单台耗时。
IDENTIFIER = re.compile(r"[A-Za-z0-9][A-Za-z0-9_.-]{0,127}\Z")
SELECTOR = re.compile(r"[A-Za-z0-9\[][A-Za-z0-9_.:%\[\]-]{0,254}\Z")
MDNS_SELECTOR = re.compile(r"adb-[A-Za-z0-9_.-]+(?: \([1-9][0-9]*\))?\._adb-tls-connect\._tcp(?:\.local)?\.?\Z")


class GuardianError(Exception):
    """可向用户展示的固定错误说明；不包含 adb 的原始响应。"""


@dataclass
class Result:
    code: int = 0
    out: str = ""
    err: str = ""
    timed_out: bool = False


class Runner:
    def __init__(self, adb="adb"):
        resolved = shutil.which(adb)
        if not resolved:
            raise GuardianError("找不到 adb；请安装 Android SDK Platform-Tools，或用 --adb 指定路径")
        self.adb = str(Path(resolved).resolve())

    def run(self, args, timeout=TIMEOUT):
        # 固定参数数组；不运行 shell，不读取或改写任何全局 ADB 环境变量。
        try:
            done = subprocess.run(
                [self.adb] + list(args), stdin=subprocess.DEVNULL,
                stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                encoding="utf-8", errors="replace", timeout=timeout, check=False,
            )
            return Result(done.returncode, done.stdout, done.stderr)
        except subprocess.TimeoutExpired:
            return Result(1, timed_out=True)
        except OSError:
            raise GuardianError("无法执行 adb；请检查 --adb 路径与执行权限") from None


def now_text():
    return datetime.datetime.now(datetime.timezone.utc).isoformat(timespec="seconds")


def valid_identifier(value, label):
    if not isinstance(value, str) or not IDENTIFIER.fullmatch(value):
        raise GuardianError(label + " 必须是 1–128 位字母、数字、点、下划线或连字符，且以字母或数字开头")
    return value


def endpoint(value):
    """只接受明确 IP:port（IPv6 必须有方括号），不猜端口、不做 DNS 查询。"""
    if not isinstance(value, str) or len(value) > 255:
        return None
    match = re.fullmatch(r"\[([0-9A-Fa-f:.]+(?:%[A-Za-z0-9_.-]+)?)\]:([0-9]{1,5})", value)
    if not match:
        match = re.fullmatch(r"([0-9.]+):([0-9]{1,5})", value)
    if not match:
        return None
    host, port = match.groups()
    try:
        address = ipaddress.ip_address(host)
    except ValueError:
        return None
    if not 1 <= int(port) <= 65535:
        return None
    host = str(address)
    return ("[" + host + "]" if address.version == 6 else host) + ":" + str(int(port))


def valid_selector(value):
    if not isinstance(value, str) or len(value) > 255 or not (SELECTOR.fullmatch(value) or MDNS_SELECTOR.fullmatch(value)):
        raise GuardianError("serial 格式无效；必须明确指定 adb devices 中的单个序列号")
    if ":" in value and not endpoint(value):
        raise GuardianError("网络 serial 必须是明确的 IP:port，IPv6 使用 [地址]:port")
    return value


def parse_devices(output):
    devices = {}
    for line in output.splitlines():
        match = re.fullmatch(r"(.+?)\s+(device|offline|unauthorized|recovery|sideload|no)(?:\s+.*)?", line.strip())
        if match:
            serial, state = match.groups()
            try:
                valid_selector(serial)
            except GuardianError:
                continue
            devices[serial] = state
    return devices


@dataclass(frozen=True)
class Service:
    name: str
    kind: str
    endpoint: str


def instance_name(name, kind=CONNECT):
    name = name.rstrip(".")
    if name.endswith(".local"):
        name = name[:-6]
    suffix = "." + kind
    if name.endswith(suffix):
        name = name[:-len(suffix)]
    return name


def parse_mdns(output):
    services = []
    for line in output.splitlines():
        # Bonjour 名称冲突会出现 "adb-SERIAL-suffix (2)"，服务名中空格不能按列拆散。
        match = re.fullmatch(r"(.+?)\s+(_adb(?:-tls-connect|-tls-pairing)?\._tcp(?:\.local)?\.?)\s+(\S+)\s*", line.strip())
        if not match:
            continue
        raw_name, kind, raw_address = match.groups()
        kind = kind.rstrip(".")
        if kind.endswith(".local"):
            kind = kind[:-6]
        if kind not in (CONNECT, PAIRING, "_adb._tcp"):
            continue
        address = endpoint(raw_address)
        name = instance_name(raw_name, kind)
        # QR 配对实例可含 @、/ 等字符；仍须识别其端口，绝不作为连接目标。
        safe_name = bool(IDENTIFIER.fullmatch(re.sub(r" \([1-9][0-9]*\)$", "", name))) if kind != PAIRING else bool(name) and len(name) <= 255
        if address and safe_name:
            service = Service(name, kind, address)
            if service not in services:
                services.append(service)
    return services


def matches_name(name, record):
    """完整标识或有明确 '-' 边界的官方前缀；从不按任意 substring 匹配。"""
    name = re.sub(r" \([1-9][0-9]*\)$", "", instance_name(name))
    bases = ["adb-" + record["serialno"]]
    if record.get("guid"):
        bases.extend([record["guid"], "adb-" + record["guid"]])
    for base in bases:
        if name == base:
            return True
        if name.startswith(base + "-") and re.fullmatch(r"[A-Za-z0-9]+", name[len(base) + 1:]):
            return True
    return False


def load_config(path, missing_ok=False):
    path = Path(path).expanduser()
    try:
        if path.stat().st_size > 1024 * 1024:
            raise GuardianError("配置过大（上限 1 MiB）")
        data = json.loads(path.read_text(encoding="utf-8"))
    except FileNotFoundError:
        if missing_ok:
            return {"version": 1, "devices": []}
        raise GuardianError("配置不存在；请先 activate") from None
    except (OSError, ValueError):
        raise GuardianError("配置无法读取或不是有效 UTF-8 JSON") from None
    if not isinstance(data, dict) or data.get("version") != 1 or not isinstance(data.get("devices"), list):
        raise GuardianError("配置必须包含 version: 1 和 devices 数组")
    seen = set()
    for record in data["devices"]:
        if not isinstance(record, dict):
            raise GuardianError("设备记录必须是对象")
        serialno = valid_identifier(record.get("serialno"), "ro.serialno")
        if serialno in seen:
            raise GuardianError("配置含重复 ro.serialno；请合并记录或重新 activate")
        seen.add(serialno)
        if record.get("guid") is not None:
            valid_identifier(record["guid"], "GUID")
        if not isinstance(record.get("model"), str) or len(record["model"]) > 256 or any(ord(c) < 32 for c in record["model"]):
            raise GuardianError("model 格式无效")
        if record.get("endpoint") is not None:
            if not endpoint(record["endpoint"]):
                raise GuardianError("配置 endpoint 必须是 IP:port")
            record["endpoint"] = endpoint(record["endpoint"])
        if record.get("usb_serial") is not None:
            valid_identifier(record["usb_serial"], "USB serial")
    return data


def save_config(path, data):
    """同目录原子替换；从创建起就是 0600，避免先写宽权限再 chmod 的窗口。"""
    path = Path(path).expanduser().absolute()
    temporary = None
    try:
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        if path.is_symlink():
            raise GuardianError("拒绝覆盖符号链接配置；请指定普通文件路径")
        fd, temporary = tempfile.mkstemp(prefix=".adb-guardian-", dir=str(path.parent))
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            os.fchmod(stream.fileno(), 0o600)
            json.dump(data, stream, ensure_ascii=False, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        temporary = None
    except OSError:
        raise GuardianError("配置写入失败；请检查目标目录权限和可用空间") from None
    finally:
        if temporary is not None:
            os.unlink(temporary)


def failure(result):
    if result.timed_out:
        return "timeout"
    message = (result.out + "\n" + result.err).lower()
    if any(word in message for word in ("unauthorized", "not authorized", "failed to authenticate", "authentication failed", "authentication error", "permission denied")):
        return "unauthorized"
    if result.code or any(word in message for word in ("failed", "cannot connect", "unable to connect", "refused", "offline", "error:")):
        return "unavailable"
    return None


def read_property(runner, serial, prop):
    result = runner.run(["-s", serial, "shell", "getprop", prop])
    if failure(result):
        raise GuardianError("无法读取目标设备属性（离线、未授权或超时）；请核对设备授权")
    return result.out.strip()


def activate(runner, serial, path, emit=print):
    serial = valid_selector(serial)
    data = load_config(path, missing_ok=True)
    devices_result = runner.run(["devices", "-l"])
    if devices_result.code or devices_result.timed_out:
        raise GuardianError("adb devices 失败或超时")
    state = parse_devices(devices_result.out).get(serial)
    if state != "device":
        raise GuardianError("指定设备未连接或未授权；只接受 adb devices 中状态为 device 的明确 serial")
    installed = runner.run(["-s", serial, "shell", "pm", "path", PACKAGE])
    if failure(installed) or not any(line.startswith("package:") for line in installed.out.splitlines()):
        raise GuardianError("目标设备未安装 " + PACKAGE + "，或安装检查失败")
    serialno = valid_identifier(read_property(runner, serial, "ro.serialno"), "ro.serialno")
    guid = read_property(runner, serial, "persist.adb.wifi.guid") or None
    if guid:
        valid_identifier(guid, "GUID")
    model = read_property(runner, serial, "ro.product.model")
    if not model or len(model) > 256 or any(ord(c) < 32 for c in model):
        raise GuardianError("设备 model 为空或格式无效")
    granted = runner.run(["-s", serial, "shell", "pm", "grant", PACKAGE, PERMISSION])
    if failure(granted) or granted.out.strip() or granted.err.strip():
        raise GuardianError("WRITE_SECURE_SETTINGS 授权失败或厂商拒绝；未登记成功，请检查手机限制")
    # 不读完整 settings。定向查看本 App 权限，且绝不打印原始 dumpsys 响应。
    permission = runner.run(["-s", serial, "shell", "dumpsys", "package", PACKAGE])
    if failure(permission) or not re.search(re.escape(PERMISSION) + r":\s*granted=true\b", permission.out):
        raise GuardianError("无法确认 WRITE_SECURE_SETTINGS 已授予（可能被厂商拒绝）；未登记成功")
    record = {"serialno": serialno, "guid": guid, "model": model, "endpoint": endpoint(serial), "activated_at": now_text()}
    if endpoint(serial) is None and "._adb" not in serial:
        record["usb_serial"] = valid_identifier(serial, "USB serial")
    elif endpoint(serial) is None:
        discovered = runner.run(["mdns", "services"])
        if not discovered.code and not discovered.timed_out:
            # 只从当前已选中的服务名取 endpoint；不将其他同前缀候选写作已验证地址。
            addresses = {s.endpoint for s in parse_mdns(discovered.out) if s.kind == CONNECT and s.name == instance_name(serial)}
            if len(addresses) == 1:
                record["endpoint"] = addresses.pop()
    previous = next((r for r in data["devices"] if r["serialno"] == serialno), None)
    if previous:
        if previous.get("usb_serial") and "usb_serial" not in record:
            record["usb_serial"] = previous["usb_serial"]
        data["devices"][data["devices"].index(previous)] = record
    else:
        data["devices"].append(record)
    save_config(path, data)
    emit("已授权并登记：" + serialno + "（" + model + "）；网络 endpoint：" + (record["endpoint"] or "未知（未从 USB 推断 IP）"))
    if not guid:
        emit("尚无无线 GUID；启用系统无线调试并配对后可再次 activate 更新同一条记录。")
    emit("仍需在手机系统无线调试中信任当前 Wi-Fi，并与此电脑完成无线配对；随后在 App 中开始守护。")
    emit("配置已以 0600 保存；请勿提交真实配置到版本库。")
    return record


@dataclass
class DeviceState:
    failures: int = 0
    next_due: float = 0.0
    status: str = ""
    detail: str = ""
    updated_at: str = ""
    manual_required: bool = False
    cursor: int = 0
    attached_cursor: int = 0
    healthy_target: str = ""


MESSAGES = {
    "healthy": "shell 健康且 ro.serialno 精确一致",
    "waiting": "未发现目标连接服务；等待手机 App 恢复可信 Wi-Fi 的无线调试，或检查网络/mDNS",
    "unavailable": "目标当前不可达；等待手机 App 恢复服务或网络恢复",
    "timeout": "目标命令超时；等待服务恢复，按本机独立退避重试",
    "identity_mismatch": "候选 ro.serialno 不符；拒绝认作目标设备，请核对过期 endpoint",
    "unauthorized": "候选未授权或配对已取消；需人工核对并重新配对/授权，之后重启 watch 或等待授权传输出现",
    "discovery_error": "adb devices 失败；本轮暂停目标操作，等待发现恢复",
}


class Watcher:
    def __init__(self, runner, data, interval=30, emit=print, clock=time.monotonic):
        if not math.isfinite(interval) or not 1 <= interval <= 3600:
            raise GuardianError("interval 必须在 1–3600 秒之间")
        self.runner = runner
        self.records = data["devices"]
        self.interval = interval
        self.emit = emit
        self.clock = clock
        self.states = {r["serialno"]: DeviceState() for r in self.records}
        self.discovery_message = None

    def report(self, record, status, target=""):
        state = self.states[record["serialno"]]
        detail = MESSAGES[status] + (("；目标 " + target) if target else "")
        changed = (state.status, state.detail) != (status, detail)
        state.status, state.detail, state.updated_at = status, detail, now_text()
        if changed:
            self.emit(json.dumps({"updated_at": state.updated_at, "serialno": record["serialno"], "status": status, "detail": detail}, ensure_ascii=False))

    def discovery_notice(self, message):
        if self.discovery_message != message:
            self.discovery_message = message
            self.emit(json.dumps({"updated_at": now_text(), "status": "discovery", "detail": message}, ensure_ascii=False))

    def version_notice(self):
        result = self.runner.run(["version"])
        match = re.search(r"\bVersion (\d+)\.", result.out)
        if failure(result):
            self.discovery_notice("adb version 检查失败；仍尝试 devices / mdns services")
        elif match and int(match.group(1)) < 30:
            self.discovery_notice("Platform-Tools 版本较旧；请升级以支持无线 mDNS，已知 endpoint 仍可尝试")

    def probe(self, target, record):
        result = self.runner.run(["-s", target, "shell", "getprop", "ro.serialno"])
        problem = failure(result)
        if problem:
            return problem
        if not result.out.strip():
            return "unavailable"
        return "healthy" if result.out.strip() == record["serialno"] else "identity_mismatch"

    def attached_targets(self, record, devices, services):
        names = {s.name for s in services if s.kind == CONNECT and matches_name(s.name, record)}
        addresses = {s.endpoint for s in services if s.kind == CONNECT and matches_name(s.name, record)}
        if record.get("endpoint"):
            addresses.add(record["endpoint"])
        pairing_addresses = {s.endpoint for s in services if s.kind == PAIRING}
        targets = []
        for serial in devices:
            name_match = "._adb-tls-connect._tcp" in serial and matches_name(serial, record)
            if serial in (record["serialno"], record.get("usb_serial")) or name_match or serial in names or endpoint(serial) in addresses:
                if endpoint(serial) not in pairing_addresses:
                    targets.append(serial)
        preferred = self.states[record["serialno"]].healthy_target
        return sorted(targets, key=lambda s: (s != preferred, s))

    def fail(self, record, problem, current):
        state = self.states[record["serialno"]]
        state.failures = min(state.failures + 1, 12)
        delay = min(self.interval * (2 ** (state.failures - 1)), max(self.interval, 300))
        state.next_due = current + delay
        state.manual_required = problem == "unauthorized" or state.manual_required
        state.healthy_target = ""
        self.report(record, problem)

    def healthy(self, record, target, current):
        state = self.states[record["serialno"]]
        state.failures = 0
        state.next_due = current + self.interval
        state.manual_required = False
        state.healthy_target = target
        self.report(record, "healthy", target)

    def step(self):
        devices_result = self.runner.run(["devices", "-l"])
        mdns_result = self.runner.run(["mdns", "services"])
        # 不探测 track-services/proto 等新版本专属命令；services 新旧输出均优先兼容。
        mdns_ok = not mdns_result.code and not mdns_result.timed_out and "unknown command" not in (mdns_result.out + mdns_result.err).lower() and ("List of discovered" in mdns_result.out or bool(parse_mdns(mdns_result.out)))
        self.discovery_notice("mDNS services 可用" if mdns_ok else "mDNS 不可用或 adb 版本不支持；请升级 Platform-Tools，仍可使用登记 endpoint；无 endpoint 时等待发现恢复")
        services = parse_mdns(mdns_result.out) if mdns_ok else []
        if devices_result.code or devices_result.timed_out:
            for record in self.records:
                self.report(record, "discovery_error")
            return
        devices = parse_devices(devices_result.out)
        for record in self.records:
            state = self.states[record["serialno"]]
            current = self.clock()
            if current < state.next_due:
                continue
            self.check_device(record, state, devices, services, current)

    def check_device(self, record, state, devices, services, current):
        problems = []
        tried = set()
        attached = self.attached_targets(record, devices, services)
        preferred = state.healthy_target if state.healthy_target in attached else None
        if preferred:
            attached.remove(preferred)
        if attached:
            offset = state.attached_cursor % len(attached)
            attached = attached[offset:] + attached[:offset]
            state.attached_cursor = (offset + MAX_CANDIDATES - bool(preferred)) % len(attached)
        if preferred:
            attached.insert(0, preferred)
        for target in attached[:MAX_CANDIDATES]:
            # 持续 unauthorized 不做 shell/connect 重试，直到出现已授权传输。
            if devices[target] == "unauthorized":
                problems.append("unauthorized")
                tried.add(target)
                tried.update(s.endpoint for s in services if s.kind == CONNECT and s.name == instance_name(target))
                continue
            if state.manual_required and devices[target] != "device":
                continue
            problem = self.probe(target, record)
            if problem == "healthy":
                self.healthy(record, target, current)
                return
            problems.append(problem)
            # 不符身份和未授权的传输绝不 reconnect/disconnect。
            if problem in ("unauthorized", "identity_mismatch"):
                tried.add(target)
                tried.update(s.endpoint for s in services if s.kind == CONNECT and s.name == instance_name(target))
        if state.manual_required:
            self.fail(record, "unauthorized", current)
            return
        pairing_addresses = {s.endpoint for s in services if s.kind == PAIRING}
        dynamic = sorted({s.endpoint for s in services if s.kind == CONNECT and matches_name(s.name, record) and s.endpoint not in pairing_addresses})
        # 动态服务优先；历史 endpoint 只是候选，每次仍精确校验身份。
        candidates = []
        if dynamic:
            offset = state.cursor % len(dynamic)
            candidates = dynamic[offset:] + dynamic[:offset]
            state.cursor = (offset + MAX_CANDIDATES) % len(dynamic)
        saved = record.get("endpoint")
        if saved and saved not in candidates and saved not in pairing_addresses:
            candidates.append(saved)
        if candidates:
            for target in candidates[:MAX_CANDIDATES]:
                if target in tried:
                    continue
                result = self.runner.run(["connect", target])
                problem = failure(result)
                if not problem:
                    problem = self.probe(target, record)
                if problem == "healthy":
                    self.healthy(record, target, current)
                    return
                problems.append(problem)
        # 未授权比网络错误更需要人工；任何单个不符身份的响应都不能算连接成功。
        problem = next((p for p in ("unauthorized", "identity_mismatch", "timeout", "unavailable") if p in problems), "waiting")
        self.fail(record, problem, current)

    def run(self, once=False, sleep=time.sleep):
        if not self.records:
            raise GuardianError("配置中没有设备；请先 activate")
        self.version_notice()
        while True:
            self.step()
            if once:
                return 0 if all(s.status == "healthy" for s in self.states.values()) else 1
            sleep(self.interval)


def build_parser():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    commands = parser.add_subparsers(dest="command", required=True)
    for name, help_text in (("activate", "仅向指定设备授权并登记"), ("watch", "只守护已登记设备")):
        command = commands.add_parser(name, help=help_text)
        command.add_argument("--config", default=DEFAULT_CONFIG, help="私有配置路径（默认 %(default)s）")
        command.add_argument("--adb", default="adb", help="adb 可执行路径，默认使用当前用户 PATH")
        if name == "activate":
            command.add_argument("--serial", required=True, help="必须明确指定 adb devices 的 serial")
        else:
            command.add_argument("--interval", type=float, default=30, help="轮询秒数，1–3600（默认 30）")
            command.add_argument("--once", action="store_true", help="仅检查一轮，全部健康返回 0，否则返回 1")
            command.add_argument("--log-file", help="可选轮转日志路径；每份 512 KiB，保留两份历史")
    return parser


def main(argv=None):
    args = build_parser().parse_args(argv)
    try:
        if args.command == "activate":
            valid_selector(args.serial)
            activate(Runner(args.adb), args.serial, args.config)
            return 0
        data = load_config(args.config)
        if not math.isfinite(args.interval) or not 1 <= args.interval <= 3600:
            raise GuardianError("interval 必须在 1–3600 秒之间")
        emit = print
        if args.log_file:
            log_path = Path(args.log_file).expanduser()
            log_path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            logger = logging.getLogger("adb_guardian")
            logger.setLevel(logging.INFO)
            logger.propagate = False
            handler = RotatingFileHandler(log_path, maxBytes=512 * 1024, backupCount=2, encoding="utf-8")
            logger.addHandler(handler)
            emit = logger.info
        return Watcher(Runner(args.adb), data, args.interval, emit=emit).run(args.once)
    except KeyboardInterrupt:
        print("已停止守护；未修改设备调试状态。", file=sys.stderr)
        return 130
    except GuardianError as exc:
        print("错误：" + str(exc), file=sys.stderr)
        return 2


if __name__ == "__main__":
    sys.exit(main())
