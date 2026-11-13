import type { AudioDevice } from "./ipc";

/**
 * Display label for an audio device in the Settings pickers.
 *
 * ALSA exposes generic C-Media USB codecs (the DE-19 among them, but also many
 * other CM108-style interfaces) with the card name "Device". Keep the real
 * identifier visible while making it recognizable, and steer toward the
 * `plughw:` variant, which resamples to the rate we ask for.
 */
export function audioDeviceLabel(device: AudioDevice): string {
  if (device.name.includes("CARD=Device")) {
    const recommended = device.name.startsWith("plughw:") ? " — recommended" : "";
    return `USB audio codec (C-Media, e.g. DE-19) — ${device.name}${recommended}`;
  }
  if (device.name === "pipewire") return "PipeWire sound server";
  if (device.name === "default") return "Linux system default";
  return device.name;
}
