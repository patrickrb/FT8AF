<#
.SYNOPSIS
  Build and run the native FT8 golden-vector tests on the host (no device needed).

.DESCRIPTION
  Compiles ft8af_glue/test_golden_encode.c against the vendored ft8_lib encode
  path (pack / encode / crc / constants / text / message) with a host clang and
  runs it. Exit code is the test's exit code: 0 == all golden checks pass.

  This is the regression guard for issue #61: the FT8 encode/hash output that the
  prebuilt libft8cn.so produces today, frozen as golden vectors, so the
  in-progress from-source reconstruction (and any future ft8_lib change) must
  reproduce it bit-for-bit.

.PARAMETER Clang
  Path to a host clang.exe. Defaults to a search of common install locations.
  The Android NDK clang does NOT work here (it targets Android/MinGW with no host
  C runtime headers) -- use a desktop LLVM (winget install LLVM.LLVM) or MSVC.

.PARAMETER Regen
  Build the test and run it in --emit mode: it prints fresh, paste-ready C
  literals (payloads / tones / hashes) computed live from the current ft8_lib.
  Use ONLY when the FT8 vectors change intentionally, then paste the three blocks
  over the kGolden* tables in test_golden_encode.c. Never hand-edit single bytes.

.EXAMPLE
  ./run_host_tests.ps1
.EXAMPLE
  ./run_host_tests.ps1 -Clang "C:\Program Files\LLVM\bin\clang.exe"
#>
param(
    [string]$Clang = "",
    [switch]$Regen
)

$ErrorActionPreference = "Stop"
$here = Split-Path -Parent $MyInvocation.MyCommand.Definition
$cpp  = Split-Path -Parent $here            # .../cpp
$ft8  = Join-Path $cpp "ft8_lib"

if (-not $Clang) {
    $probe = @(
        "C:\Program Files\LLVM\bin\clang.exe",
        "${env:ProgramFiles(x86)}\LLVM\bin\clang.exe",
        (Get-Command clang -ErrorAction SilentlyContinue).Source
    )
    $candidates = @($probe | Where-Object { $_ -and (Test-Path $_) })
    if ($candidates.Count -eq 0) {
        Write-Error "No host clang found. Install LLVM (winget install LLVM.LLVM) or pass -Clang <path>."
    }
    $Clang = $candidates[0]
}
Write-Host "Using clang: $Clang"

# ft8_lib sources needed by the encode + pack path.
$ft8Srcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c"
) | ForEach-Object { Join-Path $ft8 $_ }

$compat = Join-Path $here "host_compat.h"
# -D_CRT_SECURE_NO_WARNINGS silences MSVC's strncpy/etc. deprecation notices from
# the vendored ft8_lib; -Wno-deprecated-non-prototype quiets its K&R-style decls.
$common = @("-std=c11","-O2","-D_CRT_SECURE_NO_WARNINGS",
            "-Wno-deprecated-non-prototype","-Wno-deprecated-declarations",
            "-include", $compat, "-I", $ft8)

$src = Join-Path $here "test_golden_encode.c"
$out = Join-Path $env:TEMP "ft8_golden_test.exe"

& $Clang @common $src @ft8Srcs -o $out
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed." }

if ($Regen) {
    & $out --emit
    exit $LASTEXITCODE
}

& $out
$goldenExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# dev-625 regression tests: ft8_snr() FT8 calibration + the waterfall display
# magnitude mapping (fft_display.c). Links the decoder + the extracted display
# helper; separate executable (own main()).
# ---------------------------------------------------------------------------
$dev625Srcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c","ft8\decode.c","ft8\ldpc.c","ft8\osd.c","ft8\unpack.c"
) | ForEach-Object { Join-Path $ft8 $_ }
$dev625Srcs += (Join-Path $here "fft_display.c")

$src625 = Join-Path $here "test_dev625_fixes.c"
$out625 = Join-Path $env:TEMP "ft8_dev625_test.exe"

& $Clang @common $src625 @dev625Srcs -o $out625
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (dev625)." }

