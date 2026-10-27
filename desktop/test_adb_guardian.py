# -*- coding: utf-8 -*-
"""纯标准库 / fake runner 回归：python3 -m unittest -v（在 desktop 中）。"""

import contextlib
import io
import json
from pathlib import Path
import stat
import subprocess
import tempfile
import unittest
from unittest import mock

if __package__:
    from . import adb_guardian as g
else:
    import adb_guardian as g


def record(serial="SERIAL", **updates):
    data = {"serialno": serial, "guid": "GUID123", "model": "iQOO test", "endpoint": None}
    data.update(updates)
    return data


def config(*records):
    return {"version": 1, "devices": list(records)}


def mdns(*lines):
    return g.Result(out="List of discovered mdns services\n" + "\n".join(lines))


class FakeRunner:
    def __init__(self, replies=None):
        self.replies = {
            ("version",): g.Result(out="Android Debug Bridge version 1.0.41\nVersion 36.0.0-test\n"),
            ("devices", "-l"): g.Result(out="List of devices attached\n"),
            ("mdns", "services"): mdns(),
        }
        self.replies.update(replies or {})
        self.calls = []

    def run(self, args, timeout=g.TIMEOUT):
        self.calls.append((tuple(args), timeout))
        key = tuple(args)
        if key not in self.replies:
            raise AssertionError("unplanned command: " + repr(key))
        reply = self.replies[key]
        return reply() if callable(reply) else reply

    @property
    def commands(self):
        return [call[0] for call in self.calls]


def probe(target):
    return ("-s", target, "shell", "getprop", "ro.serialno")


class ParsingTests(unittest.TestCase):
    def test_mdns_distinguishes_pairing_connect_and_legacy(self):
        services = g.parse_mdns("""List of discovered mdns services
adb-SERIAL-A _adb-tls-connect._tcp 192.168.1.2:33333
adb-SERIAL-B _adb-tls-pairing._tcp 192.168.1.2:44444
adb-SERIAL _adb._tcp 192.168.1.2:5555
studio-g@<xeYnap/ _adb-tls-pairing._tcp 192.168.1.3:45678
""")
        self.assertEqual([s.kind for s in services], [g.CONNECT, g.PAIRING, "_adb._tcp", g.PAIRING])

    def test_mdns_full_names_trailing_dot_ipv6_and_duplicates(self):
        row = "adb-SERIAL-A._adb-tls-connect._tcp.local. _adb-tls-connect._tcp. [fe80::1%en0]:12345\n"
        services = g.parse_mdns(row + row + "bad _adb-tls-connect._tcp 1.2.3.4:99999\n")
        self.assertEqual(services, [g.Service("adb-SERIAL-A", g.CONNECT, "[fe80::1%en0]:12345")])

    def test_serial_boundary_never_arbitrary_substring(self):
        r = record()
        for name in ("adb-SERIAL-X", "adb-SERIAL-X._adb-tls-connect._tcp", "adb-SERIAL"):
            self.assertTrue(g.matches_name(name, r), name)
        for name in ("adb-SERIAL2-X", "adb-XSERIAL-X", "prefix-adb-SERIAL-X", "adb-SERIAL-X-extra", "SERIAL", "adb-serial-X"):
            self.assertFalse(g.matches_name(name, r), name)

    def test_guid_exact_or_boundary_only(self):
        for name in ("GUID123", "adb-GUID123", "adb-GUID123-Z9", "GUID123-Z9"):
            self.assertTrue(g.matches_name(name, record()), name)
        for name in ("GUID1234", "adb-GUID1234-Z9", "XGUID123", "prefix-GUID123-Z9"):
            self.assertFalse(g.matches_name(name, record()), name)

    def test_endpoint_rejects_hostnames_missing_ports_and_injection(self):
        for address in ("host:5555", "1.2.3.4", "1.2.3.4:0", "1.2.3.4:65536", "1.2.3.4:12;id", "::1:5555", "999.1.1.1:5555"):
            self.assertIsNone(g.endpoint(address), address)
        self.assertEqual(g.endpoint("192.168.1.2:01234"), "192.168.1.2:1234")
        self.assertEqual(g.endpoint("[::1]:5555"), "[::1]:5555")

    def test_selector_rejects_argument_and_shell_injection(self):
        for serial in ("--help", "-s", "A;touch", "A$(id)", "A`id`", "A\nB", "A B", "A/../../B", "1.2.3.4:5555;id"):
            with self.subTest(serial=serial), self.assertRaises(g.GuardianError):
                g.valid_selector(serial)
        self.assertEqual(g.valid_selector("USB-A_01"), "USB-A_01")

    def test_devices_preserves_offline_and_unauthorized(self):
        self.assertEqual(g.parse_devices("List of devices attached\nA unauthorized\nB offline\nC device product:x\n"), {"A": "unauthorized", "B": "offline", "C": "device"})


class ActivationTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.path = Path(self.temp.name) / "private" / "devices.json"
        self.serial = "USB123"
        self.runner = FakeRunner({
            ("devices", "-l"): g.Result(out="List of devices attached\nUSB123 device\nOTHER device\n"),
            ("-s", self.serial, "shell", "pm", "path", g.PACKAGE): g.Result(out="package:/data/app/example/base.apk\n"),
            probe(self.serial): g.Result(out="REAL123\n"),
            ("-s", self.serial, "shell", "getprop", "persist.adb.wifi.guid"): g.Result(out="GUID123\n"),
            ("-s", self.serial, "shell", "getprop", "ro.product.model"): g.Result(out="iQOO 13\n"),
            ("-s", self.serial, "shell", "pm", "grant", g.PACKAGE, g.PERMISSION): g.Result(),
            ("-s", self.serial, "shell", "dumpsys", "package", g.PACKAGE): g.Result(out=g.PERMISSION + ": granted=true\n"),
        })

    def activate(self):
        return g.activate(self.runner, self.serial, self.path, emit=lambda _: None)

    def test_explicit_target_permission_and_private_config_usb_has_no_ip(self):
        saved = self.activate()
        self.assertEqual(saved["serialno"], "REAL123")
        self.assertEqual(saved["usb_serial"], "USB123")
        self.assertIsNone(saved["endpoint"])
        self.assertEqual(stat.S_IMODE(self.path.stat().st_mode), 0o600)
        self.assertEqual(stat.S_IMODE(self.path.parent.stat().st_mode), 0o700)
        self.assertEqual(g.load_config(self.path)["devices"], [saved])
        grant = [c for c in self.runner.commands if "grant" in c]
        self.assertEqual(grant, [("-s", "USB123", "shell", "pm", "grant", g.PACKAGE, g.PERMISSION)])

    def test_duplicate_activation_updates_same_record(self):
        self.activate()
        self.runner.replies[("-s", self.serial, "shell", "getprop", "ro.product.model")] = g.Result(out="iQOO updated")
        self.activate()
        saved = g.load_config(self.path)["devices"]
        self.assertEqual(len(saved), 1)
        self.assertEqual(saved[0]["model"], "iQOO updated")

    def test_other_unauthorized_device_does_not_block_selected_device(self):
        self.runner.replies[("devices", "-l")] = g.Result(out="USB123 device\nOTHER unauthorized\nTHIRD offline\n")
        self.assertEqual(self.activate()["serialno"], "REAL123")

    def test_empty_guid_can_activate_but_stays_explicitly_unknown(self):
        self.runner.replies[("-s", self.serial, "shell", "getprop", "persist.adb.wifi.guid")] = g.Result()
        self.assertIsNone(self.activate()["guid"])

    def test_reactivation_by_network_preserves_usb_identity(self):
        self.activate()
        old_serial = self.serial
        self.serial = "192.168.1.2:45678"
        replies = dict(self.runner.replies)
        for key, value in replies.items():
            if len(key) > 1 and key[:2] == ("-s", old_serial):
                self.runner.replies[("-s", self.serial) + key[2:]] = value
        self.runner.replies[("devices", "-l")] = g.Result(out=self.serial + " device\n")
        saved = self.activate()
        self.assertEqual(saved["endpoint"], self.serial)
        self.assertEqual(saved["usb_serial"], old_serial)
        self.assertEqual(len(g.load_config(self.path)["devices"]), 1)

    def test_missing_app_never_grants(self):
        self.runner.replies[("-s", self.serial, "shell", "pm", "path", g.PACKAGE)] = g.Result()
        with self.assertRaises(g.GuardianError):
            self.activate()
        self.assertFalse(self.path.exists())
        self.assertFalse(any("grant" in c for c in self.runner.commands))

    def test_vendor_denial_is_failure_not_registered(self):
        self.runner.replies[("-s", self.serial, "shell", "pm", "grant", g.PACKAGE, g.PERMISSION)] = g.Result(1, err="SecurityException: denied")
        with self.assertRaisesRegex(g.GuardianError, "授权失败"):
            self.activate()
        self.assertFalse(self.path.exists())

    def test_silent_vendor_denial_requires_verified_permission(self):
        self.runner.replies[("-s", self.serial, "shell", "dumpsys", "package", g.PACKAGE)] = g.Result(out=g.PERMISSION + ": granted=false")
        with self.assertRaisesRegex(g.GuardianError, "无法确认"):
            self.activate()
        self.assertFalse(self.path.exists())

    def test_offline_selected_device_does_not_fallback_to_other(self):
        self.runner.replies[("devices", "-l")] = g.Result(out="USB123 unauthorized\nOTHER device\n")
        with self.assertRaises(g.GuardianError):
            self.activate()
        self.assertEqual(self.runner.commands, [("devices", "-l")])

    def test_invalid_serial_runs_no_commands(self):
        with self.assertRaises(g.GuardianError):
            g.activate(self.runner, "--help", self.path)
        self.assertEqual(self.runner.calls, [])

    def test_symbolic_config_not_overwritten(self):
        target = Path(self.temp.name) / "target"
        target.write_text("keep", encoding="utf-8")
        link = Path(self.temp.name) / "link"
        link.symlink_to(target)
        with self.assertRaises(g.GuardianError):
            g.save_config(link, config(record()))
        self.assertEqual(target.read_text(encoding="utf-8"), "keep")


