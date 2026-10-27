"""Regression controls for simulator compatibility and preparation receipts."""
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

SCRIPT = Path(__file__).resolve().parents[1] / "run-ios-m35-journeys.py"
SPEC = importlib.util.spec_from_file_location("ios_m35_runner", SCRIPT)
RUNNER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(RUNNER)


def phone(number):
    return {"identifier": f"phone-{number}", "name": f"iPhone {number} Pro", "productFamily": "iPhone"}


def runtime(version="27.1", supported=None, available=True):
    return {"identifier": f"com.apple.CoreSimulator.SimRuntime.iOS-{version.replace('.', '-')}",
            "version": version, "isAvailable": available,
            "supportedDeviceTypes": [phone(18)] if supported is None else supported}


class StopAfterCreate(Exception):
    pass


class RunnerPreparationTests(unittest.TestCase):
    def run_preparation(self, runtimes, device_types, inventory_error=None):
        calls = []
        revision = (RUNNER.ROOT / "apple/sdk-revision").read_text().strip()

        def fake_command(args, timeout=60):
            calls.append(args)
            if args[0] == "git":
                return "" if "status" in args else revision
            if args[1:4] == ["simctl", "list", "runtimes"]:
                if inventory_error:
                    raise inventory_error
                return json.dumps(runtimes)
            if args[1:4] == ["simctl", "list", "devicetypes"]:
                return json.dumps(device_types)
            if args[1:3] == ["simctl", "create"]:
                # Representative inventory:27.1 supports18, while17 is installed
                # too. Reject the incompatible pair seen in the hosted failure.
                if args[-2] != "phone-18":
                    raise subprocess.CalledProcessError(147, args)
                return "owned-simulator"
            if args[1:3] == ["simctl", "boot"]:
                raise StopAfterCreate()
            raise AssertionError(f"Unexpected command: {args}")

        with tempfile.TemporaryDirectory(prefix="m35-runner-test-") as directory:
            output = Path(directory)
            with patch.object(RUNNER, "command", side_effect=fake_command), \
                    patch.object(RUNNER.subprocess, "run") as cleanup, \
                    patch.object(sys, "argv", [str(SCRIPT), "--output", directory,
                                              "--derived-data", str(output / "derived")]):
                try:
                    RUNNER.main()
                except Exception as error:
                    raised = error
                else:
                    self.fail("Preparation control must retain its failure")
                path = output / "results.json"
                receipt = json.loads(path.read_text()) if path.exists() else None
            return raised, receipt, calls, cleanup.call_args_list

    def test_runtime_advertised_phone_wins_over_installed_old_model(self):
        error, receipt, calls, cleanup = self.run_preparation(
            {"runtimes": [runtime()]}, {"devicetypes": [phone(17), phone(18)]})
        self.assertIsInstance(error, StopAfterCreate)
        creates = [args for args in calls if args[1:3] == ["simctl", "create"]]
        self.assertEqual(len(creates), 1)
        self.assertEqual(creates[0][-2:], ["phone-18", runtime()["identifier"]])
        self.assertEqual(receipt["status"], "failed")
        self.assertEqual(receipt["failure"], {"phase": "boot", "kind": "StopAfterCreate"})
        self.assertEqual(len(cleanup), 2)

    def test_inventory_timeout_is_receipted_without_sensitive_error_text(self):
        secret = "sensitive-inventory-detail"
        error, receipt, calls, cleanup = self.run_preparation(
            {}, {}, subprocess.TimeoutExpired(secret, 60, output=secret))
        self.assertIsInstance(error, subprocess.TimeoutExpired)
        self.assertEqual(receipt["failure"], {"phase": "prepare.runtimes", "kind": "TimeoutExpired"})
        self.assertNotIn(secret, json.dumps(receipt))
        self.assertEqual(receipt["journeys"], {})
        self.assertEqual(cleanup, [])
        self.assertFalse(any(args[1:3] == ["simctl", "create"] for args in calls))

    def test_no_compatible_installed_phone_fails_without_create_or_cleanup(self):
        error, receipt, calls, cleanup = self.run_preparation(
            {"runtimes": [runtime()]}, {"devicetypes": [phone(17)]})
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(receipt["failure"]["phase"], "prepare.select-device")
        self.assertEqual(cleanup, [])
        self.assertFalse(any(args[1:3] == ["simctl", "create"] for args in calls))

    def test_missing_runtime_inventory_is_receipted(self):
        error, receipt, _, cleanup = self.run_preparation({}, {})
        self.assertIsInstance(error, KeyError)
        self.assertEqual(receipt["failure"]["phase"], "prepare.runtimes")
        self.assertEqual(cleanup, [])

    def test_missing_device_inventory_is_receipted(self):
        error, receipt, _, cleanup = self.run_preparation({"runtimes": [runtime()]}, {})
        self.assertIsInstance(error, KeyError)
        self.assertEqual(receipt["failure"]["phase"], "prepare.device-types")
        self.assertEqual(cleanup, [])

    def test_unavailable_or_pre27_runtime_is_not_used_as_fallback(self):
        error, receipt, calls, cleanup = self.run_preparation(
            {"runtimes": [runtime("26.0"), runtime(available=False)]}, {"devicetypes": [phone(18)]})
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(receipt["failure"]["phase"], "prepare.select-device")
        self.assertEqual(cleanup, [])
        self.assertFalse(any(args[1:3] == ["simctl", "create"] for args in calls))

    def test_missing_advertised_types_is_receipted(self):
        incomplete = runtime()
        del incomplete["supportedDeviceTypes"]
        error, receipt, _, cleanup = self.run_preparation(
            {"runtimes": [incomplete]}, {"devicetypes": [phone(18)]})
        self.assertIsInstance(error, KeyError)
        self.assertEqual(receipt["failure"]["phase"], "prepare.select-device")
        self.assertEqual(cleanup, [])

    def test_newest_runtime_without_compatible_phone_does_not_retry_older(self):
        error, receipt, calls, cleanup = self.run_preparation(
            {"runtimes": [runtime("27.0", [phone(17)]), runtime("27.1")]},
            {"devicetypes": [phone(17)]})
        self.assertIsInstance(error, RuntimeError)
        self.assertEqual(receipt["failure"]["phase"], "prepare.select-device")
        self.assertEqual(cleanup, [])
        self.assertFalse(any(args[1:3] == ["simctl", "create"] for args in calls))

    def test_creation_failure_is_receipted_without_cleaning_unowned_device(self):
        error, receipt, calls, cleanup = self.run_preparation(
            {"runtimes": [runtime(supported=[phone(17)])]}, {"devicetypes": [phone(17)]})
        self.assertIsInstance(error, subprocess.CalledProcessError)
        self.assertEqual(receipt["failure"], {"phase": "prepare.create-device", "kind": "CalledProcessError"})
        self.assertEqual(cleanup, [])
        self.assertEqual(sum(args[1:3] == ["simctl", "create"] for args in calls), 1)


if __name__ == "__main__":
    unittest.main()
