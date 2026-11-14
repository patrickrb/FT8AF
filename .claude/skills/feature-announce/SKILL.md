---
name: feature-announce
description: >-
  Announce every new FT8AF feature once Patrick promotes a build to Play
  production: one ft8af.app blog post per user-facing feature (merged and
  live), then posts on FT8AF's social accounts (Facebook, X, Discord, plus
  Bluesky/Threads when credentials exist) linking to it. Use from the
  ft8af-factory loop when production moved, or when asked to "blog the new
  features", "announce the release", "post about <feature>", or for an FT8AF
  social post.
---

# Announce FT8AF features

Users can only get what is on the Play **production** track, so nothing is
announced until Patrick promotes it there. Internal builds are never
announced. Patrick asked for a blog post and social posts for **every new
feature** (2026-11-13); that request is the standing approval to merge the
post and publish the social posts without a per-post yes.

**Hard rules**
- Every claim traces to a source: the merged PR (title, "What changed",
  scenarios, screenshots) or the GitHub release notes of a shipped
  `android-dev.*` build. Never invent a rig, a mode, a range or a
  performance number. If the PR doesn't say a radio was tested, don't name it.
- No hype about transmit or RF. Say what the operator can now do, and what
  they need (which radios, which setting). Ham terms stay as hams write them
  (FT8, FT4, POTA, CAT, QSO, PSKReporter, 73).
- Never sign in anywhere. A login wall in Chrome stops that network; report it.

## 1. What shipped to production

```bash
gh api "repos/patrickrb/FT8AF/contents/tracks.json?ref=state/play-tracks" --jq .content | base64 -d
```