class WatchTests(unittest.TestCase):
    def watcher(self, runner, *records, interval=30):
        self.time = 0.0
        self.logs = []
        return g.Watcher(runner, config(*(records or (record(),))), interval, self.logs.append, clock=lambda: self.time)

    def test_only_matching_connect_service_never_pairing_or_other_phone(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({
            ("mdns", "services"): mdns(
                "adb-SERIAL-A _adb-tls-connect._tcp " + address,
                "adb-SERIAL-B _adb-tls-pairing._tcp 192.168.1.2:44444",
                "adb-SERIAL2-A _adb-tls-connect._tcp 192.168.1.3:33333",
                "adb-SERIAL _adb._tcp 192.168.1.2:5555"),
            ("connect", address): g.Result(out="connected to " + address),
            probe(address): g.Result(out="SERIAL\n"),
        })
        watcher = self.watcher(runner)
        self.assertEqual(watcher.run(once=True), 0)
        self.assertEqual([c for c in runner.commands if c[0] == "connect"], [("connect", address)])
        self.assertEqual(watcher.states["SERIAL"].status, "healthy")

    def test_unauthorized_mdns_transport_not_reconnected_by_its_endpoint(self):
        serial = "adb-SERIAL-A._adb-tls-connect._tcp"
        address = "192.168.1.2:33333"
        runner = FakeRunner({("devices", "-l"): g.Result(out=serial + " unauthorized\n"), ("mdns", "services"): mdns("adb-SERIAL-A _adb-tls-connect._tcp " + address)})
        watcher = self.watcher(runner)
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "unauthorized")
        self.assertEqual(runner.commands, [("devices", "-l"), ("mdns", "services")])

    def test_qr_pairing_port_is_rejected_as_saved_endpoint(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("mdns", "services"): mdns("studio-g@<xeYnap/ _adb-tls-pairing._tcp " + address)})
        watcher = self.watcher(runner, record(endpoint=address))
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "waiting")
        self.assertFalse(any(c[0] == "connect" for c in runner.commands))

    def test_missing_mdns_and_endpoint_never_guesses_ip(self):
        runner = FakeRunner({("mdns", "services"): g.Result(1, err="unknown command mdns")})
        watcher = self.watcher(runner)
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "waiting")
        self.assertEqual(runner.commands, [("devices", "-l"), ("mdns", "services")])

    def test_guid_discovery(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("mdns", "services"): mdns("adb-GUID123 _adb-tls-connect._tcp " + address), ("connect", address): g.Result(), probe(address): g.Result(out="SERIAL")})
        watcher = self.watcher(runner)
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "healthy")

    def test_port_changes_are_dynamically_rediscovered(self):
        old, new = "192.168.1.2:33333", "192.168.1.2:33334"
        runner = FakeRunner({("mdns", "services"): mdns("adb-SERIAL-A _adb-tls-connect._tcp " + old), ("connect", old): g.Result(), probe(old): g.Result(out="SERIAL"), ("connect", new): g.Result(), probe(new): g.Result(out="SERIAL")})
        watcher = self.watcher(runner)
        watcher.step()
        runner.replies[("mdns", "services")] = mdns("adb-SERIAL-B _adb-tls-connect._tcp " + new)
        self.time = 30
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].healthy_target, new)

    def test_reallocated_ip_is_not_accepted_and_second_candidate_verified(self):
        stale, valid = "192.168.1.2:33333", "192.168.1.3:33334"
        runner = FakeRunner({("mdns", "services"): mdns("adb-SERIAL-A _adb-tls-connect._tcp " + stale, "adb-SERIAL-B _adb-tls-connect._tcp " + valid), ("connect", stale): g.Result(), probe(stale): g.Result(out="OTHER"), ("connect", valid): g.Result(), probe(valid): g.Result(out="SERIAL")})
        watcher = self.watcher(runner)
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].healthy_target, valid)
        self.assertIn(probe(stale), runner.commands)
        self.assertIn(probe(valid), runner.commands)

    def test_known_endpoint_identity_mismatch_fails(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("connect", address): g.Result(), probe(address): g.Result(out="OTHER")})
        watcher = self.watcher(runner, record(endpoint=address))
        self.assertEqual(watcher.run(once=True), 1)
        self.assertEqual(watcher.states["SERIAL"].status, "identity_mismatch")

    def test_connected_candidate_requires_shell_not_get_state(self):
        serial = "adb-SERIAL-A._adb-tls-connect._tcp"
        runner = FakeRunner({("devices", "-l"): g.Result(out=serial + " device\nUNREGISTERED device\n"), probe(serial): g.Result(out="SERIAL")})
        watcher = self.watcher(runner)
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "healthy")
        self.assertIn(probe(serial), runner.commands)
        self.assertFalse(any(c[0] in ("connect", "get-state") for c in runner.commands))

    def test_healthy_endpoint_never_connects_again(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("devices", "-l"): g.Result(out=address + " device\n"), probe(address): g.Result(out="SERIAL")})
        watcher = self.watcher(runner, record(endpoint=address))
        watcher.step()
        self.time = 30
        watcher.step()
        self.assertFalse(any(c[0] == "connect" for c in runner.commands))
        self.assertEqual(len([line for line in self.logs if json.loads(line).get("serialno")]), 1)
        self.assertTrue(watcher.states["SERIAL"].updated_at)

    def test_verified_healthy_transport_stays_first_during_candidate_rotation(self):
        addresses = ["adb-SERIAL-X%d._adb-tls-connect._tcp" % i for i in range(6)]
        runner = FakeRunner({("devices", "-l"): g.Result(out="\n".join(a + " device" for a in addresses)), probe(addresses[0]): g.Result(out="SERIAL")})
        watcher = self.watcher(runner)
        for when in (0, 30, 60):
            self.time = when
            watcher.step()
        self.assertEqual(runner.commands.count(probe(addresses[0])), 3)
        self.assertFalse(any(c[0] == "connect" for c in runner.commands))

    def test_timeout_and_target_connect_have_bounded_calls_no_global_restart(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("devices", "-l"): g.Result(out=address + " offline\n"), probe(address): g.Result(1, timed_out=True), ("connect", address): g.Result(1, timed_out=True)})
        watcher = self.watcher(runner, record(endpoint=address))
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "timeout")
        self.assertEqual(runner.commands, [("devices", "-l"), ("mdns", "services"), probe(address), ("connect", address)])
        self.assertTrue(all(0 < timeout <= 6 for _, timeout in runner.calls))

    def test_unauthorized_transport_does_not_attempt_commands_and_recovers_manually(self):
        runner = FakeRunner({("devices", "-l"): g.Result(out="SERIAL unauthorized\n"), probe("SERIAL"): g.Result(out="SERIAL")})
        watcher = self.watcher(runner)
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "unauthorized")
        self.time = 30
        watcher.step()
        self.assertFalse(any(c[0] in ("-s", "connect") for c in runner.commands))
        runner.replies[("devices", "-l")] = g.Result(out="SERIAL device\n")
        self.time = 90
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "healthy")

    def test_authentication_failure_stops_automatic_connect_retries(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("connect", address): g.Result(1, err="failed to authenticate to " + address)})
        watcher = self.watcher(runner, record(endpoint=address))
        for when in (0, 30, 90, 210):
            self.time = when
            watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "unauthorized")
        self.assertEqual(runner.commands.count(("connect", address)), 1)

    def test_failure_backoff_does_not_suppress_other_devices(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("devices", "-l"): g.Result(out="HEALTHY device\n"), ("connect", address): g.Result(1, err="connection refused"), probe("HEALTHY"): g.Result(out="HEALTHY")})
        watcher = self.watcher(runner, record(endpoint=address), record("HEALTHY", guid=None))
        for when in (0, 30, 60, 90):
            self.time = when
            watcher.step()
        self.assertEqual(runner.commands.count(("connect", address)), 3)
        self.assertEqual(runner.commands.count(probe("HEALTHY")), 4)
        self.assertEqual(watcher.states["SERIAL"].next_due, 210)
        self.assertEqual(watcher.states["HEALTHY"].failures, 0)

    def test_missing_service_waits_without_connect_and_logs_only_changes(self):
        runner = FakeRunner()
        watcher = self.watcher(runner)
        for when in (0, 30, 90):
            self.time = when
            watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "waiting")
        self.assertEqual(len(self.logs), 2)  # 一条发现状态，一条设备状态。
        self.assertFalse(any(c[0] == "connect" for c in runner.commands))

    def test_old_adb_unsupported_mdns_uses_known_endpoint(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("version",): g.Result(out="Version 29.0.0-test"), ("mdns", "services"): g.Result(1, err="unknown command mdns"), ("connect", address): g.Result(), probe(address): g.Result(out="SERIAL")})
        watcher = self.watcher(runner, record(endpoint=address))
        self.assertEqual(watcher.run(once=True), 0)
        self.assertTrue(any("版本较旧" in line for line in self.logs))
        self.assertTrue(any("mDNS 不可用" in line for line in self.logs))

    def test_pairing_endpoint_never_used_even_if_saved(self):
        address = "192.168.1.2:33333"
        runner = FakeRunner({("mdns", "services"): mdns("adb-SERIAL-A _adb-tls-pairing._tcp " + address)})
        watcher = self.watcher(runner, record(endpoint=address))
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "waiting")
        self.assertFalse(any(c[0] == "connect" for c in runner.commands))

    def test_more_than_four_candidates_bounded_and_rotated(self):
        addresses = ["192.168.1.%d:33333" % i for i in range(10, 16)]
        replies = {("mdns", "services"): mdns(*("adb-SERIAL-X%d _adb-tls-connect._tcp %s" % (i, a) for i, a in enumerate(addresses)))}
        replies.update({("connect", address): g.Result(1, err="connection refused") for address in addresses})
        runner = FakeRunner(replies)
        watcher = self.watcher(runner)
        watcher.step()
        self.assertEqual(len([c for c in runner.commands if c[0] == "connect"]), 4)
        self.time = 30
        watcher.step()
        connected = {c[1] for c in runner.commands if c[0] == "connect"}
        self.assertEqual(connected, set(addresses))

    def test_device_discovery_failure_never_uses_stale_candidates(self):
        runner = FakeRunner({("devices", "-l"): g.Result(1, timed_out=True)})
        watcher = self.watcher(runner, record(endpoint="192.168.1.2:33333"))
        watcher.step()
        self.assertEqual(watcher.states["SERIAL"].status, "discovery_error")
        self.assertEqual(runner.commands, [("devices", "-l"), ("mdns", "services")])


