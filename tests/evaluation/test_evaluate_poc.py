import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).parents[2] / "scripts" / "evaluate_poc.py"
SPEC = importlib.util.spec_from_file_location("evaluate_poc", SCRIPT)
evaluation = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(evaluation)


def payload(segments, latencies=None):
    return {
        "schemaVersion": 1,
        "measurementOrigin": "synthetic/offline",
        "segments": segments,
        "latencySamplesMs": latencies or [],
    }


class EvaluatePocTest(unittest.TestCase):
    def test_korean_unicode_and_edit_operations_are_micro_aggregated(self):
        result = evaluation.evaluate(payload([
            {"id": "exact-nfc", "reference": "가 나", "hypothesis": "가 나"},
            {"id": "sub", "reference": "공정", "hypothesis": "공장"},
            {"id": "delete", "reference": "LOT 42", "hypothesis": "LOT"},
            {"id": "insert", "reference": "", "hypothesis": "추가"},
        ]))

        cer = result["transcript"]["cer"]
        wer = result["transcript"]["wer"]
        self.assertEqual((cer["substitutions"], cer["deletions"], cer["insertions"]), (1, 3, 2))
        self.assertEqual(cer["referenceUnits"], 11)
        self.assertEqual(cer["rate"], 6 / 11)
        self.assertEqual((wer["substitutions"], wer["deletions"], wer["insertions"]), (1, 1, 1))
        self.assertEqual(wer["referenceUnits"], 5)
        self.assertEqual(wer["rate"], 0.6)

    def test_all_empty_references_have_unknown_rates_but_keep_insertions(self):
        result = evaluation.evaluate(payload([
            {"id": "empty", "reference": "", "hypothesis": ""},
            {"id": "insert", "reference": "", "hypothesis": "오인식"},
        ]))
        self.assertIsNone(result["transcript"]["cer"]["rate"])
        self.assertEqual(result["transcript"]["cer"]["insertions"], 3)
        self.assertIsNone(result["transcript"]["wer"]["rate"])
        self.assertEqual(result["transcript"]["wer"]["insertions"], 1)

    def test_keyterms_use_explicit_occurrences_and_count_false_positives(self):
        result = evaluation.evaluate(payload([{
            "id": "terms",
            "reference": "LOT 번호",
            "hypothesis": "로또 번호",
            "termAnnotations": [
                {"term": "LOT", "referenceCount": 1, "predictedCount": 0, "matchedCount": 0},
                {"term": "로또", "referenceCount": 0, "predictedCount": 2, "matchedCount": 0},
                {"term": "MES", "referenceCount": 2, "predictedCount": 1, "matchedCount": 1},
            ],
        }]))
        terms = result["keyterms"]
        self.assertEqual((terms["truePositives"], terms["falsePositives"], terms["falseNegatives"]), (1, 2, 2))
        self.assertEqual(terms["precision"], 1 / 3)
        self.assertEqual(terms["recall"], 1 / 3)

    def test_speaker_disagreement_reports_missing_label_coverage(self):
        result = evaluation.evaluate(payload([
            {"id": "same", "reference": "a", "hypothesis": "a", "referenceSpeaker": "s1", "predictedSpeaker": "s1"},
            {"id": "different", "reference": "b", "hypothesis": "b", "referenceSpeaker": "s2", "predictedSpeaker": "s1"},
            {"id": "missing-prediction", "reference": "c", "hypothesis": "c", "referenceSpeaker": "s2"},
            {"id": "unannotated", "reference": "d", "hypothesis": "d", "predictedSpeaker": "s3"},
        ]))
        speakers = result["speakers"]
        self.assertEqual(speakers["metricName"], "aligned speaker-label disagreement (not DER)")
        self.assertEqual(speakers["comparableSegments"], 2)
        self.assertEqual(speakers["missingReferenceLabels"], 1)
        self.assertEqual(speakers["missingPredictedLabelsForReference"], 1)
        self.assertEqual(speakers["comparisonCoverage"], 2 / 3)
        self.assertEqual(speakers["disagreementRate"], 0.5)

    def test_latency_uses_nearest_rank_percentiles(self):
        latency = evaluation.evaluate(payload([], [100, 200, 300, 400, 500, 600, 700, 800, 900, 1000]))["latencyMs"]
        self.assertEqual(latency["p50"], 500)
        self.assertEqual(latency["p95"], 1000)
        self.assertEqual((latency["minimum"], latency["maximum"]), (100, 1000))

    def test_rejects_duplicate_ids_invalid_times_and_nonfinite_values(self):
        invalid_payloads = [
            {"schemaVersion": True, "measurementOrigin": "synthetic/offline", "segments": []},
            {"schemaVersion": 1.0, "measurementOrigin": "synthetic/offline", "segments": []},
            payload([{"id": "same", "reference": "a", "hypothesis": "a"}, {"id": "same", "reference": "b", "hypothesis": "b"}]),
            payload([{"id": "time", "reference": "a", "hypothesis": "a", "startMs": 2, "endMs": 1}]),
            payload([], [-1]),
            payload([], [10**1000]),
            payload([{"id": "count", "reference": "a", "hypothesis": "a", "termAnnotations": [{"term": "a", "referenceCount": True, "predictedCount": 0, "matchedCount": 0}]}]),
            payload([{"id": "match", "reference": "a", "hypothesis": "a", "termAnnotations": [{"term": "a", "referenceCount": 1, "predictedCount": 0, "matchedCount": 1}]}]),
        ]
        for invalid in invalid_payloads:
            with self.subTest(invalid=invalid), self.assertRaises(evaluation.EvaluationError):
                evaluation.evaluate(invalid)

        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "nan.json"
            source.write_text('{"schemaVersion":1,"measurementOrigin":"synthetic/offline","segments":[],"latencySamplesMs":[NaN]}', encoding="utf-8")
            with self.assertRaisesRegex(evaluation.EvaluationError, "non-finite"):
                evaluation.load_input(source)

    def test_cli_writes_utf8_stdout_and_refuses_output_clobber(self):
        fixture = Path(__file__).parents[2] / "examples" / "poc-evaluation-synthetic.json"
        completed = subprocess.run(
            [sys.executable, str(SCRIPT), str(fixture)],
            capture_output=True,
            text=True,
            encoding="utf-8",
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        parsed = json.loads(completed.stdout)
        self.assertEqual(parsed["measurementOrigin"], "synthetic/offline")
        self.assertEqual(parsed["counts"]["segments"], 3)

        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "existing.json"
            output.write_text("keep", encoding="utf-8")
            refused = subprocess.run(
                [sys.executable, str(SCRIPT), str(fixture), "--output", str(output)],
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )
            self.assertEqual(refused.returncode, 2)
            self.assertIn("refusing to overwrite", refused.stderr)
            self.assertEqual(output.read_text(encoding="utf-8"), "keep")

    def test_cli_rejects_duplicate_json_keys(self):
        with tempfile.TemporaryDirectory() as temporary:
            source = Path(temporary) / "duplicate.json"
            source.write_text(
                '{"schemaVersion":1,"measurementOrigin":"synthetic/offline",'
                '"segments":[{"id":"x","reference":"정답","reference":"덮어쓰기",'
                '"hypothesis":"정답"}]}',
                encoding="utf-8",
            )
            completed = subprocess.run(
                [sys.executable, str(SCRIPT), str(source)],
                capture_output=True,
                text=True,
                encoding="utf-8",
                check=False,
            )

        self.assertEqual(completed.returncode, 2)
        self.assertIn("duplicate key: reference", completed.stderr)
        self.assertEqual(completed.stdout, "")


if __name__ == "__main__":
    unittest.main()
