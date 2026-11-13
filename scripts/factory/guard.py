"""PreToolUse guard for the unattended factory loop.

Permission deny rules match a command by prefix, so `git -C dir reset --hard`
or `git push origin HEAD -f` slip past `Bash(git reset --hard*)`. This hook
reads the whole command and blocks the destructive forms wherever their flags
sit, plus FT8AF's production line: only Patrick ships to Play production,
so the factory may not create or push an android-v* / desktop-v* tag, run the
production ship on one, or merge past branch protection. It is active only in the factory session (FT8AF_FACTORY=1, set by
run-loop.cmd), so interactive sessions keep their normal rules.

Exit 2 blocks the call and tells the model why (Claude Code hook contract).

    python scripts/factory/guard.py --self-test
"""
import json
import os
import re
import sys

# Each rule: (why, regex over one shell segment). Segments are split on
# ; && || | and newlines, so a rule only sees one command at a time.
RULES = [
    ("force-push", re.compile(
        r"\bgit\b.*\bpush\b.*(?:\s--force\S*|\s-[A-Za-z]*f[A-Za-z]*\b|\s\+\S)")),
    ("git reset --hard", re.compile(r"\bgit\b.*\breset\b.*\s--hard\b")),
    ("git clean", re.compile(r"\bgit\b.*\sclean\b")),
    ("git checkout/restore discarding work", re.compile(
        r"\bgit\b.*\s(?:checkout|restore)\b.*(?:\s--\s+\.|\s\.\s*$|\s--force\b|\s-f\b)")),
    # Any named-path restore of the working tree discards uncommitted edits:
    # `git restore <path>`, `git restore --worktree …`, `git checkout [<ref>] -- <path>`.
    # Index-only `git restore --staged <path>` stays allowed.
    ("git restore of working-tree files", re.compile(
        r"\bgit\b.*\srestore\b(?!.*\s--staged\b)|\bgit\b.*\srestore\b.*\s(?:--worktree|-W)\b")),
    ("git checkout of paths (discards edits)", re.compile(r"\bgit\b.*\scheckout\b.*\s--(?:\s|$)")),
    # Bulk pushes can publish a local android-v* / desktop-v* tag without naming it.
    ("a bulk or wildcard tag push", re.compile(
        r"\bgit\b.*\bpush\b.*(?:\s--tags\b|\s--follow-tags\b|\s--mirror\b|refs/tags/\*|\s['\"]?\*:)")),
    ("deleting a branch with -D", re.compile(r"\bgit\b.*\sbranch\b.*\s-D\b")),
    ("gh secret / variable / auth", re.compile(r"\bgh\s+(?:secret|variable|auth)\b")),
    ("deleting a repo or release", re.compile(r"\bgh\s+(?:repo|release)\s+delete\b")),
    ("API DELETE", re.compile(
        r"\bgh\s+api\b.*(?:\s-X\s*['\"]?DELETE|\s--method[=\s]+['\"]?DELETE)", re.I)),
    ("a production tag (android-v* / desktop-v*)", re.compile(
        r"\b(?:git\b.*\s(?:tag|push)\b|gh\s+(?:release\s+create|workflow\s+run)\b|gh\s+api\b.*\bgit/refs\b)"
        r".*\b(?:android|desktop)-v\d")),
    ("a production workflow dispatch", re.compile(
        r"\bgh\s+api\b.*/dispatches\b.*\b(?:android|desktop)-v\d|\bgh\s+api\b.*\b(?:android|desktop)-v\d.*/dispatches\b")),
    ("a raw workflow re-run (use scripts/factory/rerun.sh, which refuses production runs)",
     re.compile(r"\bgh\s+(?:run\s+rerun|api\b.*/actions/(?:runs|jobs)/\d+/rerun)")),
    ("merging past branch protection (--admin)", re.compile(r"\bgh\s+pr\s+merge\b.*\s--admin\b")),
]

SPLIT = re.compile(r"&&|\|\||[;|\n]")


def why_blocked(command: str):
    for seg in SPLIT.split(command or ""):
        seg = " " + seg.strip()
        for why, rx in RULES:
            if rx.search(seg):
                return why
    return None


