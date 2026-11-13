import { describe, it, expect } from "vitest";
import type { AudioDevice } from "./ipc";
import { audioDeviceLabel } from "./audioDevice";

function dev(name: string): AudioDevice {
  return { id: name, name, is_default: false, default_sample_rate: 48000, channels: 1 };
}

describe("audioDeviceLabel", () => {
  it("names generic C-Media ALSA cards and recommends the plughw variant", () => {
    expect(audioDeviceLabel(dev("plughw:CARD=Device,DEV=0"))).toBe(
      "USB audio codec (C-Media, e.g. DE-19) — plughw:CARD=Device,DEV=0 — recommended",
    );
    expect(audioDeviceLabel(dev("hw:CARD=Device,DEV=0"))).toBe(
      "USB audio codec (C-Media, e.g. DE-19) — hw:CARD=Device,DEV=0",
    );
  });

  it("gives the Linux sound servers readable names", () => {
    expect(audioDeviceLabel(dev("pipewire"))).toBe("PipeWire sound server");
    expect(audioDeviceLabel(dev("default"))).toBe("Linux system default");
  });

  it("passes every other device name through unchanged", () => {
    expect(audioDeviceLabel(dev("Speakers (Realtek(R) Audio)"))).toBe("Speakers (Realtek(R) Audio)");
    expect(audioDeviceLabel(dev("plughw:CARD=PCH,DEV=0"))).toBe("plughw:CARD=PCH,DEV=0");
  });
});
