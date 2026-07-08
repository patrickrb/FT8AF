//! SQLite persistence (rusqlite, bundled). Ports the essential `qso_log` +
//! `config` schema from `DatabaseOpr.java` and the ADIF export format from
//! `ShareLogs.downQSLTableToFile`. Uses a `user_version` PRAGMA migration rather
//! than the Android app's v18 upgrade ladder.

use parking_lot::Mutex;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::path::Path;

const SCHEMA_VERSION: i64 = 1;

/// One logged QSO. Field names follow ADIF.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
pub struct QsoRecord {
    #[serde(default)]
    pub id: Option<i64>,
    pub call: String,
    #[serde(default)]
    pub gridsquare: String,
    #[serde(default = "default_mode")]
    pub mode: String,
    #[serde(default)]
    pub rst_sent: String,
    #[serde(default)]
    pub rst_rcvd: String,
    pub qso_date: String,
    pub time_on: String,
    #[serde(default)]
    pub qso_date_off: String,
    #[serde(default)]
    pub time_off: String,
    #[serde(default)]
    pub band: String,
    #[serde(default)]
    pub freq: String,
    #[serde(default)]
    pub station_callsign: String,
    #[serde(default)]
    pub my_gridsquare: String,
    #[serde(default)]
    pub comment: String,
}

fn default_mode() -> String {
    "FT8".to_string()
}

pub struct Db {
    conn: Mutex<Connection>,
}

impl Db {
    pub fn open(path: impl AsRef<Path>) -> anyhow::Result<Db> {
        let conn = Connection::open(path)?;
        Self::from_conn(conn)
    }

    pub fn open_in_memory() -> anyhow::Result<Db> {
        Self::from_conn(Connection::open_in_memory()?)
    }

    fn from_conn(conn: Connection) -> anyhow::Result<Db> {
        let db = Db { conn: Mutex::new(conn) };
        db.migrate()?;
        Ok(db)
    }

    fn migrate(&self) -> anyhow::Result<()> {
        let conn = self.conn.lock();
        let version: i64 = conn.query_row("PRAGMA user_version", [], |r| r.get(0))?;
        if version < 1 {
            conn.execute_batch(
                r#"
                CREATE TABLE IF NOT EXISTS config (
                    key   TEXT PRIMARY KEY,
                    value TEXT
                );
                CREATE TABLE IF NOT EXISTS qso_log (
                    id               INTEGER PRIMARY KEY AUTOINCREMENT,
                    call             TEXT NOT NULL,
                    gridsquare       TEXT,
                    mode             TEXT,
                    rst_sent         TEXT,
                    rst_rcvd         TEXT,
                    qso_date         TEXT,
                    time_on          TEXT,
                    qso_date_off     TEXT,
                    time_off         TEXT,
                    band             TEXT,
                    freq             TEXT,
                    station_callsign TEXT,
                    my_gridsquare    TEXT,
                    comment          TEXT,
                    created_at       INTEGER
                );
                CREATE INDEX IF NOT EXISTS qso_log_call_idx ON qso_log(call);
                CREATE UNIQUE INDEX IF NOT EXISTS qso_log_dedup_idx
                    ON qso_log(call, qso_date, time_on, mode);
                "#,
            )?;
        }
        conn.execute(&format!("PRAGMA user_version = {SCHEMA_VERSION}"), [])?;
        Ok(())
    }

    // --- config key/value ---------------------------------------------------

    pub fn set_config(&self, key: &str, value: &str) -> anyhow::Result<()> {
        self.conn.lock().execute(
            "INSERT INTO config(key, value) VALUES(?1, ?2)
             ON CONFLICT(key) DO UPDATE SET value = excluded.value",
            params![key, value],
        )?;
        Ok(())
    }

    pub fn get_config(&self, key: &str) -> Option<String> {
        self.conn
            .lock()
            .query_row("SELECT value FROM config WHERE key = ?1", params![key], |r| {
                r.get::<_, String>(0)
            })
            .ok()
    }

