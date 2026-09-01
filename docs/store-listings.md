# Play Store listings

The Play Store listing **text** is version-controlled. Every language the app
ships gets its own directory under `fastlane/metadata/android/`, and
`.github/scripts/publish_listings.py` pushes them to Google Play.

Before this, listings were typed into the Play Console by hand, which meant the
English listing was the only one that existed — users browsing the store in
Japanese, Russian, or Portuguese saw an English page for an app whose UI is
fully translated into their language.

## Layout

```
fastlane/metadata/android/
  en-US/                        <- the default listing; every other locale
    title.txt                      falls back to this one's graphics
    short_description.txt
    full_description.txt
  ar/  cs-CZ/  es-419/  es-ES/  fr-FR/  id/  it-IT/  ja-JP/  ko-KR/
  nl-NL/  pl-PL/  pt-BR/  ru-RU/  tr-TR/  uk/  zh-CN/  zh-TW/
```

Directory names are **Play Console locale codes**, which are not the same as the
Android resource qualifiers the app itself uses. The mapping:

| App resources    | Play listing | Language              |
| ---------------- | ------------ | --------------------- |
| `values`         | `en-US`      | English (default)     |
| `values-ar`      | `ar`         | Arabic                |
| `values-cs`      | `cs-CZ`      | Czech                 |
| `values-es`      | `es-ES`      | Spanish (Spain)       |
| `values-es`      | `es-419`     | Spanish (Latin America) |
| `values-fr`      | `fr-FR`      | French                |
| `values-in`      | `id`         | Indonesian            |
| `values-it`      | `it-IT`      | Italian               |
| `values-ja`      | `ja-JP`      | Japanese              |
| `values-ko`      | `ko-KR`      | Korean                |
| `values-nl`      | `nl-NL`      | Dutch                 |
| `values-pl`      | `pl-PL`      | Polish                |
| `values-pt-rBR`  | `pt-BR`      | Portuguese (Brazil)   |
| `values-ru`      | `ru-RU`      | Russian               |
| `values-tr`      | `tr-TR`      | Turkish               |
| `values-uk`      | `uk`         | Ukrainian             |
| `values-zh-rCN`  | `zh-CN`      | Chinese (Simplified)  |
| `values-zh-rTW`  | `zh-TW`      | Chinese (Traditional) |

`es-419` is a Play-only split with no app-resource counterpart: it is the same
Spanish copy rewritten with Latin American vocabulary (*celular*, *computadora*)
rather than Iberian (*móvil*, *ordenador*). `es-US` can be added the same way if
US Spanish is ever worth splitting out.

**Adding a language to the app means adding a directory here too** — a new
`values-xx` with no matching listing directory leaves those users with an
English store page.

## Limits

Play rejects anything longer, so the unit tests enforce these locally and name
the offending file instead of letting the API return a bare 400:

| File                    | Limit          |
| ----------------------- | -------------- |
| `title.txt`             | 30 characters  |
| `short_description.txt` | 80 characters  |
| `full_description.txt`  | 4000 characters |

Limits are **characters, not bytes** — CJK copy that is well within 80
characters is far over 80 bytes, and that is fine.

Translations cannot be literal: most languages run 5–15% longer than English,
and the short description has only 80 characters to work with. Write to the
idea, not to the words. The English full description is deliberately kept a few
hundred characters below the cap for the same reason — French, the longest
translation, lands within about 500 characters of the limit.

`title.txt` is `FT8AF` in every locale — it is a brand name, not a phrase to
translate. A test asserts this, so changing the store name is a deliberate edit
to that test rather than something a translation pass can do by accident.

## Publishing

The workflow is `.github/workflows/play-listings.yml`:

- **Pull request** touching the metadata → validation only. Runs the unit tests,
  which load the whole tree and check completeness and character limits. No
  secrets, so it works from forks.
- **Push to `main`** touching the metadata → publishes the changed locales. Since
  all PRs target `dev`, listing changes reach Play on the normal
  dev → staging → main promotion, alongside the release they belong to.
