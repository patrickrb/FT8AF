# Desktop CI patch — 32-bit Windows (i686) MSI leg

`i686-windows-ci.patch` adds a second Windows leg to
`.github/workflows/desktop.yml` so the desktop release also builds and uploads a
32-bit (`i686`) MSI/NSIS installer alongside the x64 one.

**Why it's a patch and not applied directly:** the automation account that opened
this PR authenticates with a token that lacks the GitHub `workflow` scope, so it
cannot push edits to files under `.github/workflows/`. A maintainer with
`workflow` scope should apply this patch (it's the only piece of the change that
touches a workflow file):

```
git apply desktop/ci/i686-windows-ci.patch
git add .github/workflows/desktop.yml
git commit -m "Desktop CI: add 32-bit Windows (i686) MSI build leg"
```

## What the patch changes

The `build` job's matrix becomes an `include` list with a new `windows-x86` leg:

- installs the `i686-pc-windows-msvc` Rust target (`dtolnay/rust-toolchain`
  `targets:`),
- gives each leg a distinct `rust-cache` key so the two `windows-latest` jobs
  don't thrash one cache,
- adds `--target i686-pc-windows-msvc` to both the PR compile-check
  (`tauri build --no-bundle`) and the release bundle (`tauri-action` `args`),
  only on that leg — the other three legs are byte-for-byte unchanged.

Tauri's WiX bundler tags the MSI filename with the architecture
(`FT8AF_<ver>_x86_en-US.msi` vs `..._x64_...`), so the two Windows installers
attach to the same release without colliding. All the non-workflow code that
makes the 32-bit build actually compile and bundle (`build.rs` arch selection,
the `hamlib/x86` DLL split) ships in this PR directly.
