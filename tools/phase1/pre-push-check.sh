#!/usr/bin/env bash
set -euo pipefail
source_root=$(git rev-parse --show-toplevel)
while read -r local_ref local_sha remote_ref remote_sha; do
    [[ "$remote_ref" == refs/heads/codex/phase1-* ]] || continue
    [[ "$local_sha" != 0000000000000000000000000000000000000000 ]] || continue
    if [[ "$remote_sha" != 0000000000000000000000000000000000000000 ]]; then
        changed=$(git diff --name-only "$remote_sha" "$local_sha")
        requires_gate=0
        while IFS= read -r changed_path; do
            case "$changed_path" in *.md) continue ;; esac
            case "$changed_path" in core/*|sdk/*|tools/phase1/*) requires_gate=1 ;; esac
        done <<< "$changed"
        if [[ "$requires_gate" == 0 ]]; then
            git diff --check "$remote_sha" "$local_sha"
            continue
        fi
    fi
    python3 -E "$source_root/tools/phase1/login-test-context.py" --verify-gate --head "$local_sha"
done
