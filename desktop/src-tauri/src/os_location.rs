//! Optional, opt-in OS location service -> operator grid (issue #471).
//!
//! Nothing in here runs until the user turns on the [`LOCATION_ENABLED_KEY`]
//! config flag (default off) in Settings -> Station and presses "Use my
//! location". The Tauri command layer (`main.rs::get_os_location`) enforces that
//! gate; querying the platform service is the only thing that triggers the OS
//! location-permission prompt, so a fresh install never requests location.
//!
//! Platform backends:
//!   - Linux   — GeoClue 2 over D-Bus (may be unavailable on headless installs).
//!   - Windows — Windows.Devices.Geolocation `Geolocator`.
//!   - macOS / other — not wired yet; returns a clear error so the UI falls back
//!     to manual entry (see the module docs / PR for the CoreLocation plan).

use serde::Serialize;
use std::time::Duration;

use crate::util;

/// Config key that gates the whole feature. Absent or anything other than the
/// exact string `"true"` means disabled.
pub const LOCATION_ENABLED_KEY: &str = "location_service_enabled";

/// Grid precision for OS-derived locations: 6 chars (subsquare, ~2.5 × 5 km) is
/// coarse enough not to leak a pinpoint home QTH but still useful for FT8.
pub const OS_GRID_PRECISION: usize = 6;

/// Upper bound on how long the OS query may block before we give up, so the UI
/// "Use my location" button can never hang the app.
const QUERY_TIMEOUT: Duration = Duration::from_secs(15);

/// A resolved OS location plus the derived Maidenhead grid handed to the UI.
#[derive(Debug, Clone, PartialEq, Serialize)]
pub struct OsLocation {
    pub lat: f64,
    pub lon: f64,
    pub grid: String,
}

/// Whether the OS-location feature is enabled, given the stored config value.
///
/// The default (`None`) and any non-`"true"` string are treated as disabled, so
/// a fresh install — or a hand-edited/garbage config value — never opts the user
/// in silently.
pub fn location_enabled(value: Option<&str>) -> bool {
    matches!(value, Some("true"))
}

/// Convert a raw OS fix into the [`OsLocation`] returned to the UI. Kept separate
/// from the platform query so the lat/lon -> grid step is unit-testable without
/// touching any OS API.
pub fn to_os_location(lat: f64, lon: f64) -> OsLocation {
    OsLocation {
        lat,
        lon,
        grid: util::latlon_to_maidenhead(lat, lon, OS_GRID_PRECISION),
    }
}

/// Query the OS location service and resolve it to an [`OsLocation`].
///
/// Returns a human-readable error (permission denied, service unavailable /
/// headless, timeout) on failure. Blocking; call off the UI thread — Tauri
/// commands already run on a worker thread.
pub fn resolve() -> Result<OsLocation, String> {
    let (lat, lon) = query_with_timeout()?;
    Ok(to_os_location(lat, lon))
}

/// Run the platform query on a helper thread and bound it with [`QUERY_TIMEOUT`]
/// so a stuck location service can never wedge the caller.
fn query_with_timeout() -> Result<(f64, f64), String> {
    let (tx, rx) = std::sync::mpsc::channel();
    std::thread::Builder::new()
        .name("ft8af-os-location".into())
        .spawn(move || {
            // The receiver may already be gone (timed out); ignore send errors.
            let _ = tx.send(platform::query());
        })
        .map_err(|e| format!("could not start location query: {e}"))?;
    match rx.recv_timeout(QUERY_TIMEOUT) {
        Ok(result) => result,
        Err(_) => Err(
            "Timed out waiting for the location service. Check that location \
             access is allowed, or enter your grid manually."
                .into(),
        ),
    }
}

// --- Linux: GeoClue 2 over D-Bus -------------------------------------------

#[cfg(target_os = "linux")]
mod platform {
    use zbus::blocking::Connection;
    use zbus::proxy;
    use zbus::zvariant::OwnedObjectPath;

    // GeoClue accuracy levels (see GClueAccuracyLevel). 4 = "city" — coarse, which
    // is all a Maidenhead subsquare needs and the least intrusive prompt.
    const ACCURACY_CITY: u32 = 4;

