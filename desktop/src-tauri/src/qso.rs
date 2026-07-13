//! FT8 QSO auto-sequencer — a pure state machine ported (in spirit) from
//! `FT8TransmitSignal` + `FunctionOfTransmit`. It reacts to the decoded messages
//! of each RX slot and decides the message to send in the next TX slot, handling
//! both roles: calling CQ (initiator) and answering a CQ (answerer). On QSO
//! completion it emits a `QsoRecord` to log. No I/O lives here, so it's fully
//! unit-testable; the scheduler turns its decisions into encode+PTT+DB+events.

use serde::{Deserialize, Serialize};

use crate::db::QsoRecord;
use crate::dsp::DecodedMessage;
use crate::util;

/// The last message we transmitted — drives what reply we expect next.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TxStage {
    Idle,
    Cq,
    Grid,    // answered a CQ with our grid
    Report,  // sent a signal report
    RReport, // sent R + report
    Rr73,
    Bye73,
}

/// Snapshot of QSO/TX state for the UI.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QsoStatus {
    pub active: bool,
    pub stage: TxStage,
    pub target: Option<String>,
    pub tx_message: Option<String>,
    pub report_sent: Option<i32>,
    pub report_rcvd: Option<i32>,
}

/// What the engine wants the scheduler to do after processing a slot.
#[derive(Debug, Clone)]
pub enum QsoOutcome {
    /// A QSO finished and should be logged.
    Completed(QsoRecord),
}

#[derive(Debug, Clone)]
enum Content {
    Grid(String),
    Report(i32),
    RReport(i32),
    Rr73,
    Rrr,
    Bye73,
    Other,
}

pub struct QsoEngine {
    pub my_call: String,
    pub my_grid: String, // 4-char
    pub band: String,
    pub freq_mhz: String,
    pub active: bool,
    pub auto_return_to_cq: bool,

    stage: TxStage,
    target: Option<String>,
    grid_rcvd: Option<String>,
    report_sent: Option<i32>,
    report_rcvd: Option<i32>,
    tx_message: Option<String>,
    started_ms: i64,
}

impl QsoEngine {
    pub fn new(my_call: impl Into<String>, my_grid: impl Into<String>) -> Self {
        QsoEngine {
            my_call: my_call.into().to_uppercase(),
            my_grid: util::grid4(&my_grid.into()).to_uppercase(),
            band: String::new(),
            freq_mhz: String::new(),
            active: false,
            auto_return_to_cq: true,
            stage: TxStage::Idle,
            target: None,
            grid_rcvd: None,
            report_sent: None,
            report_rcvd: None,
            tx_message: None,
            started_ms: 0,
        }
    }

    pub fn status(&self) -> QsoStatus {
        QsoStatus {
            active: self.active,
            stage: self.stage,
            target: self.target.clone(),
            tx_message: self.tx_message.clone(),
            report_sent: self.report_sent,
            report_rcvd: self.report_rcvd,
        }
    }

    pub fn tx_message(&self) -> Option<&str> {
        self.tx_message.as_deref()
    }

    /// Begin calling CQ.
    pub fn start_cq(&mut self) {
        self.reset_qso();
        self.active = true;
        self.stage = TxStage::Cq;
        self.tx_message = Some(format!("CQ {} {}", self.my_call, self.my_grid));
    }

    /// Begin answering a specific decoded CQ (operator tapped a station).
    pub fn answer(&mut self, msg: &DecodedMessage) {
        self.reset_qso();
        self.active = true;
        self.target = Some(msg.call_from.to_uppercase());
        self.grid_rcvd = if msg.grid.is_empty() { None } else { Some(msg.grid.clone()) };
        self.report_sent = Some(clamp_report(msg.snr));
        self.stage = TxStage::Grid;
        self.started_ms = util::now_unix_ms();
        self.tx_message = Some(format!(
            "{} {} {}",
            self.target.as_ref().unwrap(),
            self.my_call,
            self.my_grid
        ));
    }