BLOCK = [
    "git push --force origin x", "git push origin HEAD -f", "git push -fu origin x",
    "git push origin +HEAD:main", "git push --force-with-lease",
    "git -C C:/w reset --hard HEAD", "cd x && git reset --hard origin/dev",
    "git clean -fdx", "git -C w clean -n", "git checkout -- .", "git restore .",
    "git branch -D feat/x",
    "gh secret list", "gh variable set X", "gh auth status", "gh auth login",
    "gh repo delete patrickrb/x", "gh release delete v1",
    "gh api -X DELETE repos/a/b/git/refs/heads/x", "gh api repos/a/b -X DELETE",
    "gh api --method=DELETE repos/a/b", "gh api --method delete repos/a/b",
    "gh api -XDELETE repos/a/b",
    "git tag android-v1.2.0", "git push origin android-v1.2.0", "git -C w tag -a desktop-v2.0.0 -m x",
    "gh release create android-v1.2.0", "gh workflow run android.yml --ref android-v1.2.0",
    "gh api repos/a/b/git/refs -f ref=refs/tags/android-v1.2.0 -f sha=x",
    "gh pr merge 12 --merge --admin",
    "gh api repos/a/b/actions/workflows/android.yml/dispatches -f ref=android-v1.2.0",
    "gh run rerun 123", "gh run rerun 123 --failed",
    "gh api -X POST repos/a/b/actions/runs/123/rerun",
    "gh api -X POST repos/patrickrb/FT8AF/actions/jobs/123/rerun",
    "git push origin --tags", "git push --follow-tags", "git push --mirror origin",
    "git push origin 'refs/tags/*'", "git push origin refs/tags/*:refs/tags/*",
    "git restore ft8af/app/src/main/SomeFile.kt", "git restore --worktree x.kt",
    "git restore --staged --worktree x.kt", "git checkout -- scripts/factory/guard.py",
    "git checkout HEAD -- x.kt", "git -C w checkout origin/dev -- a b",
]
ALLOW = [
    "git push -u origin feat/x", "git push", "git fetch origin",
    "git reset HEAD~1 --soft", "git log --oneline -1", "git branch -d feat/merged",
    "git checkout -b feat/x origin/dev", "git restore --staged file.kt",
    "git worktree add -b feat/x C:/wt origin/dev", "git worktree remove C:/wt",
    "gh pr merge 1 --auto --merge", "gh pr create --draft --base dev --title fix -f",
    "gh api repos/a/b/pulls/1/requested_reviewers -X POST -f x=y",
    "gh issue edit 1 --remove-label factory:ready", "gh run list --branch main",
    "bash scripts/factory/gradle.sh testDebugUnitTest",
    "gh pr merge 12 --merge", "gh release view android-dev.1538", "git tag --list 'android-dev.*'",
    "git fetch --tags", "gh run list --workflow=android.yml --branch main",
    "bash scripts/factory/rerun.sh 123", "gh run view 123 --json headBranch",
    "git checkout dev", "git switch -c feat/x", "git push -u origin HEAD",
    "git push origin feat/x:feat/x", "git restore --staged a.kt b.kt",
]


def self_test():
    bad = [c for c in BLOCK if not why_blocked(c)] + [c for c in ALLOW if why_blocked(c)]
    for c in bad:
        print("WRONG:", c, "->", why_blocked(c))
    print("guard self-test", "FAILED" if bad else f"ok ({len(BLOCK)} blocked, {len(ALLOW)} allowed)")
    return 1 if bad else 0


def main():
    if "--self-test" in sys.argv:
        return self_test()
    if os.environ.get("FT8AF_FACTORY") != "1":
        return 0
    try:
        event = json.load(sys.stdin)
    except ValueError:
        return 0
    command = (event.get("tool_input") or {}).get("command", "")
    why = why_blocked(command)
    if why:
        print(f"Blocked by the factory guard ({why}). This loop runs unattended "
              f"and may not do that; leave it for Patrick and say so.", file=sys.stderr)
        return 2
    return 0


if __name__ == "__main__":
    sys.exit(main())
