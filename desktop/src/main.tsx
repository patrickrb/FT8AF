import ReactDOM from "react-dom/client";
import { invoke } from "@tauri-apps/api/core";
import App from "./App";

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
async function loadCustomStyles() {
  try {
    const css = await invoke<string>("get_custom_css");
    const style = document.createElement("style");
    style.id = "ft8af-custom-styles";
    style.textContent = css;
    document.head.appendChild(style);
  } catch (e) {
    console.error("failed to load custom styles:", e);
  }
}

loadCustomStyles().finally(() => {
  ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(<App />);
});
