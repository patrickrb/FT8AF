// Pure logic for the unified "Rig" picker, extracted from App.tsx so it can be
// unit-tested (the Settings component that renders it can't be).
//
// The Settings screen leads with a single **Rig** dropdown listing the actual
// radios; which CAT library talks to the radio (the `backend`) is an internal
// detail picked from the selection. Each dropdown entry is an opaque string:
//
//   "none"           → RigBackend "none"  — manual tuning (the default)
//   "flrig"          → RigBackend "flrig" — network CAT via FLrig XML-RPC
//   "hamlib:<model>" → RigBackend "hamlib" with hamlib_model = <model>
//
import type { HamlibRig, RigConfig } from "./ipc";

export const RIG_NONE = "none";
export const RIG_FLRIG = "flrig";
const HAMLIB_PREFIX = "hamlib:";

export interface RigOption {
  value: string;
  label: string;
}

/**
 * Build the Rig dropdown options: **None** (default) and **FLrig** pinned at
 * the top, followed by every Hamlib-supported radio by name. `hamlibRigs` is
 * already sorted case-insensitively by name by `list_hamlib_rigs()`.
 */
export function rigOptions(hamlibRigs: HamlibRig[]): RigOption[] {
  return [
    { value: RIG_NONE, label: "None (manual tuning)" },
    { value: RIG_FLRIG, label: "FLrig" },
    ...hamlibRigs.map((r) => ({ value: `${HAMLIB_PREFIX}${r.model}`, label: r.name })),
  ];
}

/**
 * Map a config back to the selector value so the dropdown reflects a persisted
 * config. A legacy `serial` backend (removed) has no option, so it falls back
 * to None.
 */
export function configToSelection(cfg: RigConfig): string {
  switch (cfg.backend) {
    case "flrig":
      return RIG_FLRIG;
    case "hamlib":
      return `${HAMLIB_PREFIX}${cfg.hamlib_model}`;
    default:
      return RIG_NONE;
  }
}

/**
 * Apply a selector value to the config: set `backend` (and `hamlib_model` for a
 * Hamlib radio) while leaving the connection fields (serial port/baud, network
 * address, FLrig host/port) untouched. An unrecognized value maps to None.
 */
export function applySelection(cfg: RigConfig, selection: string): RigConfig {
  if (selection === RIG_FLRIG) {
    return { ...cfg, backend: "flrig" };
  }
  if (selection.startsWith(HAMLIB_PREFIX)) {
    const model = parseInt(selection.slice(HAMLIB_PREFIX.length), 10);
    if (!Number.isNaN(model)) {
      return { ...cfg, backend: "hamlib", hamlib_model: model };
    }
  }
  return { ...cfg, backend: "none" };
}

/**
 * Human-readable radio name for the current config, for the connect-status line
 * ("Icom IC-7300" rather than "model 3073"). Falls back to a generic label when
 * the model isn't in the enumerated list.
 */
export function rigName(cfg: RigConfig, hamlibRigs: HamlibRig[]): string {
  switch (cfg.backend) {
    case "none":
      return "None (manual tuning)";
    case "flrig":
      return "FLrig";
    case "hamlib":
      return (
        hamlibRigs.find((r) => r.model === cfg.hamlib_model)?.name ??
        `Hamlib #${cfg.hamlib_model}`
      );
  }
}
