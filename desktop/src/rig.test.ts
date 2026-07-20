import { describe, it, expect } from "vitest";
import type { HamlibRig, RigConfig } from "./ipc";
import {
  RIG_FLRIG,
  RIG_NONE,
  applySelection,
  configToSelection,
  rigName,
  rigOptions,
} from "./rig";

// list_hamlib_rigs() returns rigs already sorted case-insensitively by name.
const RIGS: HamlibRig[] = [
  { model: 3073, name: "Icom IC-7300" },
  { model: 2, name: "Yaesu FT-991" },
];

function cfg(overrides: Partial<RigConfig> = {}): RigConfig {
  return {
    backend: "none",
    port: "",
    baud: 38400,
    flrig_host: "127.0.0.1",
    flrig_port: 12345,
    hamlib_model: 1,
    hamlib_network: false,
    hamlib_address: "127.0.0.1:4992",
    ...overrides,
  };
}

describe("rigOptions", () => {
  it("pins None (default) then FLrig, then every Hamlib radio", () => {
    const opts = rigOptions(RIGS);
    expect(opts.map((o) => o.value)).toEqual([
      RIG_NONE,
      RIG_FLRIG,
      "hamlib:3073",
      "hamlib:2",
    ]);
    expect(opts[0].label).toBe("None (manual tuning)");
    expect(opts[1].label).toBe("FLrig");
    // Radio labels are the plain radio names, no backend jargon or model #.
    expect(opts[2].label).toBe("Icom IC-7300");
  });

  it("preserves the Hamlib list order (already name-sorted upstream)", () => {
    const opts = rigOptions(RIGS).slice(2);
    expect(opts.map((o) => o.label)).toEqual(["Icom IC-7300", "Yaesu FT-991"]);
  });

  it("is just None + FLrig when no Hamlib radios are available", () => {
    expect(rigOptions([]).map((o) => o.value)).toEqual([RIG_NONE, RIG_FLRIG]);
  });
});

describe("applySelection (selector → backend)", () => {
  it("None → backend none", () => {
    expect(applySelection(cfg({ backend: "hamlib" }), RIG_NONE).backend).toBe("none");
  });

  it("FLrig → backend flrig, leaving flrig host/port untouched", () => {
    const next = applySelection(cfg({ flrig_host: "10.0.0.5", flrig_port: 9999 }), RIG_FLRIG);
    expect(next.backend).toBe("flrig");
    expect(next.flrig_host).toBe("10.0.0.5");
    expect(next.flrig_port).toBe(9999);
  });

  it("a Hamlib radio → backend hamlib with that model id", () => {
    const next = applySelection(cfg(), "hamlib:3073");
    expect(next.backend).toBe("hamlib");
    expect(next.hamlib_model).toBe(3073);
  });

  it("preserves shared connection fields when switching to a Hamlib radio", () => {
    const next = applySelection(cfg({ port: "/dev/ttyACM0", baud: 115200 }), "hamlib:2");
    expect(next.port).toBe("/dev/ttyACM0");
    expect(next.baud).toBe(115200);
  });

  it("falls back to none for an unrecognized / malformed value", () => {
    expect(applySelection(cfg({ backend: "hamlib" }), "serial").backend).toBe("none");
    expect(applySelection(cfg({ backend: "hamlib" }), "hamlib:notanumber").backend).toBe("none");
  });
});

describe("configToSelection (backend → selector)", () => {
  it("round-trips none / flrig / hamlib", () => {
    expect(configToSelection(cfg({ backend: "none" }))).toBe(RIG_NONE);
    expect(configToSelection(cfg({ backend: "flrig" }))).toBe(RIG_FLRIG);
    expect(configToSelection(cfg({ backend: "hamlib", hamlib_model: 3073 }))).toBe("hamlib:3073");
  });

  it("applySelection then configToSelection is stable for a Hamlib radio", () => {
    const c = applySelection(cfg(), "hamlib:3073");
    expect(configToSelection(c)).toBe("hamlib:3073");
  });
});

describe("rigName", () => {
  it("resolves a Hamlib model id to its radio name", () => {
    expect(rigName(cfg({ backend: "hamlib", hamlib_model: 3073 }), RIGS)).toBe("Icom IC-7300");
  });

  it("falls back to a generic label for an unknown model", () => {
    expect(rigName(cfg({ backend: "hamlib", hamlib_model: 9999 }), RIGS)).toBe("Hamlib #9999");
  });

  it("labels the non-Hamlib backends", () => {
    expect(rigName(cfg({ backend: "none" }), RIGS)).toBe("None (manual tuning)");
    expect(rigName(cfg({ backend: "flrig" }), RIGS)).toBe("FLrig");
  });

  it("never returns undefined for a legacy/unknown backend", () => {
    // A persisted config with the removed "serial" backend must not surface as
    // "Connecting undefined"; it falls back to the None label.
    const legacy = cfg({ backend: "serial" as unknown as RigConfig["backend"] });
    expect(rigName(legacy, RIGS)).toBe("None (manual tuning)");
  });
});

describe("legacy config normalization (load-time)", () => {
  it("maps a removed 'serial' backend to none while keeping connection fields", () => {
    // Mirrors the load-time normalize: applySelection(cfg, configToSelection(cfg)).
    const legacy = cfg({
      backend: "serial" as unknown as RigConfig["backend"],
      port: "COM7",
      baud: 19200,
      flrig_host: "192.168.1.5",
    });
    const normalized = applySelection(legacy, configToSelection(legacy));
    expect(normalized.backend).toBe("none");
    // Connection fields are preserved so re-selecting a rig keeps them.
    expect(normalized.port).toBe("COM7");
    expect(normalized.baud).toBe(19200);
    expect(normalized.flrig_host).toBe("192.168.1.5");
  });

  it("leaves a valid hamlib config unchanged through the round-trip", () => {
    const valid = cfg({ backend: "hamlib", hamlib_model: 3073 });
    const normalized = applySelection(valid, configToSelection(valid));
    expect(normalized.backend).toBe("hamlib");
    expect(normalized.hamlib_model).toBe(3073);
  });
});
