#!/usr/bin/env bash
# PreToolUse gate on the Agent tool: blocks launching a bmad-build step-04 review layer
# (Blind Hunter / Edge Case Hunter / Verification Gap) or an equivalent code-review subagent
# immediately after implementation, until the user has explicitly confirmed timing and model.
#
# Why this exists: on 2026-09-14 and again on 2026-09-28, dev and code-review ran back-to-back
# in one session without asking the user first -- once burning the session's token budget, once
# also defaulting the review to the same model as implementation instead of the user's preferred
# Opus-for-review split. See project memory: bmad-review-gate.md.
#
# Bypass: once the user has actually answered (1) run now/later and (2) which model, the launch
# prompt must include the literal marker `USER-CONFIRMED-REVIEW: model=<model> timing=<now|later>`
# for this hook to let it through.
#
# Logic lives in review_gate.py (python3, not jq -- jq is not guaranteed installed on every
# machine this project's hooks run on).

set -euo pipefail
python3 "$(dirname "${BASH_SOURCE[0]}")/review_gate.py"
