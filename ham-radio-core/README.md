# Ham-radio core 0.1.0

Canonical source: `patrickrb/FT8AF`, repository-root `ham-radio-core/`.
Extracted from FT8AF baseline `c2f63e8` without changing algorithms or public APIs.
MIT license; see `LICENSE`. This module contains no vendored native libraries.

## Boundary

An Android-free Java 8 library with no runtime dependencies:

| Package | Contract |
| --- | --- |
| `radio.core.audio.FirDecimator` | Stateful integer FIR decimation; caller supplies float input/output buffers. |
| `radio.core.audio.RationalResampler` | Stateful exact L/M conversion, including 44.1 kHz → 12 kHz. |
| `radio.core.usb.UacAudioFormats` | Parse UAC1 byte descriptors; select actual supported 16-bit-container formats/rates for a caller-supplied decoder rate. No device I/O. |
| `radio.core.cat.CatLiveness` | Probe/staleness/TX→RX re-arm decisions; caller owns timestamps, transport and state. |

Resamplers retain history across calls. Create one per stream; do not concurrently
share an instance. Reset on stream discontinuity. Input arrays are borrowed only
during `process`; output belongs to the caller. Allocate output using
`maxOutputFor(n)` (rational) or `n / ratio + 1` (integer). The rate-pair helpers
select downsampling, not general upsampling: callers must not label a guarded
pass-through as a successful higher-rate conversion. Explicit L/M construction
also supports upsampling; consumers must account for FIR delay themselves.

`UacAudioFormats.choose` may return null; the adapter decides whether legacy
fallback is safe. It parses UAC1 only, not UAC2/UAC3 or USB transfer scheduling.
It preserves FT8AF's two-byte-subframe selection policy; it does not validate
every PCM encoding or replace the adapter's format/channel validation.
CAT staleness arms only after a reply, never during TX, and uses strictly greater
than timeout. Re-arm the timestamp on the TX→RX edge. Use one consistent clock
domain for timestamps and retain the app's disconnect/reconnect state machine.

## Build and consume

From FT8AF's root, with JDK 17:

```sh
bash ft8af/gradlew -p ham-radio-core clean test jar
```

This standalone build needs no Android SDK. The existing 44 tests travel with
the implementation: sample counts, passband/alias rejection, buffer continuity,
malformed descriptors, actual rates and CAT timeout/TX boundaries.

In an Android Gradle settings file (adjust relative path):

```groovy
include ':ham-radio-core'
project(':ham-radio-core').projectDir = file('../ham-radio-core')
```

Add `implementation project(':ham-radio-core')` in the app. Its parent settings
must provide Maven Central for test dependencies. Replace old imports directly;
do not retain app-local copies or forwarding wrappers. Run both the core tests
and the consumer tests/build. Newer consumer APIs must be compared before moving
them; do not overwrite local fixes with an older snapshot.

## Distribution and updates

For this first cross-repository rollout, each consumer vendors this **whole
module**, including tests, license and `SHA256SUMS`, unchanged. This keeps fresh
orbs/builds self-contained without publishing a package or creating a remote
repository. The authoritative implementation stays in FT8AF; consumer adapters
stay outside this directory. From the module directory, verify with:

```sh
sha256sum -c SHA256SUMS
```

The manifest pins the source/test/build/doc payload (not itself or build output).
Record the upstream version and manifest in each port's documentation. Core
changes go to FT8AF first, pass its tests, bump the version, regenerate the
manifest and update consumers with the exact payload in one reviewed change per
repository. Never silently edit a vendored copy. A published Maven artifact is a
future distribution option, not a prerequisite or something this rollout creates.

Android USB permissions/FD lifetimes, libusb/JNI, serial drivers, rig codecs,
PTT key/unkey orchestration, capture/playback, mode timing/encoders, diagnostic
file storage, ADIF application policy, themes and screens intentionally remain
app-owned. Sharing policies is not proof of hardware interoperability.