& $out625
$dev625Exit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# FFT display window / averaging tests (issue #428): the pre-FFT window
# functions + cross-frame magnitude EMA in fft_display.c, plus a rect-vs-Hann
# spectral-leakage comparison through the same kissfft path the display uses.
# ---------------------------------------------------------------------------
$fftWinSrcs = @(
    "fft\kiss_fft.c","fft\kiss_fftr.c"
) | ForEach-Object { Join-Path $ft8 $_ }
$fftWinSrcs += (Join-Path $here "fft_display.c")

$srcFftWin = Join-Path $here "test_fft_window.c"
$outFftWin = Join-Path $env:TEMP "ft8_fft_window_test.exe"

& $Clang @common $srcFftWin @fftWinSrcs -o $outFftWin
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_fft_window)." }

& $outFftWin
$fftWinExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Callsign-hash recovery tests (issue #392): the pure helpers in ft8_call_hash.h
# that pull a compound call's 12-/22-bit hash out of a decoded frame so the
# seeded Java MessageHashMap can resolve an answer that the decoder returns as
# "<...>". Links only the pack/encode/message path it exercises.
# ---------------------------------------------------------------------------
$callHashSrcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c"
) | ForEach-Object { Join-Path $ft8 $_ }

$srcCallHash = Join-Path $here "test_call_hash.c"
$outCallHash = Join-Path $env:TEMP "ft8_call_hash_test.exe"

& $Clang @common $srcCallHash @callHashSrcs -o $outCallHash
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_call_hash)." }

& $outCallHash
$callHashExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Callsign hash-store tests: the per-decoder open-addressing table shared by
# ft8_decode_jni.cpp and ft2_decode_jni.cpp (ftx_hash_store.h). Guards the
# save/lookup semantics and, crucially, that a saturated table drops the insert
# instead of probing forever (the unbounded loop that used to hang the decode
# thread). Header-only under test; needs only the ft8_lib include path.
# ---------------------------------------------------------------------------
$srcHashStore = Join-Path $here "test_hash_store.c"
$outHashStore = Join-Path $env:TEMP "ft8_hash_store_test.exe"

& $Clang @common $srcHashStore -o $outHashStore
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_hash_store)." }

& $outHashStore
$hashStoreExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# EU_VHF / CONTESTING decoder tests (issue #403): the hand-rolled bit
# extraction + direct formatting for i3=0 n3=2 and i3=0 n3=6 frames. Payloads
# are assembled by an independent bit writer; callsign bits come from the
# vendored packer, so shift/mask regressions in message.c change the rendered
# text and fail here.
# ---------------------------------------------------------------------------
$contestSrcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c"
) | ForEach-Object { Join-Path $ft8 $_ }

$srcContest = Join-Path $here "test_contest_decode.c"
$outContest = Join-Path $env:TEMP "ft8_contest_decode_test.exe"

& $Clang @common $srcContest @contestSrcs -o $outContest
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_contest_decode)." }

& $outContest
$contestExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# FIR decimator tests (C++): anti-aliasing downsample that replaced the box
# filter in usb_audio_capture.cpp. Header-only, no ft8_lib deps; compiled with
# clang++ (the header is C++). Own main(); exit 0 == all pass.
# ---------------------------------------------------------------------------
$clangxx = [System.IO.Path]::ChangeExtension($Clang, $null) + "++.exe"
if (-not (Test-Path $clangxx)) { $clangxx = $Clang }  # clang can compile C++ via -x
$firSrc = Join-Path $here "test_fir_decimator.cpp"
$firOut = Join-Path $env:TEMP "ft8_fir_test.exe"
& $clangxx -std=c++14 -O2 -x c++ $firSrc -o $firOut
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (fir_decimator)." }
& $firOut
$firExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Rational resampler tests (C++): true polyphase L/M resampling for non-48 kHz
# USB capture devices (issue #364 — a floored 44.1k/12k ratio shifted every
# FT8 tone off the 6.25 Hz grid). Header-only, no ft8_lib deps.
# ---------------------------------------------------------------------------
$ratSrc = Join-Path $here "test_rational_resampler.cpp"
$ratOut = Join-Path $env:TEMP "ft8_rational_test.exe"
& $clangxx -std=c++14 -O2 -x c++ $ratSrc -o $ratOut
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (rational_resampler)." }
& $ratOut
$ratExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Decode benchmark / regression harness: replays the committed FT8 WAV corpus
# through the app's exact decode pipeline (same monitor config, candidate
# search, LDPC settings, dedup, and subtract-and-redecode loop as
# FT8SignalListener + ft8_decode_jni.cpp) and scores against WSJT-X jt9 truth
# sidecars. Fails if any corpus file matches fewer truth messages than its
# committed floor (testdata/ft8/floors.txt) — the floors ratchet up as decoder
# improvements land. See decode_bench.c's header for flags and output format.
# ---------------------------------------------------------------------------
$benchSrcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c","ft8\decode.c","ft8\ldpc.c","ft8\osd.c","ft8\unpack.c",
    "common\monitor.c","common\wave.c",
    "fft\kiss_fft.c","fft\kiss_fftr.c"
) | ForEach-Object { Join-Path $ft8 $_ }
$benchSrcs += (Join-Path $here "gfsk.c")
$benchSrcs += (Join-Path $here "ft8_subtract.c")
$benchSrcs += (Join-Path $here "ft8_fine.c")
$benchSrcs += (Join-Path $here "ft8_xslot.c")

