// Stamps the CI-computed release version into the desktop manifests before a
// release build. Without this every desktop-v* release shipped as 0.1.0: the
// tag drives the GitHub release name, but tauri.conf.json (installer/bundle
// version) and Cargo.toml (CARGO_PKG_VERSION, sent in the WSJT-X UDP
// heartbeat) were never updated.
//
// Usage (from desktop/): node ci/stamp-version.mjs <version>

import { readFileSync, writeFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

// The MSI bundler only accepts major.minor.patch with an optional numeric
// pre-release no greater than 65535, so the version must fit that on every OS.
const MSI_SAFE = /^(\d+)\.(\d+)\.(\d+)(?:-(\d+))?$/;

export function assertBundleSafeVersion(version) {
  const m = MSI_SAFE.exec(version);
  if (!m || (m[4] !== undefined && Number(m[4]) > 65535)) {
    throw new Error(
      `"${version}" is not a bundle-safe version (x.y.z or x.y.z-N, N <= 65535)`,
    );
  }
  return version;
}

export function stampTauriConf(text, version) {
  const conf = JSON.parse(text);
  conf.version = version;
  return JSON.stringify(conf, null, 2) + "\n";
}

// Rewrites only the `version` key of the [package] table.
export function stampCargoToml(text, version) {
  const lines = text.split("\n");
  let inPackage = false;
  let stamped = false;
  for (let i = 0; i < lines.length; i++) {
    const header = /^\s*\[([^\]]+)\]\s*$/.exec(lines[i]);
    if (header) {
      inPackage = header[1].trim() === "package";
      continue;
    }
    if (inPackage && /^\s*version\s*=/.test(lines[i])) {
      lines[i] = `version = "${version}"`;
      stamped = true;
      break;
    }
  }
  if (!stamped) throw new Error("Cargo.toml has no [package] version");
  return lines.join("\n");
}

function main(version) {
  assertBundleSafeVersion(version);
  const desktop = join(dirname(fileURLToPath(import.meta.url)), "..");
  const conf = join(desktop, "src-tauri", "tauri.conf.json");
  const cargo = join(desktop, "src-tauri", "Cargo.toml");
  writeFileSync(conf, stampTauriConf(readFileSync(conf, "utf8"), version));
  writeFileSync(cargo, stampCargoToml(readFileSync(cargo, "utf8"), version));
  console.log(`Stamped desktop version ${version}`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv[2] ?? "");
}
