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

The English short description sits at exactly 80 characters, so translations
cannot be literal; most languages expand 20–30% over English. Write to the idea,
not to the words.

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
- **Manual run** (Actions → "Play store listings" → Run workflow) → dry run by
  default; it prints the diff against what is live and sends nothing. Untick
  "Dry run" to publish without waiting for a merge to `main`.

Only locales whose text actually differs from Play are sent, so a rerun that
changes nothing commits nothing.

Play's one-open-edit-per-app rule means a listing publish and an AAB upload must
not overlap, or the second fails with *"This edit has expired"*. The publish job
shares the `play-publish` concurrency group with `android.yml`, which serializes
them. A dry run or a no-op run abandons its edit rather than leaving it open.

### Running it locally

```bash
export PLAY_SERVICE_ACCOUNT_JSON="$(cat service-account.json)"
python .github/scripts/publish_listings.py --dry-run   # show the diff
python .github/scripts/publish_listings.py             # publish + commit
python .github/scripts/publish_listings.py --pull      # overwrite the tree from Play
```

`--pull` is the resync path: if someone edits a listing in the Play Console, pull
it back down so the repo stops disagreeing with production, then commit the
result. Without it the next publish would silently revert their edit.

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