    pub fn all_config(&self) -> Vec<(String, String)> {
        let conn = self.conn.lock();
        let mut stmt = match conn.prepare("SELECT key, value FROM config") {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = stmt
            .query_map([], |r| Ok((r.get::<_, String>(0)?, r.get::<_, String>(1)?)))
            .map(|it| it.filter_map(Result::ok).collect())
            .unwrap_or_default();
        rows
    }

    // --- QSO log -------------------------------------------------------------

    /// Insert (or update on the dedup key) a QSO. Returns its row id.
    pub fn insert_qso(&self, r: &QsoRecord) -> anyhow::Result<i64> {
        let conn = self.conn.lock();
        conn.execute(
            "INSERT INTO qso_log
                (call, gridsquare, mode, rst_sent, rst_rcvd, qso_date, time_on,
                 qso_date_off, time_off, band, freq, station_callsign,
                 my_gridsquare, comment, created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)
             ON CONFLICT(call, qso_date, time_on, mode) DO UPDATE SET
                gridsquare=excluded.gridsquare, rst_sent=excluded.rst_sent,
                rst_rcvd=excluded.rst_rcvd, qso_date_off=excluded.qso_date_off,
                time_off=excluded.time_off, band=excluded.band, freq=excluded.freq,
                station_callsign=excluded.station_callsign,
                my_gridsquare=excluded.my_gridsquare, comment=excluded.comment",
            params![
                r.call, r.gridsquare, r.mode, r.rst_sent, r.rst_rcvd, r.qso_date,
                r.time_on, r.qso_date_off, r.time_off, r.band, r.freq,
                r.station_callsign, r.my_gridsquare, r.comment,
                crate::util::now_unix_ms(),
            ],
        )?;
        Ok(conn.last_insert_rowid())
    }

    pub fn list_qsos(&self, limit: i64, offset: i64) -> Vec<QsoRecord> {
        let conn = self.conn.lock();
        let mut stmt = match conn.prepare(
            "SELECT id, call, gridsquare, mode, rst_sent, rst_rcvd, qso_date, time_on,
                    qso_date_off, time_off, band, freq, station_callsign,
                    my_gridsquare, comment
             FROM qso_log ORDER BY id DESC LIMIT ?1 OFFSET ?2",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        stmt.query_map(params![limit, offset], row_to_qso)
            .map(|it| it.filter_map(Result::ok).collect())
            .unwrap_or_default()
    }

    pub fn count(&self) -> i64 {
        self.conn
            .lock()
            .query_row("SELECT COUNT(*) FROM qso_log", [], |r| r.get(0))
            .unwrap_or(0)
    }

    pub fn delete_qso(&self, id: i64) -> anyhow::Result<()> {
        self.conn
            .lock()
            .execute("DELETE FROM qso_log WHERE id = ?1", params![id])?;
        Ok(())
    }

    /// Export the whole log as an ADIF string, matching ShareLogs' field set/order.
    pub fn export_adif(&self) -> String {
        let mut out = String::from("FT8AF ADIF Export<eoh>\n");
        for r in self.list_qsos(i64::MAX, 0).into_iter().rev() {
            out.push_str(&adif_field("call", &r.call));
            out.push_str("<QSL_RCVD:1>N <QSL_MANUAL:1>N ");
            adif_opt(&mut out, "gridsquare", &r.gridsquare);
            adif_opt(&mut out, "mode", &r.mode);
            adif_opt(&mut out, "rst_sent", &r.rst_sent);
            adif_opt(&mut out, "rst_rcvd", &r.rst_rcvd);
            adif_opt(&mut out, "qso_date", &r.qso_date);
            adif_opt(&mut out, "time_on", &r.time_on);
            adif_opt(&mut out, "qso_date_off", &r.qso_date_off);
            adif_opt(&mut out, "time_off", &r.time_off);
            adif_opt(&mut out, "band", &r.band);
            adif_opt(&mut out, "freq", &r.freq);
            adif_opt(&mut out, "station_callsign", &r.station_callsign);
            adif_opt(&mut out, "my_gridsquare", &r.my_gridsquare);
            out.push_str(&format!("<comment:{}>{} <eor>\n", r.comment.len(), r.comment));
        }
        out
    }
}

fn row_to_qso(r: &rusqlite::Row) -> rusqlite::Result<QsoRecord> {
    Ok(QsoRecord {
        id: r.get(0)?,
        call: r.get(1)?,
        gridsquare: r.get::<_, Option<String>>(2)?.unwrap_or_default(),
        mode: r.get::<_, Option<String>>(3)?.unwrap_or_default(),
        rst_sent: r.get::<_, Option<String>>(4)?.unwrap_or_default(),
        rst_rcvd: r.get::<_, Option<String>>(5)?.unwrap_or_default(),
        qso_date: r.get::<_, Option<String>>(6)?.unwrap_or_default(),
        time_on: r.get::<_, Option<String>>(7)?.unwrap_or_default(),
        qso_date_off: r.get::<_, Option<String>>(8)?.unwrap_or_default(),
        time_off: r.get::<_, Option<String>>(9)?.unwrap_or_default(),
        band: r.get::<_, Option<String>>(10)?.unwrap_or_default(),
        freq: r.get::<_, Option<String>>(11)?.unwrap_or_default(),
        station_callsign: r.get::<_, Option<String>>(12)?.unwrap_or_default(),
        my_gridsquare: r.get::<_, Option<String>>(13)?.unwrap_or_default(),
        comment: r.get::<_, Option<String>>(14)?.unwrap_or_default(),
    })
}

fn adif_field(name: &str, value: &str) -> String {
    format!("<{}:{}>{} ", name, value.len(), value)
}

fn adif_opt(out: &mut String, name: &str, value: &str) {
    if !value.is_empty() {
        out.push_str(&adif_field(name, value));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insert_dedup_and_export() {
        let db = Db::open_in_memory().unwrap();
        let rec = QsoRecord {
            call: "K1ABC".into(),
            gridsquare: "FN42".into(),
            mode: "FT8".into(),
            rst_sent: "-12".into(),
            rst_rcvd: "-08".into(),
            qso_date: "20260604".into(),
            time_on: "123045".into(),
            band: "20m".into(),
            freq: "14.074000".into(),
            station_callsign: "K0XYZ".into(),
            my_gridsquare: "EN37".into(),
            ..Default::default()
        };
        db.insert_qso(&rec).unwrap();
        db.insert_qso(&rec).unwrap(); // dedup -> still one row
        assert_eq!(db.count(), 1);

        let adif = db.export_adif();
        assert!(adif.starts_with("FT8AF ADIF Export<eoh>\n"));
        assert!(adif.contains("<call:5>K1ABC "));
        assert!(adif.contains("<gridsquare:4>FN42 "));
        assert!(adif.contains("<band:3>20m "));
        assert!(adif.trim_end().ends_with("<eor>"));
    }

    #[test]
    fn config_roundtrip() {
        let db = Db::open_in_memory().unwrap();
        assert_eq!(db.get_config("my_call"), None);
        db.set_config("my_call", "K0XYZ").unwrap();
        db.set_config("my_call", "K0ABC").unwrap();
        assert_eq!(db.get_config("my_call").as_deref(), Some("K0ABC"));
    }

    #[test]
    fn custom_bands_persist_via_config() {
        // The custom dial list (issue #470) rides on the config store, so it must
        // survive a reload the same way any other config value does.
        use crate::bands;
        let db = Db::open_in_memory().unwrap();

        let mut custom =
            bands::parse_custom_bands(&db.get_config(bands::CUSTOM_BANDS_KEY).unwrap_or_default());
        assert!(custom.is_empty());

        bands::upsert_custom_band(&mut custom, "3B9 DX", 14_090_000);
        db.set_config(bands::CUSTOM_BANDS_KEY, &bands::serialize_custom_bands(&custom))
            .unwrap();

        // Reload from the store (as a fresh app launch would).
        let reloaded =
            bands::parse_custom_bands(&db.get_config(bands::CUSTOM_BANDS_KEY).unwrap());
        assert_eq!(reloaded.len(), 1);
        assert_eq!(reloaded[0].dial_hz, 14_090_000);
        assert_eq!(reloaded[0].name, "3B9 DX");

        let merged = bands::merged_bands(&reloaded);
        assert!(merged.iter().any(|b| b.dial_hz == 14_090_000 && b.custom));
    }
}
