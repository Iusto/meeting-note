#!/usr/bin/env python3
"""Evaluate explicitly aligned POC transcript annotations without provider calls."""

from __future__ import annotations

import argparse
import json
import math
import sys
import unicodedata
from pathlib import Path
from typing import Any, Sequence


SCHEMA_VERSION = 1
ALLOWED_ORIGINS = {"synthetic/offline", "human-aligned-poc"}


class EvaluationError(ValueError):
    """Raised when an input cannot be evaluated without inventing data."""


def _reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise EvaluationError(f"duplicate key: {key}")
        result[key] = value
    return result


def _reject_nonfinite(value: str) -> None:
    raise EvaluationError(f"non-finite JSON number is not allowed: {value}")


def _object(value: Any, path: str) -> dict[str, Any]:
    if not isinstance(value, dict):
        raise EvaluationError(f"{path} must be an object")
    return value


def _array(value: Any, path: str) -> list[Any]:
    if not isinstance(value, list):
        raise EvaluationError(f"{path} must be an array")
    return value


def _string(value: Any, path: str, *, allow_empty: bool = True) -> str:
    if not isinstance(value, str):
        raise EvaluationError(f"{path} must be a string")
    normalized = unicodedata.normalize("NFC", value)
    if not allow_empty and not normalized.strip():
        raise EvaluationError(f"{path} must not be empty")
    return normalized


def _optional_label(item: dict[str, Any], name: str, path: str) -> str | None:
    if name not in item or item[name] is None:
        return None
    return _string(item[name], f"{path}.{name}", allow_empty=False)


def _number(value: Any, path: str) -> float:
    if isinstance(value, bool) or not isinstance(value, (int, float)):
        raise EvaluationError(f"{path} must be a number")
    try:
        result = float(value)
    except OverflowError as error:
        raise EvaluationError(f"{path} must be within the finite numeric range") from error
    if not math.isfinite(result) or result < 0:
        raise EvaluationError(f"{path} must be finite and non-negative")
    return result


def _count(value: Any, path: str) -> int:
    if isinstance(value, bool) or not isinstance(value, int) or value < 0:
        raise EvaluationError(f"{path} must be a non-negative integer")
    return value


def _edit_counts(reference: Sequence[str], hypothesis: Sequence[str]) -> dict[str, int]:
    """Return deterministic Levenshtein substitution/deletion/insertion counts."""
    previous: list[tuple[int, int, int, int]] = [
        (index, 0, 0, index) for index in range(len(hypothesis) + 1)
    ]
    for ref_index in range(1, len(reference) + 1):
        row: list[tuple[int, int, int, int]] = [(ref_index, 0, ref_index, 0)]
        for hyp_index in range(1, len(hypothesis) + 1):
            if reference[ref_index - 1] == hypothesis[hyp_index - 1]:
                row.append(previous[hyp_index - 1])
                continue
            diagonal = previous[hyp_index - 1]
            deletion = previous[hyp_index]
            insertion = row[hyp_index - 1]
            candidates = (
                (diagonal[0] + 1, diagonal[1] + 1, diagonal[2], diagonal[3], 0),
                (deletion[0] + 1, deletion[1], deletion[2] + 1, deletion[3], 1),
                (insertion[0] + 1, insertion[1], insertion[2], insertion[3] + 1, 2),
            )
            best = min(candidates, key=lambda candidate: (candidate[0], candidate[4]))
            row.append(best[:4])
        previous = row
    distance, substitutions, deletions, insertions = previous[-1]
    return {
        "referenceUnits": len(reference),
        "hypothesisUnits": len(hypothesis),
        "substitutions": substitutions,
        "deletions": deletions,
        "insertions": insertions,
        "edits": distance,
    }


def _add_counts(total: dict[str, int], counts: dict[str, int]) -> None:
    for key, value in counts.items():
        total[key] += value


def _metric(total: dict[str, int]) -> dict[str, int | float | None]:
    result: dict[str, int | float | None] = dict(total)
    denominator = total["referenceUnits"]
    result["rate"] = total["edits"] / denominator if denominator else None
    return result


def _nearest_rank(samples: list[float], percentile: float) -> float | None:
    if not samples:
        return None
    ordered = sorted(samples)
    rank = max(1, math.ceil(percentile * len(ordered)))
    return ordered[rank - 1]


