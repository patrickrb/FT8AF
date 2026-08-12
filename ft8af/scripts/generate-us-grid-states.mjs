#!/usr/bin/env node
/**
 * Regenerate app/src/main/assets/us_grid_states.json — the 4-character
 * Maidenhead grid → US state table behind UsStateLookup (Worked All States in
 * the decode list, and the rover's states-visited labels in ROTA trips).
 *
 * The table this replaced was hand-written and wrong for ~25 of 513 cells —
 * famously mapping EM09 and DM99 (I-70 across western Kansas) to Nebraska, so
 * every Kansas trip "visited" NE. This script derives each cell's state from
 * real boundary polygons instead: sample a lattice of points inside the 2°×1°
 * cell, point-in-polygon each against every state, and let the state with the
 * most hits own the cell. Cells with no hits (open ocean, Canada, Mexico) are
 * omitted — UsStateLookup returns null for them, which is correct.
 *
 * A borderline majority is decided on a denser lattice before it's trusted, so
 * a cell split ~50/50 between two states is settled by area, not sampling
 * noise. Either answer is defensible for a cell a state line runs through; what
 * matters is that the listed state genuinely covers the cell.
 *
 * Boundary source: the RTOTA site's public/map/us-states.json (GeoJSON with
 * properties.postal, built by that repo's scripts/fetch-map-data.mjs from
 * us-atlas states-10m). Using the same file the site draws and classifies with
 * keeps one notion of where Kansas ends across the app and the server.
 *
 * Usage:
 *   node scripts/generate-us-grid-states.mjs <path-to-us-states.json>
 *   node scripts/generate-us-grid-states.mjs   # tries the sibling checkout
 */

import { readFile, writeFile } from "node:fs/promises";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const OUT = join(here, "..", "app", "src", "main", "assets", "us_grid_states.json");
const DEFAULT_STATES = join(here, "..", "..", "..", "road-trips-on-the-air-site", "public", "map", "us-states.json");

/** Lattice size for the first pass; squared = samples per cell. */
const BASE_N = 12;
/** Densified lattice for cells where the top two states are close. */
const FINE_N = 32;
/** "Close" = winner leads runner-up by less than this share of land hits. */
const CLOSE_MARGIN = 0.2;

const statesPath = process.argv[2] ?? DEFAULT_STATES;
const collection = JSON.parse(await readFile(statesPath, "utf8"));

/* ---- point-in-polygon (even-odd ray cast, same as the site's lib/map/pip.ts) ---- */

function pointInRing(lon, lat, ring) {
  let inside = false;
  for (let i = 0, j = ring.length - 1; i < ring.length; j = i++) {
    const [xi, yi] = ring[i];
    const [xj, yj] = ring[j];
    if (yi > lat !== yj > lat && lon < ((xj - xi) * (lat - yi)) / (yj - yi) + xi) inside = !inside;
  }
  return inside;
}

function pointInPolygon(lon, lat, rings) {
  let inside = false;
  for (const ring of rings) if (pointInRing(lon, lat, ring)) inside = !inside;
  return inside;
}

function pointInGeometry(lon, lat, geometry) {
  if (geometry.type === "Polygon") return pointInPolygon(lon, lat, geometry.coordinates);
  return geometry.coordinates.some((rings) => pointInPolygon(lon, lat, rings));
}

function geometryBbox(geometry) {
  const box = { minLon: Infinity, minLat: Infinity, maxLon: -Infinity, maxLat: -Infinity };
  const walk = (coords) => {
    if (typeof coords[0] === "number") {
      const [lon, lat] = coords;
      if (lon < box.minLon) box.minLon = lon;
      if (lon > box.maxLon) box.maxLon = lon;
      if (lat < box.minLat) box.minLat = lat;
      if (lat > box.maxLat) box.maxLat = lat;
      return;
    }
    for (const c of coords) walk(c);
  };
  walk(geometry.coordinates);
  return box;
}

const states = collection.features.map((f) => ({
  postal: f.properties.postal,
  bbox: geometryBbox(f.geometry),
  geometry: f.geometry,
}));

/* ---- Maidenhead cells ---- */

const gridName = (west, south) => {
  const lonField = Math.floor((west + 180) / 20);
  const latField = Math.floor((south + 90) / 10);
  const lonDigit = Math.floor(((west + 180) % 20) / 2);
  const latDigit = Math.floor((south + 90) % 10);
  return (
    String.fromCharCode(65 + lonField) +
    String.fromCharCode(65 + latField) +
    String(lonDigit) +
    String(latDigit)
  );
};

/** States whose bbox overlaps the cell — the only ones worth ray-casting. */
function candidates(west, south) {
  return states.filter(
    (s) => s.bbox.minLon < west + 2 && s.bbox.maxLon > west && s.bbox.minLat < south + 1 && s.bbox.maxLat > south,
  );
}

/** Hit counts per state over an n×n interior lattice. */
function sample(west, south, cands, n) {
  const hits = new Map();
  for (let i = 0; i < n; i++) {
    for (let j = 0; j < n; j++) {
      const lon = west + ((i + 0.5) / n) * 2;
      const lat = south + (j + 0.5) / n;
      for (const s of cands) {
        if (
          lon >= s.bbox.minLon && lon <= s.bbox.maxLon &&
          lat >= s.bbox.minLat && lat <= s.bbox.maxLat &&
          pointInGeometry(lon, lat, s.geometry)
        ) {
          hits.set(s.postal, (hits.get(s.postal) ?? 0) + 1);
          break; // states don't overlap; first containing polygon owns the point
        }
      }
    }
  }
  return hits;
}

function winner(hits) {
  const ranked = [...hits.entries()].sort((a, b) => b[1] - a[1]);
  if (ranked.length === 0) return null;
  const total = ranked.reduce((sum, [, n]) => sum + n, 0);
  const lead = ranked[0][1] - (ranked[1]?.[1] ?? 0);
  return { postal: ranked[0][0], close: lead / total < CLOSE_MARGIN };
}

/* ---- sweep every cell any state's bbox touches ---- */

const table = {};
let cells = 0;
for (const s of states) {
  const westStart = Math.floor(s.bbox.minLon / 2) * 2;
  const southStart = Math.floor(s.bbox.minLat);
  for (let west = westStart; west < s.bbox.maxLon; west += 2) {
    for (let south = southStart; south < s.bbox.maxLat; south += 1) {
      const grid = gridName(west, south);
      if (grid in table) continue;
      cells++;
      const cands = candidates(west, south);
      if (cands.length === 0) continue;
      let result = winner(sample(west, south, cands, BASE_N));
      if (result?.close) result = winner(sample(west, south, cands, FINE_N));
      if (result) table[grid] = result.postal;
      else table[grid] = null; // mark visited; deleted below
    }
  }
}
for (const k of Object.keys(table)) if (table[k] === null) delete table[k];

const sorted = Object.fromEntries(Object.entries(table).sort(([a], [b]) => (a < b ? -1 : 1)));
await writeFile(OUT, JSON.stringify(sorted) + "\n", "utf8");
console.log(`examined ${cells} cells, kept ${Object.keys(sorted).length}, wrote ${OUT}`);
