import Accelerate
import FT8Audio

/// Welch-averaged FFT using vDSP, producing the `summedPower` array that
/// `WaterfallRowBuilder.buildRow` expects. Caches the `vDSP_FFTSetup` for
/// the session.
enum FFTProcessor {
    /// Shared FFT setup for `fftSize = 2048` (log2 = 11). Created once.
    private static let fftSetup: FFTSetup = {
        let log2n = vDSP_Length(log2(Float(WaterfallRowBuilder.fftSize)))
        return vDSP_create_fftsetup(log2n, FFTRadix(kFFTRadix2))!
    }()

    /// Compute Welch-averaged power spectrum from raw 12 kHz samples.
    ///
    /// Returns `summedPower[0..<columns]` where each element is the sum of
    /// |FFT bin|^2 across `segments` Hann-windowed, 50%-overlapping segments.
    /// Ready to pass directly to `WaterfallRowBuilder.buildRow()`.
    static func welchPower(
        samples: [Float],
        fftSize: Int = WaterfallRowBuilder.fftSize,
        segments: Int = WaterfallRowBuilder.segments,
        step: Int = WaterfallRowBuilder.step,
        columns: Int
    ) -> [Float] {
        let log2n = vDSP_Length(log2(Float(fftSize)))
        let halfN = fftSize / 2

        // Pre-compute Hann window.
        var window = [Float](repeating: 0, count: fftSize)
        vDSP_hann_window(&window, vDSP_Length(fftSize), Int32(vDSP_HANN_NORM))

        // Accumulate |bin|^2 across segments.
        var summedPower = [Float](repeating: 0, count: columns)

        var windowed = [Float](repeating: 0, count: fftSize)
        // Split complex storage for the in-place FFT.
        var realp = [Float](repeating: 0, count: halfN)
        var imagp = [Float](repeating: 0, count: halfN)

        samples.withUnsafeBufferPointer { samplesPtr in
        for seg in 0..<segments {
            let offset = seg * step
            guard offset + fftSize <= samples.count else { break }

            // Window this segment in place — offset directly into `samples` via
            // the base pointer (no per-segment `Array` allocation, no redundant
            // window pass over the buffer start).
            vDSP_vmul(
                samplesPtr.baseAddress! + offset, 1,
                window, 1,
                &windowed, 1,
                vDSP_Length(fftSize)
            )

            // Pack into split-complex form for the real FFT.
            windowed.withUnsafeBufferPointer { src in
                realp.withUnsafeMutableBufferPointer { rp in
                    imagp.withUnsafeMutableBufferPointer { ip in
                        var split = DSPSplitComplex(realp: rp.baseAddress!, imagp: ip.baseAddress!)
                        src.baseAddress!.withMemoryRebound(to: DSPComplex.self, capacity: halfN) { complexPtr in
                            vDSP_ctoz(complexPtr, 2, &split, 1, vDSP_Length(halfN))
                        }
                        // In-place FFT.
                        vDSP_fft_zrip(fftSetup, &split, 1, log2n, FFTDirection(kFFTDirection_Forward))

                        // Compute magnitude squared per bin and accumulate.
                        let cols = min(columns, halfN)
                        for i in 0..<cols {
                            let re = rp[i]
                            let im = ip[i]
                            summedPower[i] += re * re + im * im
                        }
                    }
                }
            }
        }
        } // samples.withUnsafeBufferPointer

        return summedPower
    }
}