The `production` entry with status `completed` gives the release name and
its **versionCode** (always run# + 1000). The name is `android-dev.<N>` when
Patrick promoted an internal build in Console, or `android-v<x.y.z>` when he
shipped by tag; work from the versionCode either way. Resolve it to a git ref:
`android-dev.<versionCode − 1000>` if that tag exists, otherwise the
`android-v*` tag whose release run had that run number
(`gh run list --workflow=android.yml --json number,headBranch,headSha`). The
last announced versionCode is in the newest `announce` issue's title
(baseline before the first one: 2312 = `android-dev.1312`). Everything between
the two refs shipped now:

```bash
gh release list --limit 50 --json tagName --jq '.[].tagName' | grep '^android-dev\.'
gh release view android-dev.<k> --json body --jq .body        # notes for each shipped build
git -C /c/Users/burns/Projects/NEXT-FT8CN fetch --tags origin
git -C /c/Users/burns/Projects/NEXT-FT8CN log --merges --format='%s' <base ref>..<shipped ref> \
  | grep -E '^Merge pull request #[0-9]+ from ' | grep -vE ' from [^/]+/(dev|staging)$'
```

All parents, not `--first-parent`: main's first-parent history is only the
dev → main release merges, and the feature PRs are merges on dev's side. The
last `grep` drops the release/promotion merges themselves (from `dev` or the
retired `staging`). Read each remaining PR
(`gh pr view <n> --json title,body,labels,mergedAt`). A feature is a PR
labelled `user-facing` (factory PRs carry it), or for older PRs one whose
"What changed" opens with something an operator would notice. Fixes, CI,
refactors and docs are not features; a fix an operator reported (often
`from-discord`) can get one line in a feature post, not a post of its own.
Nothing user-facing shipped → no posts; still say so in the report.

Track the batch in one issue so an interrupted run resumes instead of
starting over or stopping half-done. Before writing anything, find or open it:

```bash
gh issue list --label announce --state all --search "versionCode <code> in:title" --json number,state,body
gh issue create --label announce --title "Announce versionCode <code> (<release name>, <version>)" --body-file checklist.md
```

The body is a checklist: one line per feature (PR, blog slug), then one line
per network per post. Tick each item the moment it is done (edit the body),
paste the live URL next to it, and close the issue only when every item is
ticked or marked skipped with a reason. A later cycle that finds an **open**
`announce` issue resumes only its unticked items; a **closed** one for this
release means it's finished. The blog's `release` field is not the "done"
marker, the closed issue is.

## 2. One blog post per feature

The format and the add-a-post steps are in ft8af-site's `src/blog/README.md`;
follow it exactly. Per feature:

- `src/blog/<slug>/post.json`: `title` (what the operator can do now, under
  ~65 characters), `description` (one sentence, under ~155), `date` (today),
  `app_version` (the `x.y.z` of the release's versionName), `release`
  (`android-dev.<N>`, the production release, on every post of the batch),
  `tags`, `prs` ([PR numbers]), and a `hero` + `hero_alt` when there is a
  screenshot.
- `src/blog/<slug>/en.html`: 250-500 words. Open with what it does in one
  paragraph; then how to use it (the real menu path from the PR / strings),
  what you need (radio, cable, setting), and any limits the PR states. Close
  with how to get it ("Update FT8AF from Google Play") and the Discord link
  `https://discord.gg/tz4spm5nWB` for feedback. No `<h1>`; sections start at
  `<h2>`.
- Hero: the best screenshot from the PR's QA evidence (its
  `assets/pr-<n>-shots` branch, or the emulator flow artifact), copied to
  `public/assets/blog/<slug>.png`. Look at it first. None usable → no hero
  (the site default og:image is used).

```bash
git -C /c/Users/burns/Projects/ft8af-site fetch origin
git -C /c/Users/burns/Projects/ft8af-site worktree add -b blog/<release> /c/Users/burns/Projects/ft8af-site-<release> origin/main
# write the posts, then from that worktree:
node build.mjs && npm run check:i18n && npm run test:blog
gh pr create -R patrickrb/ft8af-site --base main --title "Blog: <features> (<version>)" --body "<one line per post, with its PR source>"
```

All posts for one release go in one PR. Merge it when its checks are green,
then wait for the Vercel production deploy and confirm each URL answers 200
before any social post links to it:

```bash
gh pr merge <n> -R patrickrb/ft8af-site --merge
curl -s -o /dev/null -w "%{http_code}\n" https://ft8af.app/blog/<slug>
```

## 3. Social posts, one set per post

FT8AF's accounts (from the site footer):

| Network | Account | Notes |
|---|---|---|
| Facebook | https://www.facebook.com/profile.php?id=61590478809227 | link post; the card comes from the post's og:image |
| X | https://x.com/FT8AF | 280 (a link counts 23); `#FT8 #hamradio` at most |
| Discord | https://discord.gg/tz4spm5nWB | announcements channel via webhook; plain text + link |
| Bluesky / Threads | only if credentials exist | same text as X, room for one more detail |

Copy shapes (same two or three facts everywhere; count before posting):
- **Facebook:** a one-line lead with what's new, two or three sentences on
  what it does and what you need, "Available now in FT8AF <version> on Google
  Play.", then the post link on its own line. No hashtags.
- **X:** the feature in one sentence, the "what you need" in a few words, the
  link. Offer a shorter cut if it runs long.
- **Discord:** "**New in FT8AF <version>:** <title>" then two sentences and
  the link. Several features in one release → one Discord message listing
  them, one line + link each.

Post through the API first:

```bash
python -I .claude/skills/feature-announce/scripts/post_socials.py --posts posts.json --dry-run
python -I .claude/skills/feature-announce/scripts/post_socials.py --posts posts.json
```

It reads credentials **only** from `%LOCALAPPDATA%\ft8af-factory\social.env`
(`FT8AF_SOCIAL_ENV` overrides; the keys are in the script's docstring) and
drops every inherited token variable, so it can never post to Sorrel's
accounts. A network without credentials fails on its own. Report exactly what
it printed; never retry a network that succeeded.

For a network that failed only for missing credentials, stage it in Chrome
instead (load the claude-in-chrome tools in one ToolSearch call, one tab per
network): Facebook → the FT8AF page → switch into the Page if asked →
"Create post" → paste the text with the link → wait for the preview card →
Post; X → "What is happening?!" → text with the link → Post. Verify on the
account's profile page that the post is there before reporting it live.
Stop at any login form and say so.

## 4. Report

- The production release and version announced, and the features found (with
  PR numbers), plus anything skipped and why.
- The merged site PR and each live post URL (200 confirmed).
- One line per network: live URL, or why not (missing credentials, login wall).
- Any sentence that isn't backed by a source, named explicitly.