class ConfigAndRunnerTests(unittest.TestCase):
    def test_config_validation_rejects_injection_and_duplicates(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "devices.json"
            for data in (config(record("--help")), config(record(endpoint="host;id:1234")), config(record(), record()), config(record(usb_serial="x;id")), {"version": 2, "devices": []}):
                path.write_text(json.dumps(data), encoding="utf-8")
                with self.assertRaises(g.GuardianError):
                    g.load_config(path)

    def test_runner_uses_argument_array_timeout_and_no_environment_override(self):
        with mock.patch.object(g.shutil, "which", return_value="/tmp/platform tools/adb"), mock.patch.object(g.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "ok", "")) as run:
            runner = g.Runner("/tmp/platform tools/adb")
            runner.run(["-s", "SERIAL", "shell", "getprop", "ro.serialno"])
            args, kwargs = run.call_args
            self.assertEqual(args[0], [str(Path("/tmp/platform tools/adb").resolve()), "-s", "SERIAL", "shell", "getprop", "ro.serialno"])
            self.assertEqual(kwargs["timeout"], 6)
            self.assertNotIn("env", kwargs)
            self.assertNotIn("shell", kwargs)
            self.assertEqual(kwargs["stdin"], subprocess.DEVNULL)

    def test_runner_timeout_is_sanitized(self):
        with mock.patch.object(g.shutil, "which", return_value="/tmp/adb"), mock.patch.object(g.subprocess, "run", side_effect=subprocess.TimeoutExpired("adb", 6, output=b"not logged")):
            result = g.Runner().run(["devices"])
        self.assertTrue(result.timed_out)
        self.assertEqual(result.out, "")

    def test_cli_requires_serial_and_interval_is_finite(self):
        with contextlib.redirect_stderr(io.StringIO()), self.assertRaises(SystemExit):
            g.build_parser().parse_args(["activate"])
        for interval in (0, -1, float("inf"), float("nan"), 3601):
            with self.assertRaises(g.GuardianError):
                g.Watcher(FakeRunner(), config(record()), interval)

    def test_ctrl_c_exits_without_device_mutation(self):
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / "devices.json"
            g.save_config(path, config(record()))
            with mock.patch.object(g, "Runner", return_value=FakeRunner()), mock.patch.object(g.Watcher, "run", side_effect=KeyboardInterrupt), contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(g.main(["watch", "--config", str(path)]), 130)


if __name__ == "__main__":
    unittest.main()
