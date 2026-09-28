#!/usr/bin/env python3
"""PreToolUse gate on the Agent tool for bmad-build's step-04 review layers.

See review-gate.sh (the hook entry point) and project memory bmad-review-gate.md
for why this exists.
"""
import json
import re
import sys

data = json.load(sys.stdin)
tool_input = data.get("tool_input", {}) or {}
text = f'{tool_input.get("prompt", "")} {tool_input.get("description", "")}'

signature = re.compile(
    r"blind hunter|blind-hunter|edge case hunter|edge-case-hunter|verification gap|verification-gap",
    re.IGNORECASE,
)

if signature.search(text) and "USER-CONFIRMED-REVIEW" not in text:
    reason = (
        "BMad review-gate: this looks like a step-04 code-review subagent launch "
        "(Blind Hunter / Edge Case Hunter / Verification Gap). Per project policy "
        "(memory: bmad-review-gate), STOP and ask the user (1) run review now, later, "
        "or in a fresh session, and (2) which model/effort to use (default: Opus for "
        "review, Sonnet for dev -- confirm, do not assume). Only relaunch after they "
        "answer, adding the literal marker "
        '"USER-CONFIRMED-REVIEW: model=<model> timing=<now-or-later>" to this prompt.'
    )
    print(json.dumps({
        "hookSpecificOutput": {
            "hookEventName": "PreToolUse",
            "permissionDecision": "deny",
            "permissionDecisionReason": reason,
        }
    }))
