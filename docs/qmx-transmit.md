# Transmitting with the QRP Labs QMX

## Summary

On some phones FT8AF cannot get enough audio level into the QMX for it to
transmit. The QMX can make the FT8/FT4 signal itself from CAT commands, so
FT8AF now offers that as an option: **Settings > Radio & Audio > Audio >
"QMX: Send Tones over CAT"**. With it on, an over is sent as one `TA` command
per symbol and no audio goes to the radio at all.

Recommended QMX setup:

| Setting | Value |
|---|---|
| Rig | QRP Labs QMX (Hamlib backend), control mode CAT |
| Audio Input | the QMX's regular USB audio entry |
| Audio Output | the QMX's regular USB audio entry (**not** "(USB direct)") |
| TX audio channel | Left (set automatically on first QMX connect) |
| QMX: Send Tones over CAT | **On** |

## The symptom

The QMX shows its audio input level as bars and needs **3 bars** in Digi
mode before it produces output. On a motorola edge 2022 (Android 15,
MediaTek) FT8AF only reached 1–2 bars on CQ and TUNE. The radio keyed, but
little or no RF went out. Another Android FT8 app on the same phone, cable
and radio reached 3 bars.

## What was ruled out

Every step below was measured on the phone with
`dumpsys media.audio_flinger`, `dumpsys audio`, `logcat` and FT8AF's own
`debug.log`:

- **FT8AF's waveform** is full scale. The GFSK synthesiser writes `sinf(phi)`
  with only a short ramp at each end, and TX volume and TUNE level were 100%.
- **Earlier fixes are all in place.** TX channel is Left (`00fb7dba`), RX
  capture is paused while transmitting so the USB HAL can open playback
  (`27b31fd1`), and the phone's media volume for the USB device is 15/15.
- **The phone's audio stack loses level that the app can't get back.** The
  TX track showed **-6 dB** of gain with an OEM Dolby "Music Listener" effect
  enabled on its session. Android's volume curve for USB at index 15 is
  0 dB, so the cut is not the volume curve. The Dolby settings app was
  disabled on that phone, so the user had no switch for it, and adb cannot
  re-enable a system app.
- **Compensating didn't work.** A LoudnessEnhancer on the TX session (+3 and
  +6 dB) left the radio at 2 bars. So did taking control of the Dolby effect
  on FT8AF's own session through AudioEffect's hidden constructor and
  disabling it. The recording confirmed the effect was off during that test.
  Neither change was kept.
- **USB-direct output (libusb) doesn't help either.** It failed outright at
  first: every iso submit returned `LIBUSB_ERROR_IO`, and a STOP afterwards
  crashed the app (both fixed, see below). Once it worked it still gave 1 bar.
  It also detaches the kernel audio driver, so Android's RX capture is gone
  until the radio is replugged.

## What works: QMX CAT tones

The other app's logcat gave it away:

```
[QMX_CAT_TONES] QMX CAT TA in DIGI: 79 symbols of 160.000 ms, base 1431.0 Hz,
spacing 6.2500 Hz, first tone 129 ms into the transmit thread (no audio to the radio)
```

The QMX CAT manual (firmware 1.04) documents the command:

- `TAnnnn.nn;` in Digi mode transmits a single carrier at USB dial frequency
  plus that audio frequency (fractional Hz allowed).
- The first `TA` after `TX;` keys down with a shaped (Blackman-Harris)
  envelope.
- `TA0;` (anything under 10 Hz) keys up with a shaped envelope.
- The host sequence is `FA` (dial), then `TX;`, then `TA` repeatedly.

FT8AF's implementation:

- `QmxCatTones` (pure helper) formats the commands and handles the tone math:
  tone *n* = base + *n* / symbol period, i.e. 6.25 Hz spacing for FT8 and
  20.833 Hz for FT4. It also works out how many symbols to skip on a late
  start.
- `FT8TransmitSignal.playViaQmxCatTones` encodes the message to its channel
  symbols (79 for FT8, 105 for FT4) and sends one `TA` per symbol on the
  symbol clock. Repeated tones are skipped. It sleeps until about 1 ms before
  each symbol is due, then spins to the exact time. It holds the last symbol
  for its full period and finishes with `TA0;`. PTT (`TX;` / `RX;`) stays on
  the normal keying path.
- `FT8TransmitSignal.runQmxCatTune` makes TUNE send one steady `TA` at the TX
  offset until the tune timer or the operator stops it, then `TA0;`.
- `OnDoTransmitted.supportQmxCatTones()` / `sendQmxCatCommand()` are
  implemented by `MainViewModel`. The path is active only when the setting is
  on, control mode is CAT, and the connected rig is a Hamlib QMX
  (`HamlibRig.isQmx()`). Commands go straight to the rig's serial connector,
  whose `sendData` is synchronized with Hamlib's own CAT traffic.
- The setting is persisted under config key `qmxCatTones` and is off by
  default.

Measured on the motorola: an FT8 CQ goes out as **79 symbols in 66 `TA`
commands**, with **0.3 ms worst-case** symbol lateness. The radio shows
**3 bars** on CQ and TUNE, and RX keeps decoding after every over.

## Direct-USB fixes found along the way

These are in the same commit and stand on their own:

- **RX capture now pauses for USB-direct TX too.**
  `MainViewModel.shouldPauseRxCaptureForTx` used to exclude USB-direct
  output. With the HAL's capture still open on the same rig, every libusb iso
  submit failed with `LIBUSB_ERROR_IO`.
- **Crash after a failed direct-USB write.** `nativeWrite` publishes its
  libusb context so STOP can wake its event loop. Three early-exit paths freed
  the context without unpublishing it, so the next cancel crashed in
  `libusb_interrupt_event_handler`. Every exit now unpublishes first.

## Known limits

- Only the QMX (Hamlib model `QRPLABS_QMX_MODEL_ID`) is supported. Other QRP
  Labs radios with the same command (e.g. the QDX on newer firmware) could be
  added if confirmed.
- The TUNE level setting doesn't apply with CAT tones: the radio's own
  power setting decides the output.
- The audio TX path is unchanged. With the setting off, the QMX still depends
  on the phone delivering enough audio level.