$srcBench = Join-Path $here "decode_bench.c"
$outBench = Join-Path $env:TEMP "ft8_decode_bench.exe"

& $Clang @common $srcBench @benchSrcs -o $outBench
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (decode_bench)." }

& $outBench --self-test
$benchSelfExit = $LASTEXITCODE

$corpus = Join-Path $here "testdata\ft8"
$floors = Join-Path $corpus "floors.txt"
$wavs = Get-ChildItem (Join-Path $corpus "*.wav") | ForEach-Object { $_.FullName }
& $outBench --assert-floor $floors @wavs
$benchExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Subtraction unit tests: waterfall-domain (tone replacement) and coherent
# time-domain deep-decode subtraction properties (removal, neighbor
# preservation, masked/same-frequency co-channel recovery, bounded damage).
# ---------------------------------------------------------------------------
$subSrcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c","ft8\decode.c","ft8\ldpc.c","ft8\osd.c","ft8\unpack.c",
    "common\monitor.c",
    "fft\kiss_fft.c","fft\kiss_fftr.c"
) | ForEach-Object { Join-Path $ft8 $_ }
$subSrcs += (Join-Path $here "gfsk.c")
$subSrcs += (Join-Path $here "ft8_subtract.c")

$srcSub = Join-Path $here "test_subtract.c"
$outSub = Join-Path $env:TEMP "ft8_subtract_test.exe"

& $Clang @common $srcSub @subSrcs -o $outSub
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_subtract)." }

& $outSub
$subExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# OSD unit tests: the ordered-statistics backstop behind belief propagation
# (generator consistency, BP-failure recovery, pure-noise rejection).
# ---------------------------------------------------------------------------
$osdSrcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c","ft8\ldpc.c","ft8\osd.c","ft8\unpack.c"
) | ForEach-Object { Join-Path $ft8 $_ }

$srcOsd = Join-Path $here "test_osd.c"
$outOsd = Join-Path $env:TEMP "ft8_osd_test.exe"

& $Clang @common $srcOsd @osdSrcs -o $outOsd
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_osd)." }

& $outOsd
$osdExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Fine-demod unit tests: the fine-sync coherent retry (partial-transmission
# recovery, off-grid sync accuracy, pure-noise rejection).
# ---------------------------------------------------------------------------
$fineSrcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c","ft8\decode.c","ft8\ldpc.c","ft8\osd.c","ft8\unpack.c",
    "common\monitor.c",
    "fft\kiss_fft.c","fft\kiss_fftr.c"
) | ForEach-Object { Join-Path $ft8 $_ }
$fineSrcs += (Join-Path $here "gfsk.c")
$fineSrcs += (Join-Path $here "ft8_fine.c")

$srcFine = Join-Path $here "test_fine.c"
$outFine = Join-Path $env:TEMP "ft8_fine_test.exe"

& $Clang @common $srcFine @fineSrcs -o $outFine
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_fine)." }