def evaluate(payload: Any) -> dict[str, Any]:
    root = _object(payload, "input")
    schema_version = root.get("schemaVersion")
    if isinstance(schema_version, bool) or not isinstance(schema_version, int) or schema_version != SCHEMA_VERSION:
        raise EvaluationError(f"schemaVersion must be {SCHEMA_VERSION}")
    origin = _string(root.get("measurementOrigin"), "measurementOrigin", allow_empty=False)
    if origin not in ALLOWED_ORIGINS:
        allowed = ", ".join(sorted(ALLOWED_ORIGINS))
        raise EvaluationError(f"measurementOrigin must be one of: {allowed}")

    segments = _array(root.get("segments"), "segments")
    latency_values = _array(root.get("latencySamplesMs", []), "latencySamplesMs")
    latencies = [_number(value, f"latencySamplesMs[{index}]") for index, value in enumerate(latency_values)]

    empty_counts = {
        "referenceUnits": 0,
        "hypothesisUnits": 0,
        "substitutions": 0,
        "deletions": 0,
        "insertions": 0,
        "edits": 0,
    }
    character_total = dict(empty_counts)
    word_total = dict(empty_counts)
    term_reference = term_predicted = term_true_positive = 0
    term_annotation_count = 0
    speaker_reference = speaker_predicted = speaker_comparable = 0
    speaker_matches = speaker_disagreements = 0
    seen_ids: set[str] = set()

    for index, raw_segment in enumerate(segments):
        path = f"segments[{index}]"
        segment = _object(raw_segment, path)
        segment_id = _string(segment.get("id"), f"{path}.id", allow_empty=False)
        if segment_id in seen_ids:
            raise EvaluationError(f"duplicate segment id: {segment_id}")
        seen_ids.add(segment_id)

        reference = _string(segment.get("reference"), f"{path}.reference")
        hypothesis = _string(segment.get("hypothesis"), f"{path}.hypothesis")
        _add_counts(character_total, _edit_counts(list(reference), list(hypothesis)))
        _add_counts(word_total, _edit_counts(reference.split(), hypothesis.split()))

        if "startMs" in segment:
            start = _number(segment["startMs"], f"{path}.startMs")
        else:
            start = None
        if "endMs" in segment:
            end = _number(segment["endMs"], f"{path}.endMs")
        else:
            end = None
        if (start is None) != (end is None):
            raise EvaluationError(f"{path}.startMs and endMs must be supplied together")
        if start is not None and end is not None and end < start:
            raise EvaluationError(f"{path}.endMs must be greater than or equal to startMs")

        reference_speaker = _optional_label(segment, "referenceSpeaker", path)
        predicted_speaker = _optional_label(segment, "predictedSpeaker", path)
        speaker_reference += reference_speaker is not None
        speaker_predicted += predicted_speaker is not None
        if reference_speaker is not None and predicted_speaker is not None:
            speaker_comparable += 1
            if reference_speaker == predicted_speaker:
                speaker_matches += 1
            else:
                speaker_disagreements += 1

        raw_terms = _array(segment.get("termAnnotations", []), f"{path}.termAnnotations")
        seen_terms: set[str] = set()
        for term_index, raw_term in enumerate(raw_terms):
            term_path = f"{path}.termAnnotations[{term_index}]"
            annotation = _object(raw_term, term_path)
            term = _string(annotation.get("term"), f"{term_path}.term", allow_empty=False)
            if term in seen_terms:
                raise EvaluationError(f"duplicate term annotation in {segment_id}: {term}")
            seen_terms.add(term)
            reference_count = _count(annotation.get("referenceCount"), f"{term_path}.referenceCount")
            predicted_count = _count(annotation.get("predictedCount"), f"{term_path}.predictedCount")
            matched_count = _count(annotation.get("matchedCount"), f"{term_path}.matchedCount")
            if matched_count > min(reference_count, predicted_count):
                raise EvaluationError(
                    f"{term_path}.matchedCount cannot exceed referenceCount or predictedCount"
                )
            term_reference += reference_count
            term_predicted += predicted_count
            term_true_positive += matched_count
            term_annotation_count += 1

    term_false_positive = term_predicted - term_true_positive
    term_false_negative = term_reference - term_true_positive
    return {
        "schemaVersion": SCHEMA_VERSION,
        "measurementOrigin": origin,
        "counts": {
            "segments": len(segments),
            "latencySamples": len(latencies),
            "termAnnotations": term_annotation_count,
        },
        "transcript": {
            "normalization": {
                "unicode": "NFC",
                "caseSensitive": True,
                "cerIncludesWhitespaceAndPunctuation": True,
                "werTokenization": "Unicode whitespace split; punctuation remains attached",
                "aggregation": "micro",
            },
            "cer": _metric(character_total),
            "wer": _metric(word_total),
        },
        "keyterms": {
            "method": "explicit per-segment occurrence and human-matched counts; no text or semantic matching",
            "referenceOccurrences": term_reference,
            "predictedOccurrences": term_predicted,
            "truePositives": term_true_positive,
            "falsePositives": term_false_positive,
            "falseNegatives": term_false_negative,
            "precision": term_true_positive / term_predicted if term_predicted else None,
            "recall": term_true_positive / term_reference if term_reference else None,
        },
        "speakers": {
            "metricName": "aligned speaker-label disagreement (not DER)",
            "referenceAnnotatedSegments": speaker_reference,
            "predictedAnnotatedSegments": speaker_predicted,
            "comparableSegments": speaker_comparable,
            "missingReferenceLabels": len(segments) - speaker_reference,
            "missingPredictedLabelsForReference": speaker_reference - speaker_comparable,
            "matches": speaker_matches,
            "disagreements": speaker_disagreements,
            "referenceLabelCoverage": speaker_reference / len(segments) if segments else None,
            "comparisonCoverage": speaker_comparable / speaker_reference if speaker_reference else None,
            "disagreementRate": speaker_disagreements / speaker_comparable if speaker_comparable else None,
        },
        "latencyMs": {
            "method": "nearest-rank percentile over supplied measured samples",
            "count": len(latencies),
            "minimum": min(latencies) if latencies else None,
            "maximum": max(latencies) if latencies else None,
            "p50": _nearest_rank(latencies, 0.50),
            "p95": _nearest_rank(latencies, 0.95),
        },
        "limitations": [
            "The evaluator does not capture audio, call providers, align segments, or measure latency.",
            "CER and WER compare only the supplied aligned text; empty total references produce a null rate.",
            "Keyterm scores require explicit occurrence and matched annotations and do not infer semantic matches from text.",
            "Speaker labels must be human-mapped into one shared namespace before evaluation; disagreement is not DER.",
            "The declared measurement origin is operator-provided and is not independently verified.",
        ],
    }