    /// Operator manually picks which standard message to send next (the
    /// WSJT-X Tx1–Tx5 buttons). Requires an active target — it rebuilds
    /// `tx_message` for `stage` from the current QSO context, and the
    /// auto-sequencer then carries on from there on the next decoded reply.
    /// Selecting `Cq`/`Idle` falls through to start-CQ / stop so the same
    /// control can cover the whole strip.
    pub fn set_stage(&mut self, stage: TxStage) {
        match stage {
            TxStage::Cq => return self.start_cq(),
            TxStage::Idle => return self.stop(),
            _ => {}
        }
        let dx = match self.target.clone() {
            Some(t) => t,
            None => return, // no station selected yet — nothing to address
        };
        self.active = true;
        match stage {
            TxStage::Grid => {
                self.tx_message = Some(format!("{} {} {}", dx, self.my_call, self.my_grid));
                self.stage = TxStage::Grid;
            }
            TxStage::Report => self.send_report(&dx),
            TxStage::RReport => self.send_r_report(&dx),
            TxStage::Rr73 => self.send_rr73(&dx),
            TxStage::Bye73 => {
                self.tx_message = Some(format!("{} {} 73", dx, self.my_call));
                self.stage = TxStage::Bye73;
            }
            TxStage::Cq | TxStage::Idle => unreachable!("handled above"),
        }
    }

    /// Send a free-text / arbitrary message once.
    pub fn set_free_text(&mut self, text: &str) {
        self.active = true;
        self.tx_message = Some(text.to_uppercase());
    }

    pub fn stop(&mut self) {
        self.active = false;
        self.tx_message = None;
        self.stage = TxStage::Idle;
    }

    fn reset_qso(&mut self) {
        self.target = None;
        self.grid_rcvd = None;
        self.report_sent = None;
        self.report_rcvd = None;
        self.started_ms = util::now_unix_ms();
    }

    /// Process the decoded messages of a finished RX slot. Updates `tx_message`
    /// for the next TX slot and returns a completion outcome if a QSO finished.
    pub fn process_rx(&mut self, messages: &[DecodedMessage]) -> Option<QsoOutcome> {
        if !self.active || self.stage == TxStage::Idle {
            return None;
        }

        // The courtesy "73" queued on the previous slot (RReport -> RR73, or a
        // manual Tx5) has now had its TX slot; wrap up (return to CQ / stop)
        // regardless of incoming traffic this slot.
        if self.stage == TxStage::Bye73 {
            self.finish_qso();
            return None;
        }

        // Find a message addressed to us, from our target if we have one.
        let mine = messages.iter().find(|m| {
            m.call_to.eq_ignore_ascii_case(&self.my_call)
                && match &self.target {
                    Some(t) => m.call_from.eq_ignore_ascii_case(t),
                    None => true,
                }
        });

        let msg = match mine {
            Some(m) => m.clone(),
            None => return None, // no reply this slot; scheduler retransmits
        };

        if self.target.is_none() {
            self.target = Some(msg.call_from.to_uppercase());
            self.started_ms = util::now_unix_ms();
        }
        let dx = self.target.clone().unwrap();
        let content = classify(&msg);

        match self.stage {
            TxStage::Cq => match content {
                Content::Grid(g) => {
                    self.grid_rcvd = Some(g);
                    self.report_sent = Some(clamp_report(msg.snr));
                    self.send_report(&dx);
                }
                Content::Report(n) => {
                    self.report_rcvd = Some(n);
                    self.report_sent = Some(clamp_report(msg.snr));
                    self.send_r_report(&dx);
                }
                _ => {}
            },
            TxStage::Grid => match content {
                Content::Report(n) => {
                    self.report_rcvd = Some(n);
                    // Our report reflects the DX signal from the decode we're
                    // answering (matches WSJT-X recomputing per decode).
                    self.report_sent = Some(clamp_report(msg.snr));
                    self.send_r_report(&dx);
                }
                Content::RReport(n) => {
                    self.report_rcvd = Some(n);
                    self.send_rr73(&dx);
                }
                _ => {}
            },
            TxStage::Report => match content {
                Content::RReport(n) => {
                    self.report_rcvd = Some(n);
                    self.send_rr73(&dx);
                }
                Content::Rr73 | Content::Rrr | Content::Bye73 => {
                    return self.complete(&dx);
                }
                _ => {}
            },
            TxStage::RReport => match content {
                Content::Rr73 | Content::Rrr | Content::Bye73 => {
                    // Acknowledge with 73, then log.
                    self.tx_message = Some(format!("{} {} 73", dx, self.my_call));
                    self.stage = TxStage::Bye73;
                    return self.complete(&dx);
                }
                _ => {}
            },
            TxStage::Rr73 => match content {
                Content::Bye73 | Content::Rr73 | Content::Rrr => {
                    return self.complete(&dx);
                }
                _ => {}
            },
            TxStage::Bye73 | TxStage::Idle => {}
        }
        None
    }

