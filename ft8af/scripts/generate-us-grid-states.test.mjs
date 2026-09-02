/**
 * Unit tests for the pure logic in generate-us-grid-states.mjs — grid naming,
 * point-in-polygon, majority/close-margin classification, and the sweep.
 * Fixtures are tiny axis-aligned rectangles, so every expected count is exact.
 *
 * Run with:  node --test "scripts/*.test.mjs"   (from the ft8af dir)
 */

import { test } from "node:test";
import assert from "node:assert/strict";
import {
  BASE_N,
  buildTable,
  candidates,
  classifyCell,
  geometryBbox,
  gridName,
  pointInGeometry,
  pointInPolygon,
  pointInRing,
  sample,
  winner,
} from "./generate-us-grid-states.mjs";

/** Closed rectangular ring in [lon, lat] order. */
const rect = (minLon, minLat, maxLon, maxLat) => [
  [minLon, minLat],
  [maxLon, minLat],
  [maxLon, maxLat],
  [minLon, maxLat],
  [minLon, minLat],
];

const polygon = (...rings) => ({ type: "Polygon", coordinates: rings });

const stateOf = (postal, geometry) => ({ postal, bbox: geometryBbox(geometry), geometry });

/* ---- gridName ---- */

test("gridName: origin of the grid is AA00", () => {
  assert.equal(gridName(-180, -90), "AA00");
});

test("gridName: the cells the old hand-written table got wrong", () => {
  // I-70 across western Kansas — the EM09/DM99 pair the PR exists to fix.
  assert.equal(gridName(-100, 39), "EM09");
  assert.equal(gridName(-102, 39), "DM99");
});

test("gridName: Honolulu cell used as the Kotlin test anchor", () => {
  assert.equal(gridName(-158, 21), "BL11");
});

/* ---- point-in-polygon ---- */

test("pointInRing: inside and outside a square", () => {
  const ring = rect(0, 0, 4, 4);
  assert.equal(pointInRing(2, 2, ring), true);
  assert.equal(pointInRing(5, 2, ring), false);
  assert.equal(pointInRing(2, -1, ring), false);
});

test("pointInPolygon: a hole ring punches out the interior", () => {
  const rings = [rect(0, 0, 4, 4), rect(1, 1, 3, 3)];
  assert.equal(pointInPolygon(2, 2, rings), false); // inside the hole
  assert.equal(pointInPolygon(0.5, 2, rings), true); // between outer and hole
  assert.equal(pointInPolygon(5, 2, rings), false);
});

test("pointInGeometry: MultiPolygon checks every part", () => {
  const geometry = {
    type: "MultiPolygon",
    coordinates: [[rect(0, 0, 4, 4)], [rect(10, 0, 14, 4)]],
  };
  assert.equal(pointInGeometry(2, 2, geometry), true);
  assert.equal(pointInGeometry(12, 2, geometry), true);
  assert.equal(pointInGeometry(7, 2, geometry), false); // the gap between parts
});

test("geometryBbox: spans all parts of a MultiPolygon", () => {
  const geometry = {
    type: "MultiPolygon",
    coordinates: [[rect(0, 0, 4, 4)], [rect(10, 0, 14, 4)]],
  };
  assert.deepEqual(geometryBbox(geometry), { minLon: 0, minLat: 0, maxLon: 14, maxLat: 4 });
});

/* ---- candidates / sample / winner ---- */

test("candidates: keeps only states whose bbox overlaps the cell", () => {
  const inCell = stateOf("IN", polygon(rect(0.5, 0.2, 1.5, 0.8)));
  const outside = stateOf("OUT", polygon(rect(5, 0, 6, 1)));
  assert.deepEqual(candidates(0, 0, [inCell, outside]), [inCell]);
});

test("sample: a state covering the whole cell owns every lattice point", () => {
  const s = stateOf("KS", polygon(rect(0, 0, 2, 1)));
  const hits = sample(0, 0, [s], BASE_N);
  assert.equal(hits.get("KS"), BASE_N * BASE_N);
});

test("winner: empty hits (open ocean) is null", () => {
  assert.equal(winner(new Map()), null);
});

test("winner: a decisive majority is not close", () => {
  assert.deepEqual(winner(new Map([["KS", 90], ["NE", 10]])), { postal: "KS", close: false });
});

test("winner: a narrow lead is flagged close", () => {
  // lead/total = 10/100 = 0.1 < CLOSE_MARGIN (0.2)
  assert.deepEqual(winner(new Map([["KS", 55], ["NE", 45]])), { postal: "KS", close: true });
});

/* ---- classifyCell ---- */

test("classifyCell: no candidates or no land hits gives null", () => {
  assert.equal(classifyCell(0, 0, []), null);
  // Candidate whose polygon lies outside the cell: bbox pre-filter yields no hits.
  const faraway = stateOf("XX", polygon(rect(10, 0, 12, 1)));
  assert.equal(classifyCell(0, 0, [faraway]), null);
});

test("classifyCell: a close split densifies and the larger state wins", () => {
  // Split the 2°×1° cell at lon 1.1: state A owns 55 %, state B 45 %. On the
  // base 12×12 lattice A gets 7 columns to B's 5 — lead 24/144 ≈ 0.17, under
  // CLOSE_MARGIN — so the fine pass must run, and it must still pick A.
  const a = stateOf("AA", polygon(rect(0, 0, 1.1, 1)));
  const b = stateOf("BB", polygon(rect(1.1, 0, 2, 1)));
  const base = winner(sample(0, 0, [a, b], BASE_N));
  assert.equal(base.close, true); // proves this fixture exercises the densification path
  assert.equal(classifyCell(0, 0, [a, b]), "AA");
});

/* ---- buildTable ---- */

test("buildTable: sweeps every cell a state touches and sorts the keys", () => {
  // One rectangle spanning two adjacent cells: JJ00 (lon 0–2) and JJ10 (lon 2–4).
  const s = stateOf("KS", polygon(rect(0.5, 0.2, 3.5, 0.8)));
  const { table, cells } = buildTable([s]);
  assert.deepEqual(table, { JJ00: "KS", JJ10: "KS" });
  assert.equal(cells, 2);
  assert.deepEqual(Object.keys(table), ["JJ00", "JJ10"]);
});

test("buildTable: a no-land cell is examined and then omitted, not emitted", () => {
  // Two islands in cells JJ00 and JJ20; their shared bbox drags the sweep and
  // the candidate filter through the all-water middle cell JJ10, which must be
  // dropped from the output — Hawaii's real geometry hits this path.
  const s = stateOf("HI", {
    type: "MultiPolygon",
    coordinates: [[rect(0.2, 0.2, 0.8, 0.8)], [rect(4.2, 0.2, 4.8, 0.8)]],
  });
  const { table, cells } = buildTable([s]);
  assert.deepEqual(table, { JJ00: "HI", JJ20: "HI" });
  assert.equal(cells, 3); // JJ10 was examined…
  assert.equal("JJ10" in table, false); // …and dropped
});
