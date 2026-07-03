// Streaming polyphase rational (L/M) resampler for the USB audio capture path.
//
// The FT8 decoder wants 12 kHz mono. Most USB codecs stream 48 kHz, which the
// integer FirDecimator (fir_decimator.h) handles with a /4 decimation. But a
// device that only supports e.g. 44.1 kHz used to get its ratio floored
// (44100/12000 -> 3), so the capture path actually produced 14.7 kHz audio
// labeled 12 kHz — every FT8 tone shifted off the 6.25 Hz grid and nothing
// decoded (issue #364). This class resamples by the exact rational factor
// instead: interpolate by L, low-pass, decimate by M (44.1k -> 12k is
// L/M = 40/147), implemented as a polyphase FIR so the zero-stuffed samples
// are never materialized (~numTaps()/L multiplies per output sample).
//
// Kernel: Blackman-windowed sinc at the conceptual upsampled rate
// (inputRate * L), cutoff 0.45 / max(L, M) so it sits below both the input
// and the output Nyquist, with each polyphase branch normalized to unity DC
// sum (exact unity DC gain at every output phase; the xL zero-stuffing gain
// is baked in).
//
// Header-only and free of JNI/libusb/Android deps on purpose, so the DSP can
// be unit-tested on the host (see ft8af_glue/test_rational_resampler.cpp +
// run_host_tests). RationalResampler.java is a line-for-line port for the
// UsbRequest fallback capture path — keep the two in sync.

#ifndef FT8AF_RATIONAL_RESAMPLER_H
#define FT8AF_RATIONAL_RESAMPLER_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

// Reduced interpolate/decimate pair: outputRate/inputRate == L/M exactly.
struct RationalRatio {
    int L;  // interpolation factor
    int M;  // decimation factor
};

// gcd via Euclid; local so the header stays C++14 (std::gcd is C++17).
inline int rationalResamplerGcd(int a, int b) {
    while (b != 0) {
        int t = a % b;
        a = b;
        b = t;
    }
    return a;
}

// Rational ratio for an input/target sample-rate pair. Mirrors the guards of
// decimationRatioFor (fir_decimator.h): a zero/negative target or a target
// above the input rate degrades to {1, 1} (pass-through) instead of trying to
// upsample audio that has no content above the input Nyquist anyway; callers
// should warn. 48000/12000 -> {1, 4} (use the integer FirDecimator fast path
// for that); 44100/12000 -> {40, 147}.
inline RationalRatio rationalRatioFor(int inputRate, int targetRate) {
    if (targetRate <= 0 || inputRate < targetRate) {
        return {1, 1};
    }
    const int g = rationalResamplerGcd(inputRate, targetRate);
    return {targetRate / g, inputRate / g};
}

class RationalResampler {
public:
    // interpolation (L) / decimation (M): outputRate = inputRate * L / M.
    // tapsPerPhase scales the FIR length (2 * tapsPerPhase * max(L, M) + 1
    // taps at the upsampled rate, i.e. ~2*tapsPerPhase taps per polyphase
    // branch); 8 with a Blackman window gives ~ -58 dB stopband, matching
    // FirDecimator.
    explicit RationalResampler(int interpolation = 1, int decimation = 1,
                               int tapsPerPhase = 8) {
        configure(interpolation, decimation, tapsPerPhase);
    }

    // Rebuild for a new ratio and clear state. Safe to call before the rate
    // pair is known.
    void configure(int interpolation, int decimation, int tapsPerPhase = 8) {
        L_ = interpolation < 1 ? 1 : interpolation;
        M_ = decimation < 1 ? 1 : decimation;
        buildKernel(tapsPerPhase < 1 ? 1 : tapsPerPhase);
        // Enough input history for the deepest tap any output phase reaches.
        histLen_ = static_cast<int>((kernel_.size() - 1)) / L_ + 2;
        history_.assign(histLen_, 0.0f);
        reset();
    }

    // Drop all filter state (e.g. on stream restart) without rebuilding.
    void reset() {
        std::fill(history_.begin(), history_.end(), 0.0f);
        pos_ = 0;
        inCount_ = 0;
        nextT_ = 0;
    }

