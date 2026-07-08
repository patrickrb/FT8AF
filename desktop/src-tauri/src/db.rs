//! SQLite persistence (rusqlite, bundled). Ports the essential `qso_log` +
//! `config` schema from `DatabaseOpr.java` and the ADIF export format from
//! `ShareLogs.downQSLTableToFile`. Uses a `user_version` PRAGMA migration rather
//! than the Android app's v18 upgrade ladder.

use parking_lot::Mutex;
use rusqlite::{params, Connection};
use serde::{Deserialize, Serialize};
use std::path::Path;

const SCHEMA_VERSION: i64 = 2;

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
    /// QSL confirmed status (Android `qsl_rcvd`). Drives the Logbook filter and
    /// the `<QSL_RCVD>` ADIF field on export.
    #[serde(default)]
    pub confirmed: bool,
}

fn default_mode() -> String {
    "FT8".to_string()
}

/// Logbook confirmation filter, mirroring the Android `FilterDialog`
/// (All / QSL'd only / Not-QSL'd only).
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ConfirmedFilter {
    All,
    Confirmed,
    Unconfirmed,
}

impl Default for ConfirmedFilter {
    fn default() -> Self {
        ConfirmedFilter::All
    }
}

/// Pure predicate: does a QSO match the live search box + confirmation filter?
///
/// `callsign_query` is matched case-insensitively as a substring of the call
/// (empty query matches everything), mirroring `queryByCallsign` on Android.
pub fn qso_matches(rec: &QsoRecord, callsign_query: &str, filter: ConfirmedFilter) -> bool {
    let call_ok = {
        let q = callsign_query.trim();
        q.is_empty() || rec.call.to_uppercase().contains(&q.to_uppercase())
    };
    let confirm_ok = match filter {
        ConfirmedFilter::All => true,
        ConfirmedFilter::Confirmed => rec.confirmed,
        ConfirmedFilter::Unconfirmed => !rec.confirmed,
    };
    call_ok && confirm_ok
}

