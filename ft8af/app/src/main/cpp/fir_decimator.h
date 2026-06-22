// Fixed-ratio FIR low-pass decimator for the USB audio capture path.
//
// The capture loop receives 48 kHz audio and the FT8 decoder wants 12 kHz, so we drop the
// rate by an integer ratio (4). The old code just averaged `ratio` consecutive samples — a
// box filter whose stopband rejection is only ~12 dB, so broadband hiss and anything above
// the 6 kHz output Nyquist folds back into the 0-3 kHz FT8 passband and raises the noise
// floor. WSJT-X on a PC sound card doesn't pay that penalty; this brings us closer by
// anti-aliasing with a proper windowed-sinc low-pass before decimating.
//
// Header-only and free of JNI/libusb/Android deps on purpose, so the DSP can be unit-tested
// on the host (see ft8af_glue/test_fir_decimator.cpp + run_host_tests).

#ifndef FT8AF_FIR_DECIMATOR_H
#define FT8AF_FIR_DECIMATOR_H

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

#ifndef M_PI
#define M_PI 3.14159265358979323846
#endif

class FirDecimator {
public:
    // ratio: input rate / output rate (clamped to >= 1).
    // tapsPerPhase: half-length per polyphase arm; larger = sharper transition / deeper
    //   stopband at more cost. 8 with a Blackman window gives ~ -58 dB stopband, plenty for
    //   FT8's weak-signal needs.
    explicit FirDecimator(int ratio, int tapsPerPhase = 8) {
        configure(ratio, tapsPerPhase);
    }

    // Rebuild for a new ratio and clear state. Safe to call before the rate is known.
    void configure(int ratio, int tapsPerPhase = 8) {
        ratio_ = ratio < 1 ? 1 : ratio;
        buildKernel(tapsPerPhase < 1 ? 1 : tapsPerPhase);
        history_.assign(kernel_.size(), 0.0f);
        pos_ = 0;
        phase_ = 0;
    }

    // Drop all filter state (e.g. on stream restart) without rebuilding the kernel.
    void reset() {
        std::fill(history_.begin(), history_.end(), 0.0f);
        pos_ = 0;
        phase_ = 0;
    }

    int ratio() const { return ratio_; }
    std::size_t numTaps() const { return kernel_.size(); }

    // Push `n` input samples; append each decimated output sample to `out`. Streaming-safe:
    // call repeatedly across buffers, state carries over. Produces about n/ratio outputs.
    void process(const float* in, int n, std::vector<float>& out) {
        const int N = static_cast<int>(kernel_.size());
        for (int i = 0; i < n; ++i) {
            history_[pos_] = in[i];
            pos_ = (pos_ + 1 == N) ? 0 : pos_ + 1;
            if (++phase_ >= ratio_) {
                phase_ = 0;
                out.push_back(dot());
            }
        }
    }

private:
    // Convolve the current history window with the (symmetric) kernel. history_[pos_] holds
    // the oldest sample; walking forward pairs it with kernel_[0]. The kernel is symmetric so
    // the direction only sets a constant group delay, which is irrelevant to FT8.
    float dot() const {
        const int N = static_cast<int>(kernel_.size());
        float acc = 0.0f;
        int idx = pos_;
        for (int k = 0; k < N; ++k) {
            acc += history_[idx] * kernel_[k];
            idx = (idx + 1 == N) ? 0 : idx + 1;
        }
        return acc;
    }

    void buildKernel(int tapsPerPhase) {
        const int N = 2 * tapsPerPhase * ratio_ + 1;  // odd -> exact symmetry about center
        const int M = N - 1;
        // Cutoff just below the output Nyquist (0.5/ratio cycles/sample); 0.45/ratio leaves a
        // small transition band so the passband stays flat to ~0.9 of Nyquist (~5.4 kHz at
        // 48k->12k), well above FT8's ~3 kHz top.
        const double fc = 0.45 / static_cast<double>(ratio_);

        kernel_.resize(N);
        double sum = 0.0;
        for (int k = 0; k < N; ++k) {
            const double m = k - M / 2.0;
            const double sinc = (std::abs(m) < 1e-9)
                    ? 2.0 * fc
                    : std::sin(2.0 * M_PI * fc * m) / (M_PI * m);
            // Blackman window for a deep, smooth stopband.
            const double w = 0.42
                    - 0.5 * std::cos(2.0 * M_PI * k / M)
                    + 0.08 * std::cos(4.0 * M_PI * k / M);
            const double h = sinc * w;
            kernel_[k] = static_cast<float>(h);
            sum += h;
        }
        // Normalize to unity DC gain so the decimator neither boosts nor attenuates level.
        const float norm = static_cast<float>(1.0 / sum);
        for (float& c : kernel_) {
            c *= norm;
        }
    }

    int ratio_ = 1;
    std::vector<float> kernel_;
    std::vector<float> history_;
    int pos_ = 0;
    int phase_ = 0;
};

#endif  // FT8AF_FIR_DECIMATOR_H