    int interpolation() const { return L_; }
    int decimation() const { return M_; }
    std::size_t numTaps() const { return kernel_.size(); }

    // Push `n` input samples; append each resampled output sample to `out`.
    // Streaming-safe: call repeatedly across buffers, state carries over.
    // Produces about n*L/M outputs per call.
    void process(const float* in, int n, std::vector<float>& out) {
        for (int i = 0; i < n; ++i) {
            history_[pos_] = in[i];
            pos_ = (pos_ + 1 == histLen_) ? 0 : pos_ + 1;
            ++inCount_;
            // Output sample k lives at upsampled index k*M; it is computable
            // once the newest input sample (upsampled index (inCount_-1)*L)
            // has arrived, because the FIR only looks backward from there.
            const std::int64_t newestT = (inCount_ - 1) * static_cast<std::int64_t>(L_);
            while (nextT_ <= newestT) {
                out.push_back(interpolateAt(nextT_));
                nextT_ += M_;
            }
        }
    }

private:
    // Evaluate the polyphase FIR at upsampled index t: with w[] the
    // zero-stuffed input (w[j*L] = x[j], 0 elsewhere), the output is
    // sum_k h[k] * w[t - k]; only k = phase + m*L hit non-zero samples, so we
    // walk taps h[phase], h[phase+L], ... against inputs x[t/L], x[t/L - 1],...
    float interpolateAt(std::int64_t t) const {
        const int N = static_cast<int>(kernel_.size());
        std::int64_t j = t / L_;                       // newest input index used
        int k = static_cast<int>(t - j * L_);          // kernel phase
        int back = static_cast<int>(inCount_ - 1 - j); // 0 == newest sample
        int idx = pos_ - 1 - back;
        while (idx < 0) idx += histLen_;
        float acc = 0.0f;
        while (k < N && j >= 0 && back < histLen_) {
            acc += history_[idx] * kernel_[k];
            k += L_;
            --j;
            ++back;
            idx = (idx == 0) ? histLen_ - 1 : idx - 1;
        }
        return acc;
    }

    void buildKernel(int tapsPerPhase) {
        const int lm = L_ > M_ ? L_ : M_;
        const int N = 2 * tapsPerPhase * lm + 1;  // odd -> exact symmetry
        const int Mid = N - 1;
        // Cutoff below both Nyquists in cycles per *upsampled* sample: the
        // input Nyquist is 0.5/L, the output Nyquist is 0.5/M; 0.45/max keeps
        // a small transition band under the binding one. For 44.1k -> 12k
        // (L=40, M=147) that is ~5.4 kHz — well above FT8's ~3 kHz top.
        const double fc = 0.45 / static_cast<double>(lm);

        kernel_.resize(N);
        for (int k = 0; k < N; ++k) {
            const double m = k - Mid / 2.0;
            const double sinc = (std::abs(m) < 1e-9)
                    ? 2.0 * fc
                    : std::sin(2.0 * M_PI * fc * m) / (M_PI * m);
            // Blackman window for a deep, smooth stopband.
            const double w = 0.42
                    - 0.5 * std::cos(2.0 * M_PI * k / Mid)
                    + 0.08 * std::cos(4.0 * M_PI * k / Mid);
            kernel_[k] = static_cast<float>(sinc * w);
        }
        // Normalize each polyphase branch (taps k ≡ p mod L) to unity DC sum:
        // every output phase then has exactly unity DC gain, and the xL gain
        // that compensates zero-stuffing is baked in. For L == 1 this reduces
        // to FirDecimator's whole-kernel normalization.
        for (int p = 0; p < L_; ++p) {
            double sum = 0.0;
            for (int k = p; k < N; k += L_) sum += kernel_[k];
            if (std::abs(sum) < 1e-12) continue;
            const float norm = static_cast<float>(1.0 / sum);
            for (int k = p; k < N; k += L_) kernel_[k] *= norm;
        }
    }

    int L_ = 1;
    int M_ = 1;
    std::vector<float> kernel_;
    std::vector<float> history_;
    int histLen_ = 2;
    int pos_ = 0;
    std::int64_t inCount_ = 0;
    std::int64_t nextT_ = 0;
};

#endif  // FT8AF_RATIONAL_RESAMPLER_H
