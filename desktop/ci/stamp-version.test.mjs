import { describe, expect, it } from "vitest";
import {
  assertBundleSafeVersion,
  stampCargoToml,
  stampTauriConf,
} from "./stamp-version.mjs";

describe("assertBundleSafeVersion", () => {
  it("accepts release and numeric pre-release versions", () => {
    expect(assertBundleSafeVersion("0.1.2")).toBe("0.1.2");
    expect(assertBundleSafeVersion("0.1.2-1312")).toBe("0.1.2-1312");
    expect(assertBundleSafeVersion("1.0.0-65535")).toBe("1.0.0-65535");
  });

  it("rejects versions the MSI bundler would refuse", () => {
    for (const bad of ["", "0.1", "v0.1.2", "0.1.1-dev.1312", "0.1.2-65536"]) {
      expect(() => assertBundleSafeVersion(bad)).toThrow();
    }
  });
});

describe("stampTauriConf", () => {
  it("replaces only the version", () => {
    const out = JSON.parse(
      stampTauriConf('{"productName":"FT8AF","version":"0.1.0"}', "0.1.2"),
    );
    expect(out).toEqual({ productName: "FT8AF", version: "0.1.2" });
  });
});

describe("stampCargoToml", () => {
  const toml = [
    "[package]",
    'name = "ft8af"',
    'version = "0.1.0"',
    "",
    "[dependencies]",
    'serde = { version = "1" }',
    "",
  ].join("\n");

  it("rewrites the [package] version and leaves dependencies alone", () => {
    const out = stampCargoToml(toml, "0.1.2");
    expect(out).toContain('version = "0.1.2"');
    expect(out).toContain('serde = { version = "1" }');
    expect(out).not.toContain('version = "0.1.0"');
  });

  it("does not touch a version outside [package]", () => {
    const noPkgVersion = '[package]\nname = "x"\n\n[workspace]\nversion = "9"\n';
    expect(() => stampCargoToml(noPkgVersion, "0.1.2")).toThrow();
  });
});
