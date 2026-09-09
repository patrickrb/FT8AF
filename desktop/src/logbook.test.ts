import { describe, it, expect } from "vitest";
import {
  adifToDateInput,
  buildExportArgs,
  buildQsoRecord,
  dateInputToAdif,
  filterQsos,
  formFromQso,
  isLogFiltered,
  qsoCountLabel,
  type QsoEditForm,
} from "./logbook";
import type { QsoRecord } from "./ipc";

function qso(over: Partial<QsoRecord>): QsoRecord {
  return {
    id: 1,
    call: "K1ABC",
    gridsquare: "FN42",
    mode: "FT8",
    rst_sent: "-12",
    rst_rcvd: "-08",
    qso_date: "20260604",
    time_on: "120000",
    qso_date_off: "",
    time_off: "",
    band: "20m",
    freq: "14.074",
    station_callsign: "K0XYZ",
    my_gridsquare: "EN37",
    comment: "",
    confirmed: false,
    ...over,
  };
}

describe("dateInputToAdif / adifToDateInput", () => {
  it("converts date-picker values to ADIF and back", () => {
    expect(dateInputToAdif("2026-06-15")).toBe("20260615");
    expect(dateInputToAdif("")).toBe("");
    expect(dateInputToAdif("  ")).toBe("");
    expect(adifToDateInput("20260615")).toBe("2026-06-15");
    expect(adifToDateInput("")).toBe("");
    // Non-8-digit values pass through.
    expect(adifToDateInput("2026")).toBe("2026");
  });
});

describe("buildExportArgs", () => {
  it("omits blank bounds so they are open", () => {
    expect(buildExportArgs("", "")).toEqual({ start: undefined, end: undefined });
    expect(buildExportArgs("2026-06-01", "")).toEqual({
      start: "20260601",
      end: undefined,
    });
    expect(buildExportArgs("2026-06-01", "2026-06-30")).toEqual({
      start: "20260601",
      end: "20260630",
    });
  });
});

describe("buildQsoRecord", () => {
  it("upper-cases call/grid, trims, defaults mode, carries id + confirmed", () => {
    const form: QsoEditForm = {
      call: " k1abc ",
      gridsquare: "fn42",
      mode: "",
      rst_sent: " -12 ",
      rst_rcvd: "-08",
      qso_date: "20260604",
      time_on: "120000",
      qso_date_off: "",
      time_off: "",
      band: "20m",
      freq: "14.074",
      station_callsign: "k0xyz",
      my_gridsquare: "en37",
      comment: " hi ",
      confirmed: true,
    };
    const rec = buildQsoRecord(form, 7);
    expect(rec.id).toBe(7);
    expect(rec.call).toBe("K1ABC");
    expect(rec.gridsquare).toBe("FN42");
    expect(rec.mode).toBe("FT8"); // blank -> default
    expect(rec.rst_sent).toBe("-12");
    expect(rec.station_callsign).toBe("K0XYZ");
    expect(rec.my_gridsquare).toBe("EN37");
    expect(rec.comment).toBe("hi");
    expect(rec.confirmed).toBe(true);
  });

  it("uses null id for a new QSO", () => {
    const rec = buildQsoRecord(formFromQso(null), undefined);
    expect(rec.id).toBeNull();
    expect(rec.mode).toBe("FT8");
    expect(rec.confirmed).toBe(false);
  });
});

describe("formFromQso round-trip", () => {
  it("seeds a form from a record and rebuilds an equivalent record", () => {
    const original = qso({ confirmed: true, comment: "gm" });
    const rebuilt = buildQsoRecord(formFromQso(original), original.id);
    expect(rebuilt).toEqual(original);
  });
});

describe("filterQsos", () => {
  const rows = [
    qso({ id: 1, call: "K1ABC", confirmed: true }),
    qso({ id: 2, call: "K1XYZ", confirmed: false }),
    qso({ id: 3, call: "W1AW", confirmed: true }),
  ];

  it("matches callsign substrings case-insensitively", () => {
    expect(filterQsos(rows, "k1", "all").map((r) => r.id)).toEqual([1, 2]);
    expect(filterQsos(rows, "  ", "all").length).toBe(3);
    expect(filterQsos(rows, "zzz", "all").length).toBe(0);
  });

  it("filters by confirmation status", () => {
    expect(filterQsos(rows, "", "confirmed").map((r) => r.id)).toEqual([1, 3]);
    expect(filterQsos(rows, "", "unconfirmed").map((r) => r.id)).toEqual([2]);
  });

  it("combines search and confirmation", () => {
    expect(filterQsos(rows, "k1", "confirmed").map((r) => r.id)).toEqual([1]);
  });
});

describe("isLogFiltered / qsoCountLabel", () => {
  it("detects an active search or non-all filter", () => {
    expect(isLogFiltered("", "all")).toBe(false);
    expect(isLogFiltered("   ", "all")).toBe(false);
    expect(isLogFiltered("k1", "all")).toBe(true);
    expect(isLogFiltered("", "confirmed")).toBe(true);
    expect(isLogFiltered("", "unconfirmed")).toBe(true);
  });

  it("shows the plain total when unfiltered and 'shown of total' when filtered", () => {
    expect(qsoCountLabel(42, 42, false)).toBe("42");
    // Filtered: the label reflects the rows actually shown, not the full total.
    expect(qsoCountLabel(42, 3, true)).toBe("3 of 42");
  });
});
