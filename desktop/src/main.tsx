import ReactDOM from "react-dom/client";
import { invoke } from "@tauri-apps/api/core";
import App from "./App";
// The compiled-in copy of the same stylesheet, as a string rather than a
// side-effecting CSS import -- injected only when the IPC read below fails,
// so the on-disk file stays the single source of styling in the normal case.
import bundledCss from "../public/styles.css?raw";

// Note: no React.StrictMode. Its dev-only double mount/unmount double-registers
// the async Tauri event listener, which duplicated every decode batch.

// Styles come from a real file on disk (get_custom_css, main.rs), read fresh
// on every launch -- not a Vite-bundled import. Tauri embeds frontendDist
// into the compiled binary at build time (confirmed directly: editing
// dist/styles.css after a build and relaunching the unchanged binary had no
// effect), so a bundled stylesheet -- even at a stable, unhashed path --
// would still need a full rebuild for every change. Injecting the fetched
// CSS as a <style> tag instead means edits to the on-disk file just need an
// app relaunch, matching how the rig list is resolved live via hamlib rather
// than baked in. Render waits on this so there's no unstyled flash.
function injectStyles(css: string) {
  const style = document.createElement("style");
  style.id = "ft8af-custom-styles";
  style.textContent = css;
  document.head.appendChild(style);
}

async function loadCustomStyles() {
  try {
    injectStyles(await invoke<string>("get_custom_css"));
  } catch (e) {
    // No Tauri IPC to answer the call -- most often `npm run dev` opened in a
    // plain browser, where `invoke` rejects outright. Since index.html links
    // no stylesheet, rendering past this without a fallback would put up the
    // app as bare unstyled HTML, so fall back to the compiled-in copy.
    console.error("failed to load custom styles, using the bundled copy:", e);
    injectStyles(bundledCss);
  }
}

loadCustomStyles().finally(() => {
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(<App />);
});