    #[proxy(
        interface = "org.freedesktop.GeoClue2.Manager",
        default_service = "org.freedesktop.GeoClue2",
        default_path = "/org/freedesktop/GeoClue2/Manager"
    )]
    trait Manager {
        fn get_client(&self) -> zbus::Result<OwnedObjectPath>;
    }

    #[proxy(
        interface = "org.freedesktop.GeoClue2.Client",
        default_service = "org.freedesktop.GeoClue2"
    )]
    trait Client {
        #[zbus(property)]
        fn set_desktop_id(&self, id: &str) -> zbus::Result<()>;
        #[zbus(property)]
        fn set_requested_accuracy_level(&self, level: u32) -> zbus::Result<()>;
        fn start(&self) -> zbus::Result<()>;
        fn stop(&self) -> zbus::Result<()>;
        #[zbus(signal)]
        fn location_updated(
            &self,
            old: OwnedObjectPath,
            new: OwnedObjectPath,
        ) -> zbus::Result<()>;
    }

    #[proxy(
        interface = "org.freedesktop.GeoClue2.Location",
        default_service = "org.freedesktop.GeoClue2"
    )]
    trait Location {
        #[zbus(property)]
        fn latitude(&self) -> zbus::Result<f64>;
        #[zbus(property)]
        fn longitude(&self) -> zbus::Result<f64>;
    }

    pub fn query() -> Result<(f64, f64), String> {
        let conn = Connection::system().map_err(|e| {
            format!("could not reach the system D-Bus (GeoClue unavailable): {e}")
        })?;
        let manager = ManagerProxyBlocking::new(&conn)
            .map_err(|e| format!("GeoClue location service is not available: {e}"))?;
        let client_path = manager
            .get_client()
            .map_err(|e| format!("GeoClue refused a client (permission denied?): {e}"))?;
        let client = ClientProxyBlocking::builder(&conn)
            .path(client_path)
            .and_then(|b| b.build())
            .map_err(|e| format!("could not create a GeoClue client: {e}"))?;
        // DesktopId must match an installed .desktop file for GeoClue's authority
        // check; the app id doubles as the identifier here.
        let _ = client.set_desktop_id("ft8af");
        let _ = client.set_requested_accuracy_level(ACCURACY_CITY);

        // Subscribe before Start() so we can't miss the first LocationUpdated.
        let updates = client
            .receive_location_updated()
            .map_err(|e| format!("could not subscribe to location updates: {e}"))?;
        client
            .start()
            .map_err(|e| format!("could not start the location client: {e}"))?;

        for signal in updates {
            let args = signal
                .args()
                .map_err(|e| format!("malformed location update: {e}"))?;
            let loc = LocationProxyBlocking::builder(&conn)
                .path(args.new().clone())
                .and_then(|b| b.build())
                .map_err(|e| format!("could not read the location: {e}"))?;
            let lat = loc.latitude().map_err(|e| e.to_string())?;
            let lon = loc.longitude().map_err(|e| e.to_string())?;
            let _ = client.stop();
            return Ok((lat, lon));
        }
        let _ = client.stop();
        Err("the location service returned no fix".into())
    }
}

// --- Windows: Windows.Devices.Geolocation ----------------------------------

#[cfg(target_os = "windows")]
mod platform {
    use windows::Devices::Geolocation::Geolocator;

    pub fn query() -> Result<(f64, f64), String> {
        // Constructing the Geolocator + first GetGeopositionAsync is what raises
        // the Windows location-permission prompt.
        let locator = Geolocator::new().map_err(|e| e.message())?;
        let pos = locator
            .GetGeopositionAsync()
            .and_then(|op| op.get())
            .map_err(|e| e.message())?;
        let coord = pos.Coordinate().map_err(|e| e.message())?;
        let point = coord.Point().map_err(|e| e.message())?;
        let basic = point.Position().map_err(|e| e.message())?;
        Ok((basic.Latitude, basic.Longitude))
    }
}

// --- macOS / everything else: not wired yet --------------------------------

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
mod platform {
    pub fn query() -> Result<(f64, f64), String> {
        // CoreLocation needs a CLLocationManager delegate driven from a run loop
        // plus an NSLocationUsageDescription Info.plist key; that on-device work
        // is tracked as a follow-up. Until then macOS users enter their grid by
        // hand (the manual field is untouched on this error).
        Err("Automatic location isn't available on this platform yet — \
             enter your grid manually."
            .into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn enabled_only_for_exact_true() {
        assert!(location_enabled(Some("true")));
        // Default off: no stored value.
        assert!(!location_enabled(None));
        // Anything else is off — including the string "false" and stray values.
        assert!(!location_enabled(Some("false")));
        assert!(!location_enabled(Some("1")));
        assert!(!location_enabled(Some("TRUE")));
        assert!(!location_enabled(Some("")));
    }

    #[test]
    fn to_os_location_derives_six_char_grid() {
        // Munich -> JN58td (see util::latlon_to_maidenhead tests).
        let loc = to_os_location(48.14666, 11.60833);
        assert_eq!(loc.grid, "JN58td");
        assert_eq!(loc.grid.len(), OS_GRID_PRECISION);
        assert_eq!(loc.lat, 48.14666);
        assert_eq!(loc.lon, 11.60833);
    }
}
