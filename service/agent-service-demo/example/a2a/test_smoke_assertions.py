# Copyright (c) Huawei Technologies Co., Ltd. 2026-2026. All rights reserved.

"""Offline regression checks for both smoke scripts; no services or LLM required."""
import copy
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parent
CLAIM = "WF-STREAM-001"


def artifacts():
    return [
        {"metadata": {"_agentcore_terminal": True}, "parts": [
            {"text": f"{CLAIM} approved: total 3000 CNY, policy limit 600 CNY"}]},
        {"metadata": {"agentEvent": {"type": "output", "source": {
            "agentId": "agentd-streaming"}}}, "parts": [{"data": {
                "claim_id": CLAIM, "manual_decision": "approved",
                "policy_status": "OVER_LIMIT", "llm_report": "Expense approved"}}]},
    ]


class SmokeAssertionsTest(unittest.TestCase):
    def validate(self, payload, expected_success, streaming=True):
        raw = ("\n".join("data: " + json.dumps({"result": {"artifactUpdate": {
            "artifact": artifact}}}) for artifact in payload) if streaming else
            json.dumps({"result": {"task": {"artifacts": payload}}}))
        with tempfile.TemporaryDirectory() as directory:
            response = Path(directory) / "response.txt"
            response.write_text(raw, encoding="utf-8")
            shell = (ROOT / "smoke-a2a.sh").read_text()
            function = shell.split("assert_plain_terminal_artifacts() {", 1)[1].split(
                "\n}\n", 1)[0]
            command = ('PYTHON=python3\nassert_plain_terminal_artifacts() {' + function +
                       '\n}\nassert_plain_terminal_artifacts "$1" "$2" "$3"')
            result = subprocess.run(["bash", "-c", command, "assertions", str(response),
                                     "sse" if streaming else "sync", CLAIM], capture_output=True, text=True)
            self.assertEqual(result.returncode == 0, expected_success, result.stderr)
            if shutil.which("pwsh"):
                inputs = Path(directory) / "artifacts.json"
                inputs.write_text(json.dumps(payload), encoding="utf-8")
                # Load only the real assertion function; do not start the smoke services.
                ps = Path(directory) / "assert.ps1"
                ps.write_text('''param($Script, $ArtifactsFile, $ResponseFile, $Claim, $Streaming)
$ErrorActionPreference = "Stop"
$ast = [System.Management.Automation.Language.Parser]::ParseFile($Script, [ref]$null, [ref]$null)
$function = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and
    $node.Name -eq "Assert-PlainTerminalArtifacts"
}, $true)
Invoke-Expression $function.Extent.Text
$items = @(Get-Content -Raw $ArtifactsFile | ConvertFrom-Json)
$raw = Get-Content -Raw $ResponseFile
Assert-PlainTerminalArtifacts $items $raw $Claim -Streaming:($Streaming -eq "sse")
''', encoding="utf-8")
                result = subprocess.run(["pwsh", "-NoProfile", "-File", str(ps),
                                         str(ROOT / "smoke-a2a.ps1"), str(inputs), str(response),
                                         CLAIM, "sse" if streaming else "sync"],
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode == 0, expected_success, result.stderr)

    def test_real_result_passes_both_transports(self):
        self.validate(artifacts(), True)
        self.validate(artifacts()[:1], True, streaming=False)

    def test_parent_completion_cannot_hide_remote_failure(self):
        payload = artifacts()
        payload[0]["parts"][0]["text"] += " {ok=false, code=REMOTE_UNAVAILABLE}"
        self.validate(payload, False)
        self.validate(payload[:1], False, streaming=False)

    def test_parent_summary_cannot_replace_d_result(self):
        self.validate(artifacts()[:1], False)

    def test_report_must_be_nonempty_and_from_d(self):
        for field, value in (("llm_report", ""), ("manual_decision", "rejected"),
                             ("claim_id", "wrong-claim")):
            payload = copy.deepcopy(artifacts())
            payload[1]["parts"][0]["data"][field] = value
            self.validate(payload, False)
        payload = artifacts()
        payload[1]["metadata"]["agentEvent"]["source"]["agentId"] = "agentb"
        self.validate(payload, False)

    def test_prompt_or_trace_does_not_count_as_business_result(self):
        payload = artifacts()
        payload[0]["parts"] = [{"text": "No review result was produced"}]
        self.validate(payload, False)
        self.validate(payload, False, streaming=False)


if __name__ == "__main__":
    unittest.main()
