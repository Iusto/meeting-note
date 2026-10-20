#!/usr/bin/env python3
"""Estimate monthly meeting AI costs from explicit, user-supplied assumptions.

This planning tool performs no network calls and does not discover provider prices.
It is not connected to the application's usage ledger or provider billing.
"""

from __future__ import annotations

import argparse
from decimal import Decimal, DecimalException, InvalidOperation, ROUND_CEILING, ROUND_FLOOR, localcontext
import json
from pathlib import Path
import sys
from typing import Any, Iterable


SIX_PLACES = Decimal("0.000001")
MILLION = Decimal("1000000")
DEFAULT_SCENARIOS = (Decimal("36"), Decimal("48"), Decimal("60"))
MAX_INPUT_VALUE = Decimal("1000000000")
MAX_DECIMAL_PLACES = 12


class AssumptionError(ValueError):
    """Raised when an assumption document is incomplete or unsafe to calculate."""


class DuplicateKeyError(AssumptionError):
    """Raised when a JSON object contains a duplicate key."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise DuplicateKeyError(f"duplicate key: {key}")
        result[key] = value
    return result


def load_assumptions(path: Path) -> dict[str, Any]:
    try:
        with path.open("r", encoding="utf-8") as source:
            document = json.load(
                source,
                parse_float=Decimal,
                parse_int=Decimal,
                parse_constant=lambda value: (_ for _ in ()).throw(
                    AssumptionError(f"non-finite JSON number: {value}")
                ),
                object_pairs_hook=_reject_duplicate_keys,
            )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise AssumptionError(f"cannot read assumptions: {exc}") from exc
    if not isinstance(document, dict):
        raise AssumptionError("assumptions must be a JSON object")
    return document


def _check_keys(
    value: dict[str, Any], *, path: str, required: Iterable[str], optional: Iterable[str] = ()
) -> None:
    required_set = set(required)
    allowed = required_set | set(optional)
    missing = sorted(required_set - value.keys())
    unknown = sorted(value.keys() - allowed)
    if missing:
        raise AssumptionError(f"{path} missing required field(s): {', '.join(missing)}")
    if unknown:
        raise AssumptionError(f"{path} contains unknown field(s): {', '.join(unknown)}")


def _object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise AssumptionError(f"{path} must be an object")
    return value


def _text(value: Any, path: str) -> str:
    if not isinstance(value, str) or not value.strip():
        raise AssumptionError(f"{path} must be a non-empty string")
    return value


def _nonnegative_decimal(value: Any, path: str) -> Decimal:
    if isinstance(value, bool) or not isinstance(value, (Decimal, int, str)):
        raise AssumptionError(f"{path} must be a decimal number")
    try:
        number = Decimal(value)
    except (InvalidOperation, ValueError) as exc:
        raise AssumptionError(f"{path} must be a decimal number") from exc
    if not number.is_finite():
        raise AssumptionError(f"{path} must be finite")
    if number < 0:
        raise AssumptionError(f"{path} must be nonnegative")
    if number > MAX_INPUT_VALUE:
        raise AssumptionError(f"{path} must not exceed {MAX_INPUT_VALUE}")
    if number != 0:
        exact = number.as_tuple()
        exponent = exact.exponent
        trailing_zeros = 0
        for digit in reversed(exact.digits):
            if digit != 0 or exponent + trailing_zeros >= 0:
                break
            trailing_zeros += 1
        effective_exponent = exponent + trailing_zeros
        if effective_exponent < -MAX_DECIMAL_PLACES:
            raise AssumptionError(f"{path} must have at most {MAX_DECIMAL_PLACES} decimal places")
    return number


def _positive_decimal(value: Any, path: str) -> Decimal:
    number = _nonnegative_decimal(value, path)
    if number == 0:
        raise AssumptionError(f"{path} must be greater than zero")
    return number


def _ceil_cost(value: Decimal) -> str:
    return format(value.quantize(SIX_PLACES, rounding=ROUND_CEILING), "f")


def _floor_value(value: Decimal) -> str:
    return format(value.quantize(SIX_PLACES, rounding=ROUND_FLOOR), "f")


def _plain_decimal(value: Decimal) -> str:
    return format(value, "f")


def _normalized_decimal(value: Decimal) -> str:
    if value == 0:
        return "0"
    return format(value.normalize(), "f")


def _workload(value: Any, path: str) -> dict[str, Decimal]:
    document = _object(value, path)
    fields = ("inputTokensPerHour", "outputTokensPerHour")
    _check_keys(document, path=path, required=fields)
    return {field: _nonnegative_decimal(document[field], f"{path}.{field}") for field in fields}


def validate_assumptions(document: dict[str, Any]) -> dict[str, Any]:
    _check_keys(
        document,
        path="root",
        required=("priceVersion", "asOf", "stt", "llm"),
        optional=("scenariosHours", "budgetUsd"),
    )

    stt = _object(document["stt"], "stt")
    _check_keys(
        stt,
        path="stt",
        required=("baseUsdPerMinute",),
        optional=("diarizationUsdPerMinute", "keytermUsdPerMinute"),
    )
    stt_rates = {
        "baseUsdPerMinute": _nonnegative_decimal(stt["baseUsdPerMinute"], "stt.baseUsdPerMinute"),
        "diarizationUsdPerMinute": _nonnegative_decimal(
            stt.get("diarizationUsdPerMinute", 0), "stt.diarizationUsdPerMinute"
        ),
        "keytermUsdPerMinute": _nonnegative_decimal(
            stt.get("keytermUsdPerMinute", 0), "stt.keytermUsdPerMinute"
        ),
    }

    llm = _object(document["llm"], "llm")
    _check_keys(
        llm,
        path="llm",
        required=(
            "inputUsdPerMillionTokens",
            "outputUsdPerMillionTokens",
            "summary",
            "correction",
        ),
    )
    llm_values = {
        "inputUsdPerMillionTokens": _nonnegative_decimal(
            llm["inputUsdPerMillionTokens"], "llm.inputUsdPerMillionTokens"
        ),
        "outputUsdPerMillionTokens": _nonnegative_decimal(
            llm["outputUsdPerMillionTokens"], "llm.outputUsdPerMillionTokens"
        ),
        "summary": _workload(llm["summary"], "llm.summary"),
        "correction": _workload(llm["correction"], "llm.correction"),
    }

    if "scenariosHours" in document:
        scenario_values = document["scenariosHours"]
        if isinstance(scenario_values, (str, bytes, bool)) or not isinstance(scenario_values, list):
            raise AssumptionError("scenariosHours must be an array")
    else:
        scenario_values = list(DEFAULT_SCENARIOS)
    if not scenario_values:
        raise AssumptionError("scenariosHours must not be empty")
    scenarios = [
        _positive_decimal(value, f"scenariosHours[{index}]")
        for index, value in enumerate(scenario_values)
    ]

    budget = None
    if "budgetUsd" in document:
        budget = _nonnegative_decimal(document["budgetUsd"], "budgetUsd")

    return {
        "priceVersion": _text(document["priceVersion"], "priceVersion"),
        "asOf": _text(document["asOf"], "asOf"),
        "stt": stt_rates,
        "llm": llm_values,
        "scenariosHours": scenarios,
        "budgetUsd": budget,
    }


def _llm_workload_cost(
    hours: Decimal,
    workload: dict[str, Decimal],
    input_rate: Decimal,
    output_rate: Decimal,
) -> tuple[Decimal, Decimal, Decimal]:
    input_cost = hours * workload["inputTokensPerHour"] * input_rate / MILLION
    output_cost = hours * workload["outputTokensPerHour"] * output_rate / MILLION
    return input_cost, output_cost, input_cost + output_cost


def calculate(document: dict[str, Any]) -> dict[str, Any]:
    assumptions = validate_assumptions(document)
    # The accepted bounds fit comfortably at this precision, preserving the
    # supplied decimal arithmetic before values are formatted to six places.
    with localcontext() as context:
        context.prec = 80
        return _calculate(assumptions)


def _calculate(assumptions: dict[str, Any]) -> dict[str, Any]:
    stt = assumptions["stt"]
    llm = assumptions["llm"]
    budget: Decimal | None = assumptions["budgetUsd"]
    scenarios: list[dict[str, Any]] = []
    cost_per_hour: Decimal | None = None

    for hours in assumptions["scenariosHours"]:
        minutes = hours * Decimal(60)
        stt_base = minutes * stt["baseUsdPerMinute"]
        stt_diarization = minutes * stt["diarizationUsdPerMinute"]
        stt_keyterm = minutes * stt["keytermUsdPerMinute"]
        stt_total = stt_base + stt_diarization + stt_keyterm

        summary_input, summary_output, summary_total = _llm_workload_cost(
            hours,
            llm["summary"],
            llm["inputUsdPerMillionTokens"],
            llm["outputUsdPerMillionTokens"],
        )
        correction_input, correction_output, correction_total = _llm_workload_cost(
            hours,
            llm["correction"],
            llm["inputUsdPerMillionTokens"],
            llm["outputUsdPerMillionTokens"],
        )
        llm_total = summary_total + correction_total
        total = stt_total + llm_total
        if cost_per_hour is None:
            cost_per_hour = total / hours

        scenario: dict[str, Any] = {
            "hours": _plain_decimal(hours),
            "stt": {
                "baseUsd": _ceil_cost(stt_base),
                "diarizationUsd": _ceil_cost(stt_diarization),
                "keytermUsd": _ceil_cost(stt_keyterm),
                "subtotalUsd": _ceil_cost(stt_total),
            },
            "llm": {
                "summary": {
                    "inputUsd": _ceil_cost(summary_input),
                    "outputUsd": _ceil_cost(summary_output),
                    "subtotalUsd": _ceil_cost(summary_total),
                },
                "correction": {
                    "inputUsd": _ceil_cost(correction_input),
                    "outputUsd": _ceil_cost(correction_output),
                    "subtotalUsd": _ceil_cost(correction_total),
                },
                "subtotalUsd": _ceil_cost(llm_total),
            },
            "totalUsd": _ceil_cost(total),
        }
        if budget is not None:
            remaining = max(budget - total, Decimal(0))
            over = max(total - budget, Decimal(0))
            scenario["budget"] = {
                "remainingUsd": _floor_value(remaining),
                "overUsd": _ceil_cost(over),
            }
        scenarios.append(scenario)

    result: dict[str, Any] = {
        "assumptions": {
            "priceVersion": assumptions["priceVersion"],
            "asOf": assumptions["asOf"],
            "stt": {
                field: _normalized_decimal(value)
                for field, value in assumptions["stt"].items()
            },
            "llm": {
                "inputUsdPerMillionTokens": _normalized_decimal(
                    assumptions["llm"]["inputUsdPerMillionTokens"]
                ),
                "outputUsdPerMillionTokens": _normalized_decimal(
                    assumptions["llm"]["outputUsdPerMillionTokens"]
                ),
                "summary": {
                    field: _normalized_decimal(value)
                    for field, value in assumptions["llm"]["summary"].items()
                },
                "correction": {
                    field: _normalized_decimal(value)
                    for field, value in assumptions["llm"]["correction"].items()
                },
            },
        },
        "scenarios": scenarios,
    }
    if budget is not None:
        # A budget is available capacity, so rounding it down avoids overstating it.
        result["budget"] = {"amountUsd": _floor_value(budget)}
        if cost_per_hour is not None and cost_per_hour > 0:
            result["budget"]["affordableHours"] = _floor_value(budget / cost_per_hour)
    return result


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    result.add_argument("assumptions", type=Path, help="JSON file containing explicit price assumptions")
    return result


def main(argv: list[str] | None = None) -> int:
    args = parser().parse_args(argv)
    try:
        result = calculate(load_assumptions(args.assumptions))
    except (AssumptionError, DecimalException) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 2
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