    fn send_report(&mut self, dx: &str) {
        let r = self.report_sent.unwrap_or(0);
        self.tx_message = Some(format!("{} {} {}", dx, self.my_call, fmt_report(r)));
        self.stage = TxStage::Report;
    }

    fn send_r_report(&mut self, dx: &str) {
        let r = self.report_sent.unwrap_or(0);
        self.tx_message = Some(format!("{} {} R{}", dx, self.my_call, fmt_report(r)));
        self.stage = TxStage::RReport;
    }

    fn send_rr73(&mut self, dx: &str) {
        self.tx_message = Some(format!("{} {} RR73", dx, self.my_call));
        self.stage = TxStage::Rr73;
    }

    fn complete(&mut self, dx: &str) -> Option<QsoOutcome> {
        let record = QsoRecord {
            id: None,
            call: dx.to_string(),
            gridsquare: self.grid_rcvd.clone().unwrap_or_default(),
            mode: "FT8".to_string(),
            rst_sent: self.report_sent.map(fmt_report).unwrap_or_default(),
            rst_rcvd: self.report_rcvd.map(fmt_report).unwrap_or_default(),
            qso_date: util::yyyymmdd_from_ms(self.started_ms),
            time_on: util::hhmmss_from_ms(self.started_ms),
            qso_date_off: util::utc_yyyymmdd(),
            time_off: util::utc_hhmmss(),
            band: self.band.clone(),
            freq: self.freq_mhz.clone(),
            station_callsign: self.my_call.clone(),
            my_gridsquare: self.my_grid.clone(),
            comment: String::new(),
            confirmed: false,
        };

        // If a courtesy "73" is queued (the RReport arm set stage = Bye73), leave
        // tx_message/stage so the scheduler transmits it; the next slot's Bye73
        // handler in process_rx then wraps up. Otherwise finish now.
        if self.stage != TxStage::Bye73 {
            self.finish_qso();
        }
        Some(QsoOutcome::Completed(record))
    }

    /// Return to calling CQ, or stop, per `auto_return_to_cq`.
    fn finish_qso(&mut self) {
        if self.auto_return_to_cq {
            self.reset_qso();
            self.stage = TxStage::Cq;
            self.tx_message = Some(format!("CQ {} {}", self.my_call, self.my_grid));
        } else {
            self.stop();
        }
    }
}

/// FT8 reports are clamped to roughly [-30, +30].
fn clamp_report(snr: i32) -> i32 {
    snr.clamp(-30, 30)
}

/// Format a report as a signed 2+ digit string: -9 -> "-09", 5 -> "+05".
fn fmt_report(n: i32) -> String {
    format!("{:+03}", n)
}

