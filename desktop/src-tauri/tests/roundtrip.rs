//! M1 acceptance gate: a Rust port of ft8cn_glue/test_roundtrip.c.
//!
//! Proves the C ft8_lib core links and is self-consistent through the Rust FFI:
//! pack a known message -> encode to tones -> synthesize the GFSK waveform ->
//! feed it back through the monitor + sync search + LDPC decoder and confirm the
//! original text is recovered. This is the gate for the whole project.

use ft8af::dsp::{encode, Decoder, SAMPLE_RATE, SLOT_SAMPLES};

#[test]
fn encode_then_decode_recovers_message() {
    let expected = "CQ K1ABC FN42";
    let freq_hz = 1000.0;

    // 1. pack -> 2. encode -> 3. synth GFSK into a full 15 s slot buffer.
    let payload = encode::pack77(expected).expect("pack77 should accept a standard CQ");
    let tones = encode::encode_tones(&payload);
    let mut signal = vec![0.0f32; SLOT_SAMPLES];
    encode::synth_gfsk(&tones, freq_hz, SAMPLE_RATE, &mut signal, 0);

    // 4-5. monitor -> find_sync -> decode.
    let mut decoder = Decoder::new(SAMPLE_RATE, true);
    decoder.feed_slot(&signal);
    let n = decoder.find_sync();
    assert!(n > 0, "expected at least one sync candidate, got {n}");

    let messages = decoder.decode_all();
    assert!(
        !messages.is_empty(),
        "decoder found {n} candidates but decoded nothing"
    );

    let recovered = messages.iter().find(|m| m.raw_text == expected);
    assert!(
        recovered.is_some(),
        "original message not recovered; decoded texts: {:?}",
        messages.iter().map(|m| &m.raw_text).collect::<Vec<_>>()
    );

    let m = recovered.unwrap();
    assert_eq!(m.call_to, "CQ");
    assert_eq!(m.call_from, "K1ABC");
    assert_eq!(m.grid, "FN42");
    // The signal was synthesized at 1000 Hz; decoder should land close.
    assert!(
        (m.freq_hz - freq_hz).abs() < 10.0,
        "frequency off: {} Hz",
        m.freq_hz
    );
}

#[test]
fn generate_ft8_produces_expected_length() {
    let signal = encode::generate_ft8("CQ K1ABC FN42", 1000.0, SAMPLE_RATE).unwrap();
    // 79 symbols * 0.16 s * 12000 Hz = 151680 samples (+ rounding).
    assert_eq!(signal.len(), 151680);
}