def load_input(path: Path) -> Any:
    if not path.is_file():
        raise EvaluationError(f"input must be an existing file: {path}")
    try:
        return json.loads(
            path.read_text(encoding="utf-8"),
            parse_constant=_reject_nonfinite,
            object_pairs_hook=_reject_duplicate_keys,
        )
    except UnicodeDecodeError as error:
        raise EvaluationError(f"input is not UTF-8: {error}") from error
    except json.JSONDecodeError as error:
        raise EvaluationError(f"invalid JSON: {error}") from error


def write_result(result: dict[str, Any], output: Path | None) -> None:
    rendered = json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False) + "\n"
    if output is None:
        sys.stdout.write(rendered)
        return
    if output.exists():
        raise EvaluationError(f"output already exists; refusing to overwrite: {output}")
    if not output.parent.is_dir():
        raise EvaluationError(f"output parent directory does not exist: {output.parent}")
    try:
        with output.open("x", encoding="utf-8", newline="\n") as stream:
            stream.write(rendered)
    except FileExistsError as error:
        raise EvaluationError(f"output already exists; refusing to overwrite: {output}") from error


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("input", type=Path, help="UTF-8 JSON evaluation input")
    parser.add_argument("--output", type=Path, help="write JSON to a new file instead of stdout")
    args = parser.parse_args(argv)
    try:
        result = evaluate(load_input(args.input))
        write_result(result, args.output)
    except (EvaluationError, OSError) as error:
        parser.exit(2, f"error: {error}\n")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
