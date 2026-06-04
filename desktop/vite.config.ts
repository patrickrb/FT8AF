import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Vite config tuned for Tauri: fixed dev port, no auto-clearing of the terminal
// (so Tauri's logs stay visible), and a build that emits to ../dist (= the
// frontendDist in tauri.conf.json, relative to src-tauri).
export default defineConfig({
  plugins: [react()],
  clearScreen: false,
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    target: "esnext",
    outDir: "dist",
    emptyOutDir: true,
  },
});
