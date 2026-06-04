import ReactDOM from "react-dom/client";
import App from "./App";
import "./styles.css";

// Note: no React.StrictMode. Its dev-only double mount/unmount double-registers
// the async Tauri event listener, which duplicated every decode batch.
ReactDOM.createRoot(document.getElementById("root") as HTMLElement).render(<App />);
