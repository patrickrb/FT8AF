package radio.ks3ckc.ft8af.pota

/**
 * Frequency math for POTA self-spots.
 *
 * A self-spot must advertise the **dial (carrier) frequency** so hunters tune the
 * shared FT8 calling frequency for the band — e.g. 14074.0 kHz on 20 m, 50313.0 kHz
 * on 6 m. It must NOT use the audio offset (the waterfall tap position, ~200-3500 Hz):
 * that is what [com.k1af.ft8af.GeneralVariables.getBaseFrequency] returns, and
 * posting it alone produces a ~1-3.5 kHz spot on every band.
 *
 * Contrast with the PSKReporter path, which reports `band + audioOffset` because it
 * maps an individual decoded signal rather than the activator's calling frequency.
 *
 * @param bandHz the carrier/dial frequency in Hz (GeneralVariables.band)
 * @return the dial frequency in kHz, as POTA expects
 */
internal fun potaSpotFrequencyKhz(bandHz: Long): Double = bandHz / 1000.0
