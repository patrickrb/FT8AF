// Pure, framework-free logbook helpers. The React `LogScreen` is a thin wrapper
// around these so the decision/normalization logic stays unit-testable (see
// CLAUDE.md: Composable/JSX code can't be tested directly — extract the logic).

import type { QsoRecord } from "./ipc";

/** Logbook confirmation filter, mirroring the Android `FilterDialog`. */
export type ConfirmFilter = "all" | "confirmed" | "unconfirmed";

/** Editable fields surfaced by the Edit-QSO dialog (all ADIF strings). */
export interface QsoEditForm {
  call: string;
  gridsquare: string;
  mode: string;
  rst_sent: string;
  rst_rcvd: string;
  qso_date: string;
  time_on: string;
  qso_date_off: string;
  time_off: string;
  band: string;
  freq: string;
  station_callsign: string;
  my_gridsquare: string;
  comment: string;
  confirmed: boolean;
}

/**
 * Convert an `<input type="date">` value ("YYYY-MM-DD") to an ADIF date
 * ("YYYYMMDD"). Empty input yields an empty string (open bound). Anything that
 * isn't a plain `YYYY-MM-DD` is passed through with hyphens stripped so a
 * hand-typed ADIF date still works.
 */
export function dateInputToAdif(value: string): string {
  const v = (value ?? "").trim();
  if (!v) return "";
  return v.replace(/-/g, "");
}

/** Inverse of {@link dateInputToAdif}: ADIF "YYYYMMDD" -> "YYYY-MM-DD". */
export function adifToDateInput(value: string): string {
  const v = (value ?? "").trim();
  if (/^\d{8}$/.test(v)) return `${v.slice(0, 4)}-${v.slice(4, 6)}-${v.slice(6, 8)}`;
  return v;
}

/**
 * Build the `{start, end}` args for the `export_adif` command from the two
 * date pickers. Returns `undefined` bounds (not empty strings) when a picker is
 * blank so the Rust side treats them as fully open.
 */
export function buildExportArgs(
  startInput: string,
  endInput: string,
): { start?: string; end?: string } {
  const start = dateInputToAdif(startInput);
  const end = dateInputToAdif(endInput);
  return {
    start: start || undefined,
    end: end || undefined,
  };
}

/**
 * Normalize an edit-dialog form into a `QsoRecord` ready for `save_qso`.
 * Callsigns and grids are upper-cased and trimmed to match how the app logs
 * QSOs automatically, so an edit doesn't split a station into two dedup keys.
 */
export function buildQsoRecord(
  form: QsoEditForm,
  id: number | null | undefined,
): QsoRecord {
  const up = (s: string) => (s ?? "").trim().toUpperCase();
  const t = (s: string) => (s ?? "").trim();
  return {
    id: id ?? null,
    call: up(form.call),
    gridsquare: up(form.gridsquare),
    mode: up(form.mode) || "FT8",
    rst_sent: t(form.rst_sent),
    rst_rcvd: t(form.rst_rcvd),
    qso_date: t(form.qso_date),
    time_on: t(form.time_on),
    qso_date_off: t(form.qso_date_off),
    time_off: t(form.time_off),
    band: t(form.band),
    freq: t(form.freq),
    station_callsign: up(form.station_callsign),
    my_gridsquare: up(form.my_gridsquare),
    comment: (form.comment ?? "").trim(),
    confirmed: !!form.confirmed,
  };
}

/** Seed an edit form from an existing record (or blanks for a new QSO). */
export function formFromQso(r?: QsoRecord | null): QsoEditForm {
  return {
    call: r?.call ?? "",
    gridsquare: r?.gridsquare ?? "",
    mode: r?.mode ?? "FT8",
    rst_sent: r?.rst_sent ?? "",
    rst_rcvd: r?.rst_rcvd ?? "",
    qso_date: r?.qso_date ?? "",
    time_on: r?.time_on ?? "",
    qso_date_off: r?.qso_date_off ?? "",
    time_off: r?.time_off ?? "",
    band: r?.band ?? "",
    freq: r?.freq ?? "",
    station_callsign: r?.station_callsign ?? "",
    my_gridsquare: r?.my_gridsquare ?? "",
    comment: r?.comment ?? "",
    confirmed: !!r?.confirmed,
  };
}

/**
 * Client-side fallback filter (substring call match + confirmation), matching
 * the Rust `qso_matches` predicate. Used when filtering an already-loaded page
 * without a server round-trip.
 */
export function filterQsos(
  rows: QsoRecord[],
  search: string,
  filter: ConfirmFilter,
): QsoRecord[] {
  const q = (search ?? "").trim().toUpperCase();
  return rows.filter((r) => {
    const callOk = !q || (r.call ?? "").toUpperCase().includes(q);
    const confirmOk =
      filter === "all" ||
      (filter === "confirmed" ? r.confirmed : !r.confirmed);
    return callOk && confirmOk;
  });
}