/// Pure predicate: is an ADIF `YYYYMMDD` date within an optional inclusive
/// `[start, end]` range? Empty/None bounds are open. Non-8-char dates that fall
/// outside a bounded range are excluded; with no bounds everything passes.
pub fn date_in_range(qso_date: &str, start: Option<&str>, end: Option<&str>) -> bool {
    let start = start.map(str::trim).filter(|s| !s.is_empty());
    let end = end.map(str::trim).filter(|s| !s.is_empty());
    if start.is_none() && end.is_none() {
        return true;
    }
    let d = qso_date.trim();
    if let Some(s) = start {
        if d < s {
            return false;
        }
    }
    if let Some(e) = end {
        if d > e {
            return false;
        }
    }
    true
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
        if version < 2 {
            // v2: QSL confirmation + per-service online-sync flags. These are
            // added via ALTER so an existing v1 log upgrades in place; on a
            // fresh DB the base table was just created above without them.
            // `IF NOT EXISTS` isn't supported by ADD COLUMN, so ignore the
            // "duplicate column" error to keep the migration idempotent.
            for col in [
                "ALTER TABLE qso_log ADD COLUMN qsl_rcvd INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE qso_log ADD COLUMN synced_cloudlog INTEGER NOT NULL DEFAULT 0",
                "ALTER TABLE qso_log ADD COLUMN synced_qrz INTEGER NOT NULL DEFAULT 0",
            ] {
                match conn.execute(col, []) {
                    Ok(_) => {}
                    Err(rusqlite::Error::SqliteFailure(_, Some(ref m)))
                        if m.contains("duplicate column name") => {}
                    Err(e) => return Err(e.into()),
                }
            }
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
                 my_gridsquare, comment, qsl_rcvd, created_at)
             VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16)
             ON CONFLICT(call, qso_date, time_on, mode) DO UPDATE SET
                gridsquare=excluded.gridsquare, rst_sent=excluded.rst_sent,
                rst_rcvd=excluded.rst_rcvd, qso_date_off=excluded.qso_date_off,
                time_off=excluded.time_off, band=excluded.band, freq=excluded.freq,
                station_callsign=excluded.station_callsign,
                my_gridsquare=excluded.my_gridsquare, comment=excluded.comment,
                qsl_rcvd=excluded.qsl_rcvd",
            params![
                r.call, r.gridsquare, r.mode, r.rst_sent, r.rst_rcvd, r.qso_date,
                r.time_on, r.qso_date_off, r.time_off, r.band, r.freq,
                r.station_callsign, r.my_gridsquare, r.comment, r.confirmed as i64,
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
                    my_gridsquare, comment, qsl_rcvd
             FROM qso_log ORDER BY id DESC LIMIT ?1 OFFSET ?2",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        stmt.query_map(params![limit, offset], row_to_qso)
            .map(|it| it.filter_map(Result::ok).collect())
            .unwrap_or_default()
    }

    /// Server-side search/filter mirroring the Android Logbook tab: substring
    /// match on callsign plus the QSL confirmation filter, applied over the
    /// most-recent-first log. Uses the pure [`qso_matches`] predicate.
    pub fn search_qsos(
        &self,
        callsign_query: &str,
        filter: ConfirmedFilter,
        limit: i64,
        offset: i64,
    ) -> Vec<QsoRecord> {
        self.list_qsos(i64::MAX, 0)
            .into_iter()
            .filter(|r| qso_matches(r, callsign_query, filter))
            .skip(offset.max(0) as usize)
            .take(limit.max(0) as usize)
            .collect()
    }

    /// Flag a QSO as synced to an online service so re-runs are idempotent.
    /// `service` is `"cloudlog"` or `"qrz"`; unknown services are a no-op.
    pub fn mark_synced(&self, id: i64, service: &str) -> anyhow::Result<()> {
        let column = match service {
            "cloudlog" => "synced_cloudlog",
            "qrz" => "synced_qrz",
            _ => return Ok(()),
        };
        self.conn.lock().execute(
            &format!("UPDATE qso_log SET {column} = 1 WHERE id = ?1"),
            params![id],
        )?;
        Ok(())
    }

    /// Whether a QSO has already been synced to the given service.
    pub fn is_synced(&self, id: i64, service: &str) -> bool {
        let column = match service {
            "cloudlog" => "synced_cloudlog",
            "qrz" => "synced_qrz",
            _ => return false,
        };
        self.conn
            .lock()
            .query_row(
                &format!("SELECT {column} FROM qso_log WHERE id = ?1"),
                params![id],
                |r| r.get::<_, i64>(0),
            )
            .map(|v| v != 0)
            .unwrap_or(false)
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
        self.export_adif_range(None, None)
    }

    /// Export the log as ADIF, optionally restricted to an inclusive
    /// `[start, end]` `YYYYMMDD` date range (mirrors `ExportLogSheet`'s
    /// date-range filter). Empty/`None` bounds are open.
    pub fn export_adif_range(&self, start: Option<&str>, end: Option<&str>) -> String {
        let mut out = String::from("FT8AF ADIF Export<eoh>\n");
        for r in self
            .list_qsos(i64::MAX, 0)
            .into_iter()
            .rev()
            .filter(|r| date_in_range(&r.qso_date, start, end))
        {
            out.push_str(&adif_field("call", &r.call));
            let qsl = if r.confirmed { "Y" } else { "N" };
            out.push_str(&format!("<QSL_RCVD:1>{qsl} <QSL_MANUAL:1>N "));
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
        confirmed: r.get::<_, Option<i64>>(15)?.unwrap_or(0) != 0,
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

    fn rec(call: &str, date: &str, time: &str, confirmed: bool) -> QsoRecord {
        QsoRecord {
            call: call.into(),
            mode: "FT8".into(),
            qso_date: date.into(),
            time_on: time.into(),
            confirmed,
            ..Default::default()
        }
    }

    #[test]
    fn confirmed_status_round_trips_and_reflects_in_adif() {
        let db = Db::open_in_memory().unwrap();
        db.insert_qso(&rec("K1ABC", "20260604", "120000", true)).unwrap();
        db.insert_qso(&rec("W1AW", "20260604", "130000", false)).unwrap();

        let rows = db.list_qsos(10, 0);
        let k1abc = rows.iter().find(|r| r.call == "K1ABC").unwrap();
        let w1aw = rows.iter().find(|r| r.call == "W1AW").unwrap();
        assert!(k1abc.confirmed);
        assert!(!w1aw.confirmed);

        let adif = db.export_adif();
        // Confirmed QSO exports QSL_RCVD:Y, unconfirmed exports N.
        assert!(adif.contains("<call:5>K1ABC <QSL_RCVD:1>Y "));
        assert!(adif.contains("<call:4>W1AW <QSL_RCVD:1>N "));
    }

    #[test]
    fn editing_a_qso_toggles_confirmed_via_insert() {
        let db = Db::open_in_memory().unwrap();
        let id = db.insert_qso(&rec("K1ABC", "20260604", "120000", false)).unwrap();
        assert!(!db.list_qsos(1, 0)[0].confirmed);
        // save_qso re-inserts the same dedup key with confirmed flipped.
        let mut edited = rec("K1ABC", "20260604", "120000", true);
        edited.id = Some(id);
        db.insert_qso(&edited).unwrap();
        assert_eq!(db.count(), 1);
        assert!(db.list_qsos(1, 0)[0].confirmed);
    }

    #[test]
    fn search_filters_by_callsign_and_confirmation() {
        let db = Db::open_in_memory().unwrap();
        db.insert_qso(&rec("K1ABC", "20260604", "120000", true)).unwrap();
        db.insert_qso(&rec("K1XYZ", "20260604", "130000", false)).unwrap();
        db.insert_qso(&rec("W1AW", "20260604", "140000", true)).unwrap();

        // Substring match, case-insensitive.
        let k1 = db.search_qsos("k1", ConfirmedFilter::All, 100, 0);
        assert_eq!(k1.len(), 2);
        assert!(k1.iter().all(|r| r.call.contains("K1")));

        // Confirmation filter.
        let confirmed = db.search_qsos("", ConfirmedFilter::Confirmed, 100, 0);
        assert_eq!(confirmed.len(), 2);
        let unconfirmed = db.search_qsos("", ConfirmedFilter::Unconfirmed, 100, 0);
        assert_eq!(unconfirmed.len(), 1);
        assert_eq!(unconfirmed[0].call, "K1XYZ");

        // Combined.
        let combined = db.search_qsos("k1", ConfirmedFilter::Confirmed, 100, 0);
        assert_eq!(combined.len(), 1);
        assert_eq!(combined[0].call, "K1ABC");
    }

    #[test]
    fn qso_matches_predicate() {
        let confirmed = rec("K1ABC", "20260604", "120000", true);
        let unconfirmed = rec("W1AW", "20260604", "120000", false);
        assert!(qso_matches(&confirmed, "", ConfirmedFilter::All));
        assert!(qso_matches(&confirmed, "abc", ConfirmedFilter::All));
        assert!(!qso_matches(&confirmed, "zzz", ConfirmedFilter::All));
        assert!(qso_matches(&confirmed, "", ConfirmedFilter::Confirmed));
        assert!(!qso_matches(&confirmed, "", ConfirmedFilter::Unconfirmed));
        assert!(qso_matches(&unconfirmed, "", ConfirmedFilter::Unconfirmed));
        assert!(!qso_matches(&unconfirmed, "", ConfirmedFilter::Confirmed));
    }

    #[test]
    fn export_date_range_filters_rows() {
        let db = Db::open_in_memory().unwrap();
        db.insert_qso(&rec("A1AA", "20260601", "120000", false)).unwrap();
        db.insert_qso(&rec("B2BB", "20260615", "120000", false)).unwrap();
        db.insert_qso(&rec("C3CC", "20260630", "120000", false)).unwrap();

        let mid = db.export_adif_range(Some("20260610"), Some("20260620"));
        assert!(!mid.contains("A1AA"));
        assert!(mid.contains("B2BB"));
        assert!(!mid.contains("C3CC"));

        // Open-ended bounds.
        let from = db.export_adif_range(Some("20260615"), None);
        assert!(!from.contains("A1AA"));
        assert!(from.contains("B2BB"));
        assert!(from.contains("C3CC"));

        // No bounds == whole log.
        let all = db.export_adif_range(None, None);
        assert!(all.contains("A1AA") && all.contains("B2BB") && all.contains("C3CC"));
    }

    #[test]
    fn date_in_range_predicate() {
        assert!(date_in_range("20260615", None, None));
        assert!(date_in_range("20260615", Some(""), Some("")));
        assert!(date_in_range("20260615", Some("20260601"), Some("20260630")));
        assert!(!date_in_range("20260701", Some("20260601"), Some("20260630")));
        assert!(!date_in_range("20260501", Some("20260601"), None));
        assert!(date_in_range("20260615", None, Some("20260615")));
        assert!(!date_in_range("20260616", None, Some("20260615")));
    }

    #[test]
    fn sync_flags_are_idempotent_per_service() {
        let db = Db::open_in_memory().unwrap();
        let id = db.insert_qso(&rec("K1ABC", "20260604", "120000", false)).unwrap();
        assert!(!db.is_synced(id, "cloudlog"));
        assert!(!db.is_synced(id, "qrz"));

        db.mark_synced(id, "cloudlog").unwrap();
        assert!(db.is_synced(id, "cloudlog"));
        assert!(!db.is_synced(id, "qrz"));

        // Unknown service is a no-op, not an error.
        db.mark_synced(id, "bogus").unwrap();
        assert!(!db.is_synced(id, "bogus"));
    }

    #[test]
    fn migrates_v1_log_in_place() {
        // Simulate an existing v1 database (no qsl_rcvd / sync columns) and
        // confirm the v2 migration adds them without dropping rows.
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            r#"
            CREATE TABLE qso_log (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                call TEXT NOT NULL, gridsquare TEXT, mode TEXT, rst_sent TEXT,
                rst_rcvd TEXT, qso_date TEXT, time_on TEXT, qso_date_off TEXT,
                time_off TEXT, band TEXT, freq TEXT, station_callsign TEXT,
                my_gridsquare TEXT, comment TEXT, created_at INTEGER
            );
            CREATE UNIQUE INDEX qso_log_dedup_idx
                ON qso_log(call, qso_date, time_on, mode);
            INSERT INTO qso_log(call, mode, qso_date, time_on)
                VALUES('K1ABC', 'FT8', '20260604', '120000');
            PRAGMA user_version = 1;
            "#,
        )
        .unwrap();
        let db = Db::from_conn(conn).unwrap();
        let rows = db.list_qsos(10, 0);
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].call, "K1ABC");
        assert!(!rows[0].confirmed); // defaults to unconfirmed
    }
}