- **Manual run** (Actions → "Play store listings" → Run workflow) → pick a mode:
  `dry-run` (the default; prints the diff, sends nothing), `check-permissions`
  (the grant probe below), or `publish` (the real thing, without waiting for a
  merge to `main`).

Note that `workflow_dispatch` only appears once this workflow file exists on the
repository's **default branch**, `main`. Until the first promotion carries it
there, the manual run is not available and the PR-time validation is all that
runs.

Only locales whose text actually differs from Play are sent, so a rerun that
changes nothing commits nothing.

### Prerequisite: service account permission

`PLAY_SERVICE_ACCOUNT_JSON` was set up for *releases*. Editing listings needs a
separate grant — **Play Console → Users and permissions → the service account →
App permissions → "Edit store listing, pricing & distribution"**. Without it the
`listings.patch` call fails with a 403 that names the missing permission, and
nothing is committed.

**A dry run does not prove you have this grant.** It only reads listings, and
reading them needs no edit permission — so an account that can read but not
write passes the dry run and fails on the first real publish. Use the probe
instead — as a manual run in the `check-permissions` mode, or locally:

```bash
python .github/scripts/publish_listings.py --check-permissions
```

That does the one thing a dry run skips: a single `listings.patch` inside an
edit it then abandons rather than commits, so nothing reaches the store. It
writes back the text Play already has, so the probe is a no-op even in
principle.

Its exit code is a verdict, so the codes are chosen not to collide with
anything else the script (or argparse) can produce:

| exit | meaning |
| ---- | ------- |
| `0`  | the account may edit listings |
| `1`  | the probe did not run — bad credentials, unpublishable metadata. **Not** a verdict |
| `3`  | Play refused the patch (401/403). This is the missing grant |
| `4`  | inconclusive — timeout, rate limit, Play 5xx. Proves nothing either way; run it again |

`2` is skipped on purpose: argparse exits 2 on a usage error, and a mistyped
flag must not read as an answer about the grant. Anything other than `3` is not
evidence that a permission is wrong.

Play's one-open-edit-per-app rule means a listing publish and an AAB upload must
not overlap, or the second fails with *"This edit has expired"*. The publish job
shares the `play-publish` concurrency group with `android.yml`, which serializes
them. A dry run or a no-op run abandons its edit rather than leaving it open.

### Running it locally

The script needs `google-auth` and `requests` — the same two the workflow
installs. There is no requirements file; a clean checkout otherwise fails at the
lazy imports in `play_session()`.

```bash
pip install google-auth requests
export PLAY_SERVICE_ACCOUNT_JSON="$(cat service-account.json)"
python .github/scripts/publish_listings.py --dry-run            # show the diff
python .github/scripts/publish_listings.py                      # publish + commit
python .github/scripts/publish_listings.py --pull               # overwrite the tree from Play
python .github/scripts/publish_listings.py --check-permissions  # can this account edit listings?
```

`--dry-run` prints a unified diff per changed field, Play's text against the
repo's, so a same-length edit is visible rather than showing as an unchanged
character count.

`--pull` is the resync path: if someone edits a listing in the Play Console, pull
it back down so the repo stops disagreeing with production, then commit the
result. Without it the next publish would silently revert their edit.

It only ever creates or overwrites locales Play returns. A locale that exists
here but not on Play — every language before its first publish — is left alone
and named in the output, because deleting it would throw away exactly the work
being protected. Retiring a language means deleting its directory by hand.

## What this does *not* manage

- **Graphics** — icon, feature graphic, and screenshots. Play falls back to the
  default language's graphics for any locale with none of its own, so all 17
  listings currently show the English screenshots. Per-language screenshots are
  tracked separately; the capture process runs on the `PS_Shots` AVD with the
  debug demo-data inject, and upload is still manual in the Play Console.
- **Release notes** — `android.yml` generates them per release and writes
  `distribution/whatsnew/whatsnew-en-US` for the upload action. They are English
  only. Localizing them means writing `whatsnew-<locale>` files in that same
  step, not adding `changelogs/` directories here.
- **Data safety, content rating, pricing, countries** — Play Console only.