& $outFine
$fineExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Cross-slot unit tests: LLR accumulation across same-parity slots and the
# message-history retry (acceptance, parity/frequency/noise rejection).
# ---------------------------------------------------------------------------
$xslotSrcs = @(
    "ft8\pack.c","ft8\encode.c","ft8\crc.c","ft8\constants.c",
    "ft8\text.c","ft8\message.c","ft8\decode.c","ft8\ldpc.c","ft8\osd.c","ft8\unpack.c"
) | ForEach-Object { Join-Path $ft8 $_ }
$xslotSrcs += (Join-Path $here "ft8_xslot.c")

$srcXslot = Join-Path $here "test_xslot.c"
$outXslot = Join-Path $env:TEMP "ft8_xslot_test.exe"

& $Clang @common $srcXslot @xslotSrcs -o $outXslot
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_xslot)." }

& $outXslot
$xslotExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# GFSK output-length tests: the synth_gfsk_output_len helper (gfsk.c) that the
# TX buffer sizing on both the Java side (GenerateFT8.waveformSampleCount) and
# the native synth_gfsk JNI guard must agree on, plus a canary check that
# synth_gfsk_offset writes exactly that many samples. Guards the heap OOB write a
# non-integral audioRate (e.g. 44100) in FT4/FT2 used to trigger. No ft8_lib deps.
# ---------------------------------------------------------------------------
$gfskLenSrcs = @()
$gfskLenSrcs += (Join-Path $here "gfsk.c")

$srcGfskLen = Join-Path $here "test_gfsk_len.c"
$outGfskLen = Join-Path $env:TEMP "ft8_gfsk_len_test.exe"

& $Clang @common $srcGfskLen @gfskLenSrcs -o $outGfskLen
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_gfsk_len)." }

& $outGfskLen
$gfskLenExit = $LASTEXITCODE

# ---------------------------------------------------------------------------
# Audio-feed tests: the per-slot monitor feed shared by ft8_decode_jni.cpp and
# ft2_decode_jni.cpp (ftx_feed.h). Guards the bounded copy into the retained
# samples buffer and, crucially, that a NULL `data` pointer (a failed JNI array
# pin) is a no-op instead of the native SIGSEGV the two float feed paths used to
# take. Links the monitor + FFT + decode path monitor_process exercises.
# ---------------------------------------------------------------------------
$feedSrcs = @(
    "common\monitor.c","ft8\decode.c","ft8\constants.c","ft8\crc.c",
    "ft8\text.c","ft8\message.c","ft8\pack.c","ft8\encode.c",
    "ft8\ldpc.c","ft8\osd.c","ft8\unpack.c","fft\kiss_fft.c","fft\kiss_fftr.c"
) | ForEach-Object { Join-Path $ft8 $_ }

$srcFeed = Join-Path $here "test_feed.c"
$outFeed = Join-Path $env:TEMP "ft8_feed_test.exe"

& $Clang @common $srcFeed @feedSrcs -o $outFeed
if ($LASTEXITCODE -ne 0) { Write-Error "Compile failed (test_feed)." }

& $outFeed
$feedExit = $LASTEXITCODE

if ($goldenExit -ne 0) { exit $goldenExit }
if ($dev625Exit -ne 0) { exit $dev625Exit }
if ($fftWinExit -ne 0) { exit $fftWinExit }
if ($callHashExit -ne 0) { exit $callHashExit }
if ($hashStoreExit -ne 0) { exit $hashStoreExit }
if ($contestExit -ne 0) { exit $contestExit }
if ($firExit -ne 0) { exit $firExit }
if ($ratExit -ne 0) { exit $ratExit }
if ($benchSelfExit -ne 0) { exit $benchSelfExit }
if ($benchExit -ne 0) { exit $benchExit }
if ($subExit -ne 0) { exit $subExit }
if ($osdExit -ne 0) { exit $osdExit }
if ($fineExit -ne 0) { exit $fineExit }
if ($xslotExit -ne 0) { exit $xslotExit }
if ($gfskLenExit -ne 0) { exit $gfskLenExit }
exit $feedExit
