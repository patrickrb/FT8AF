## Debug APK

<!-- CI posts a "Debug APK" comment with the download link once the build
finishes. Paste that link here, or write "see the Debug APK comment".
Write "n/a" if the PR doesn't touch ft8af/**. On a fork PR there is no
comment; link the artifact from the workflow run's summary instead. -->

## Device test

<!-- Does this need testing on a real phone with a radio? Only if it touches
transmit/PTT, CAT/rig control, USB audio or serial, audio routing, permissions,
or signing/release config (see "Device testing" in AGENTS.md). If yes, add the
`needs-device-test` label and say which part. -->

- [ ] Needs device test (labeled `needs-device-test`): <!-- why -->
- [ ] Emulator is enough

## Manual test checklist

<!-- 3-6 steps QA runs on the emulator (or the maintainer on a phone for
`needs-device-test` PRs), each with the expected result. -->

1. 
2. 
3. 

## What changed

<!-- What the user will notice, then the main code changes. Link the issue or
Discord report this fixes. -->

## Risks

<!-- What could break, and on which radios, devices, or Android versions. Write
"Low" and say why if it really is low. -->

## Tested automatically

<!-- New or changed unit tests and what they cover. Note anything that only CI
ran (instrumented tests, lint). -->

## Code review

<!-- Request a Copilot review first. If Copilot doesn't respond, run Claude's
`code-review` skill instead. Address every finding before QA. -->

- Reviewer used: <!-- Copilot / Claude code-review (Copilot didn't respond) -->
- [ ] Every finding addressed or answered

## QA evidence

<!-- Filled in by QA: screenshots or a short screen recording of the checklist
run on the emulator. -->

