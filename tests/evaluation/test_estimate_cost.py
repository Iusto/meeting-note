from __future__ import annotations

from decimal import Decimal
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "scripts" / "estimate_cost.py"
SPEC = importlib.util.spec_from_file_location("estimate_cost", SCRIPT)
assert SPEC and SPEC.loader
estimate_cost = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(estimate_cost)


def assumptions() -> dict[str, object]:
    return {
        "priceVersion": "test-v1",
        "asOf": "test-assumption",
        "stt": {
            "baseUsdPerMinute": Decimal("0.01"),
            "diarizationUsdPerMinute": Decimal("0.002"),
            "keytermUsdPerMinute": Decimal("0.001"),
        },
        "llm": {
            "inputUsdPerMillionTokens": Decimal("2"),
            "outputUsdPerMillionTokens": Decimal("8"),
            "summary": {
                "inputTokensPerHour": Decimal("10000"),
                "outputTokensPerHour": Decimal("1000"),
            },
            "correction": {
                "inputTokensPerHour": Decimal("20000"),
                "outputTokensPerHour": Decimal("2000"),
            },
        },
        "scenariosHours": [Decimal("36"), Decimal("48"), Decimal("60")],
        "budgetUsd": Decimal("40"),
    }


class EstimateCostTest(unittest.TestCase):
    def test_independently_computed_default_scenarios(self) -> None:
        result = estimate_cost.calculate(assumptions())

        self.assertEqual(["36", "48", "60"], [item["hours"] for item in result["scenarios"]])
        scenario_36 = result["scenarios"][0]
        # STT: 36 * 60 * (0.01 + 0.002 + 0.001) = 28.08
        self.assertEqual("28.080000", scenario_36["stt"]["subtotalUsd"])
        # Summary: 36 * ((10,000 * 2 + 1,000 * 8) / 1,000,000) = 1.008
        self.assertEqual("1.008000", scenario_36["llm"]["summary"]["subtotalUsd"])
        # Correction: 36 * ((20,000 * 2 + 2,000 * 8) / 1,000,000) = 2.016
        self.assertEqual("2.016000", scenario_36["llm"]["correction"]["subtotalUsd"])
        self.assertEqual("31.104000", scenario_36["totalUsd"])
        self.assertEqual("8.896000", scenario_36["budget"]["remainingUsd"])
        self.assertEqual("0.000000", scenario_36["budget"]["overUsd"])

        scenario_60 = result["scenarios"][2]
        self.assertEqual("51.840000", scenario_60["totalUsd"])
        self.assertEqual("0.000000", scenario_60["budget"]["remainingUsd"])
        self.assertEqual("11.840000", scenario_60["budget"]["overUsd"])
        # Per-hour cost is 0.864, so USD 40 affords 46.296296... hours.
        self.assertEqual("46.296296", result["budget"]["affordableHours"])
        self.assertEqual(
            {
                "baseUsdPerMinute": "0.01",
                "diarizationUsdPerMinute": "0.002",
                "keytermUsdPerMinute": "0.001",
            },
            result["assumptions"]["stt"],
        )
        self.assertEqual("10000", result["assumptions"]["llm"]["summary"]["inputTokensPerHour"])

    def test_zero_rates_and_no_budget(self) -> None:
        document = assumptions()
        document["stt"] = {"baseUsdPerMinute": Decimal(0)}
        document["llm"] = {
            "inputUsdPerMillionTokens": Decimal(0),
            "outputUsdPerMillionTokens": Decimal(0),
            "summary": {"inputTokensPerHour": Decimal(0), "outputTokensPerHour": Decimal(0)},
            "correction": {"inputTokensPerHour": Decimal(0), "outputTokensPerHour": Decimal(0)},
        }
        document.pop("budgetUsd")
        document.pop("scenariosHours")

        result = estimate_cost.calculate(document)

        self.assertEqual(["36", "48", "60"], [item["hours"] for item in result["scenarios"]])
        self.assertTrue(all(item["totalUsd"] == "0.000000" for item in result["scenarios"]))
        self.assertNotIn("budget", result)

    def test_exact_budget_boundary(self) -> None:
        document = assumptions()
        document["scenariosHours"] = [Decimal("1")]
        document["budgetUsd"] = Decimal("0.864")

        result = estimate_cost.calculate(document)

        self.assertEqual("0.000000", result["scenarios"][0]["budget"]["remainingUsd"])
        self.assertEqual("0.000000", result["scenarios"][0]["budget"]["overUsd"])
        self.assertEqual("1.000000", result["budget"]["affordableHours"])

    def test_costs_round_up_but_remaining_and_affordable_hours_round_down(self) -> None:
        document = assumptions()
        document["stt"] = {"baseUsdPerMinute": Decimal("0.000000001")}
        document["llm"] = {
            "inputUsdPerMillionTokens": Decimal(0),
            "outputUsdPerMillionTokens": Decimal(0),
            "summary": {"inputTokensPerHour": Decimal(0), "outputTokensPerHour": Decimal(0)},
            "correction": {"inputTokensPerHour": Decimal(0), "outputTokensPerHour": Decimal(0)},
        }
        document["scenariosHours"] = [Decimal("1")]
        document["budgetUsd"] = Decimal("0.0000605")

        result = estimate_cost.calculate(document)

        scenario = result["scenarios"][0]
        self.assertEqual("0.000001", scenario["totalUsd"])
        # Raw remaining is 0.00006044 and is shown conservatively (rounded down).
        self.assertEqual("0.000060", scenario["budget"]["remainingUsd"])
        self.assertEqual("0.000060", result["budget"]["amountUsd"])
        self.assertEqual("1008.333333", result["budget"]["affordableHours"])

    def test_rejects_invalid_values_and_shape(self) -> None:
        invalid_cases = []
        for bad in (True, Decimal("-1"), Decimal("NaN"), Decimal("Infinity"), 1.5):
            document = assumptions()
            document["stt"] = {"baseUsdPerMinute": bad}
            invalid_cases.append(document)
        unknown = assumptions()
        unknown["unexpected"] = Decimal(1)
        invalid_cases.append(unknown)
        missing = assumptions()
        del missing["llm"]
        invalid_cases.append(missing)
        empty_scenarios = assumptions()
        empty_scenarios["scenariosHours"] = []
        invalid_cases.append(empty_scenarios)

        for document in invalid_cases:
            with self.subTest(document=document):
                with self.assertRaises(estimate_cost.AssumptionError):
                    estimate_cost.calculate(document)

    def test_rejects_finite_values_outside_precision_bounds(self) -> None:
        for bad in (
            Decimal("1e100"),
            Decimal("0.0000000000001"),
            Decimal("0.12345678901200000000000000001"),
            Decimal("1e-1000000"),
        ):
            document = assumptions()
            document["stt"] = {"baseUsdPerMinute": bad}
            with self.subTest(bad=bad):
                with self.assertRaises(estimate_cost.AssumptionError):
                    estimate_cost.calculate(document)

    def test_cli_is_deterministic_and_rejects_duplicate_keys(self) -> None:
        cli_document = {
            "priceVersion": "cli-test",
            "asOf": "test-only",
            "stt": {"baseUsdPerMinute": 0.01},
            "llm": {
                "inputUsdPerMillionTokens": 0,
                "outputUsdPerMillionTokens": 0,
                "summary": {"inputTokensPerHour": 0, "outputTokensPerHour": 0},
                "correction": {"inputTokensPerHour": 0, "outputTokensPerHour": 0},
            },
            "scenariosHours": [1],
        }
        with tempfile.TemporaryDirectory() as temp_dir:
            valid_path = Path(temp_dir) / "valid.json"
            valid_path.write_text(json.dumps(cli_document), encoding="utf-8")
            first = subprocess.run(
                [sys.executable, str(SCRIPT), str(valid_path)],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            second = subprocess.run(
                [sys.executable, str(SCRIPT), str(valid_path)],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            duplicate_path = Path(temp_dir) / "duplicate.json"
            duplicate_path.write_text('{"priceVersion":"a","priceVersion":"b"}', encoding="utf-8")
            duplicate = subprocess.run(
                [sys.executable, str(SCRIPT), str(duplicate_path)],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            huge_path = Path(temp_dir) / "huge.json"
            huge_path.write_text(json.dumps(cli_document).replace("0.01", "1e100"), encoding="utf-8")
            huge = subprocess.run(
                [sys.executable, str(SCRIPT), str(huge_path)],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )
            invalid_utf8_path = Path(temp_dir) / "invalid-utf8.json"
            invalid_utf8_path.write_bytes(b"\xff\xfe")
            invalid_utf8 = subprocess.run(
                [sys.executable, str(SCRIPT), str(invalid_utf8_path)],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
            )

        self.assertEqual(0, first.returncode, first.stderr)
        self.assertEqual(first.stdout, second.stdout)
        self.assertEqual("0.600000", json.loads(first.stdout)["scenarios"][0]["totalUsd"])
        self.assertEqual(2, duplicate.returncode)
        self.assertIn("duplicate key", duplicate.stderr)
        self.assertEqual("", duplicate.stdout)
        self.assertEqual(2, huge.returncode)
        self.assertIn("must not exceed", huge.stderr)
        self.assertNotIn("Traceback", huge.stderr)
        self.assertEqual(2, invalid_utf8.returncode)
        self.assertIn("cannot read assumptions", invalid_utf8.stderr)
        self.assertNotIn("Traceback", invalid_utf8.stderr)


if __name__ == "__main__":
    unittest.main()