fn classify(msg: &DecodedMessage) -> Content {
    if !msg.grid.is_empty() {
        return Content::Grid(msg.grid.clone());
    }
    let extra = msg.extra.trim();
    match extra {
        "RR73" => return Content::Rr73,
        "RRR" => return Content::Rrr,
        "73" => return Content::Bye73,
        _ => {}
    }
    if let Some(rest) = extra.strip_prefix('R') {
        if let Ok(n) = rest.parse::<i32>() {
            return Content::RReport(n);
        }
    }
    if let Ok(n) = extra.parse::<i32>() {
        return Content::Report(n);
    }
    Content::Other
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(to: &str, from: &str, extra: &str, grid: &str, snr: i32) -> DecodedMessage {
        DecodedMessage {
            call_to: to.into(),
            call_from: from.into(),
            extra: extra.into(),
            grid: grid.into(),
            raw_text: format!("{to} {from} {extra}"),
            snr,
            freq_hz: 1500.0,
            time_sec: 0.5,
            score: 30,
            i3: 1,
            n3: 0,
            a91: [0; 12],
        }
    }

    #[test]
    fn answerer_full_sequence_logs_qso() {
        let mut e = QsoEngine::new("K0XYZ", "EN37");
        // Operator answers DX K1ABC who CQ'd from FN42.
        e.answer(&msg("CQ", "K1ABC", "FN42", "FN42", -5));
        assert_eq!(e.tx_message(), Some("K1ABC K0XYZ EN37"));
        assert_eq!(e.status().stage, TxStage::Grid);

        // DX reports our signal -12.
        let out = e.process_rx(&[msg("K0XYZ", "K1ABC", "-12", "", -8)]);
        assert!(out.is_none());
        assert_eq!(e.tx_message(), Some("K1ABC K0XYZ R-08"));
        assert_eq!(e.status().report_rcvd, Some(-12));

        // DX sends RR73 -> we log AND queue the courtesy 73 to transmit.
        let out = e.process_rx(&[msg("K0XYZ", "K1ABC", "RR73", "", -8)]);
        match out {
            Some(QsoOutcome::Completed(rec)) => {
                assert_eq!(rec.call, "K1ABC");
                assert_eq!(rec.gridsquare, "FN42");
                assert_eq!(rec.rst_sent, "-08");
                assert_eq!(rec.rst_rcvd, "-12");
            }
            _ => panic!("expected completed QSO"),
        }
        // The final 73 is queued (not clobbered by the auto-return to CQ)...
        assert_eq!(e.tx_message(), Some("K1ABC K0XYZ 73"));
        assert_eq!(e.status().stage, TxStage::Bye73);
        // ...and once its TX slot has passed, the next slot returns to CQ.
        assert!(e.process_rx(&[]).is_none());
        assert_eq!(e.tx_message(), Some("CQ K0XYZ EN37"));
        assert_eq!(e.status().stage, TxStage::Cq);
    }

    #[test]
    fn initiator_cq_sequence_logs_qso() {
        let mut e = QsoEngine::new("K0XYZ", "EN37");
        e.start_cq();
        assert_eq!(e.tx_message(), Some("CQ K0XYZ EN37"));

        // K1ABC answers our CQ with their grid.
        e.process_rx(&[msg("K0XYZ", "K1ABC", "FN42", "FN42", -10)]);
        assert_eq!(e.tx_message(), Some("K1ABC K0XYZ -10"));
        assert_eq!(e.status().stage, TxStage::Report);

        // K1ABC sends R-report.
        e.process_rx(&[msg("K0XYZ", "K1ABC", "R-15", "", -10)]);
        assert_eq!(e.tx_message(), Some("K1ABC K0XYZ RR73"));
        assert_eq!(e.status().report_rcvd, Some(-15));

        // K1ABC sends 73 -> complete, log, back to CQ.
        let out = e.process_rx(&[msg("K0XYZ", "K1ABC", "73", "", -10)]);
        match out {
            Some(QsoOutcome::Completed(rec)) => {
                assert_eq!(rec.call, "K1ABC");
                assert_eq!(rec.rst_sent, "-10");
                assert_eq!(rec.rst_rcvd, "-15");
            }
            _ => panic!("expected completed QSO"),
        }
        // auto-returned to CQ
        assert_eq!(e.tx_message(), Some("CQ K0XYZ EN37"));
    }

    #[test]
    fn ignores_messages_for_others() {
        let mut e = QsoEngine::new("K0XYZ", "EN37");
        e.start_cq();
        // traffic between two other stations
        assert!(e.process_rx(&[msg("W1AW", "K1ABC", "FN42", "FN42", -3)]).is_none());
        assert_eq!(e.tx_message(), Some("CQ K0XYZ EN37"));
    }
}
