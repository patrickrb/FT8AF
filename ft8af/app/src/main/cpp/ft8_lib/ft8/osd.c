// See osd.h. Ported from ft8mon's osd.cc (Robert Morris, AB1HL — GPL; the
// algorithm follows WSJT-X's osd174_91.f90 by Franke/Taylor), rewritten
// around this library's bit-packed kFTX_LDPC_generator and 64-bit bitset
// rows instead of ft8mon's int[174][91] tables.
//
// Cost per call: one incremental Gauss-Jordan over at most 174 rows of
// 91 bits (two 64-bit words per row), then (1 + depth) re-encode+score
// evaluations — tens of microseconds. Cheap enough to run on every
// near-miss candidate in the deep passes.

#include "osd.h"

#include <math.h>
#include <stdatomic.h>
#include <string.h>

#include "crc.h"

// Reject any OSD "solution" whose re-encoding disagrees with the received
// hard decisions in more than this many of the 174 bit positions. A genuine
// weak decode disagrees in some bits (that's why BP failed); random noise
// shaped into a codeword disagrees in ~half (~87). The CRC-14 alone passes
// 1 in 16k junk candidates — this is the second gate. Benchmark-swept:
// 42 admits junk free-text decodes the corpus truth doesn't contain; 34
// starts losing genuine weak decodes; 38 keeps every genuine decode of the
// 42 setting while rejecting the junk. Also validated by the pure-noise
// trials in test_osd.c.
#ifndef OSD_NHARD_MAX
#define OSD_NHARD_MAX 38
#endif

// Stricter acceptance for order-2 (pair-flip) solutions. Pair flips run
// C(depth,2) trials per candidate instead of `depth`, so junk gets many more
// draws against the CRC+nhard lottery. Benchmark-swept: at the order-1 gate
// (38) the pairs admit fabricated decodes ("CQ VTIN 0J1NTF R 579 2608"-class
// junk); 30..34 all keep the genuine pair recoveries and reject every junk
// decode on the corpus; 28 starts losing genuine ones. 30 chosen for margin.
#ifndef OSD_NHARD_MAX_ORDER2
#define OSD_NHARD_MAX_ORDER2 30
#endif

// ---------------------------------------------------------------------------
// 91-bit vectors as two 64-bit words.
// ---------------------------------------------------------------------------
typedef struct
{
    uint64_t w[2];
} bits91_t;

static inline bool bits91_get(const bits91_t* v, int i)
{
    return (v->w[i >> 6] >> (i & 63)) & 1u;
}

static inline void bits91_flip(bits91_t* v, int i)
{
    v->w[i >> 6] ^= (uint64_t)1 << (i & 63);
}

static inline void bits91_xor(bits91_t* a, const bits91_t* b)
{
    a->w[0] ^= b->w[0];
    a->w[1] ^= b->w[1];
}

static inline bool bits91_zero(const bits91_t* v)
{
    return (v->w[0] | v->w[1]) == 0;
}

static inline int bits91_parity_and(const bits91_t* a, const bits91_t* b)
{
    uint64_t x = (a->w[0] & b->w[0]) ^ (a->w[1] & b->w[1]);
    x ^= x >> 32;
    x ^= x >> 16;
    x ^= x >> 8;
    x ^= x >> 4;
    x ^= x >> 2;
    x ^= x >> 1;
    return (int)(x & 1);
}

// ---------------------------------------------------------------------------
// Generator rows for all 174 codeword bits: rows 0..90 are the identity
// (systematic message bits), rows 91..173 come from kFTX_LDPC_generator
// (bit-packed MSB-first, 91 bits in 12 bytes). Built once, lazily, with an
// atomic tri-state guard so concurrent decoders (e.g. a future threaded
// decode loop) can't race the initialization: 0 = uninitialized,
// 1 = one thread building, 2 = ready.
// ---------------------------------------------------------------------------
static bits91_t g_rows[FTX_LDPC_N];
static atomic_int g_rows_state; // zero-initialized = uninitialized

static void build_rows(void)
{
    memset(g_rows, 0, sizeof(g_rows));
    for (int i = 0; i < FTX_LDPC_K; ++i)
        bits91_flip(&g_rows[i], i);
    for (int p = 0; p < FTX_LDPC_M; ++p)
    {
        for (int j = 0; j < FTX_LDPC_K; ++j)
        {
            if ((kFTX_LDPC_generator[p][j / 8] >> (7 - (j % 8))) & 1u)
                bits91_flip(&g_rows[FTX_LDPC_K + p], j);
        }
    }
}

static void ensure_rows(void)
{
    if (atomic_load_explicit(&g_rows_state, memory_order_acquire) == 2)
        return;
    int expected = 0;
    if (atomic_compare_exchange_strong_explicit(&g_rows_state, &expected, 1,
                                                memory_order_acq_rel, memory_order_acquire))
    {
        build_rows();
        atomic_store_explicit(&g_rows_state, 2, memory_order_release);
    }
    else
    {
        // Another thread is building; the table takes microseconds, spin.
        while (atomic_load_explicit(&g_rows_state, memory_order_acquire) != 2)
        {
        }
    }
}

// Re-encode 91 message bits (as a bits91_t) into the full 174-bit codeword.
static void encode_codeword(const bits91_t* plain, uint8_t cw[FTX_LDPC_N])
{
    for (int i = 0; i < FTX_LDPC_K; ++i)
        cw[i] = bits91_get(plain, i);
    for (int p = 0; p < FTX_LDPC_M; ++p)
        cw[FTX_LDPC_K + p] = (uint8_t)bits91_parity_and(&g_rows[FTX_LDPC_K + p], plain);
}

// CRC-14 check over the message bits (77 payload + 14 CRC), mirroring the
// check in ft8_decode(): pack, extract, zero the payload tail, recompute.
static bool crc_ok(const bits91_t* plain)
{
    uint8_t a91[FTX_LDPC_K_BYTES];
    memset(a91, 0, sizeof(a91));
    for (int i = 0; i < FTX_LDPC_K; ++i)
        if (bits91_get(plain, i))
            a91[i / 8] |= (uint8_t)(0x80u >> (i % 8));

    uint16_t crc_extracted = ftx_extract_crc(a91);
    a91[9] &= 0xF8;
    a91[10] = 0;
    a91[11] = 0;
    return crc_extracted == ftx_compute_crc(a91, 96 - 14);
}

// Evaluate a candidate message: valid codeword bits are re-encoded and
// compared to the received hard decisions (nhard; the candidate is REJECTED
// when nhard exceeds nhard_max) and soft values (dist, the summed |LLR| of
// disagreeing bits — lower is better).
static bool evaluate(const bits91_t* plain, const float log174[FTX_LDPC_N],
                     int nhard_max, int* nhard_out, float* dist_out)
{
    if (bits91_zero(plain))
        return false;
    if (!crc_ok(plain))
        return false;

    uint8_t cw[FTX_LDPC_N];
    encode_codeword(plain, cw);
    int nhard = 0;
    float dist = 0.0f;
    for (int i = 0; i < FTX_LDPC_N; ++i)
    {
        uint8_t rx = (log174[i] > 0) ? 1 : 0;
        if (cw[i] != rx)
        {
            nhard++;
            dist += fabsf(log174[i]);
        }
    }
    if (nhard > nhard_max)
        return false;
    *nhard_out = nhard;
    *dist_out = dist;
    return true;
}

bool osd_decode(const float log174[FTX_LDPC_N], int depth,
                uint8_t plain174[FTX_LDPC_N], int* out_depth)
{
    ensure_rows();

    // Codeword bit indices sorted by |LLR|, most reliable first
    // (insertion sort — 174 elements, negligible).
    int order[FTX_LDPC_N];
    for (int i = 0; i < FTX_LDPC_N; ++i)
        order[i] = i;
    for (int i = 1; i < FTX_LDPC_N; ++i)
    {
        int idx = order[i];
        float s = fabsf(log174[idx]);
        int j = i - 1;
        while (j >= 0 && fabsf(log174[order[j]]) < s)
        {
            order[j + 1] = order[j];
            --j;
        }
        order[j + 1] = idx;
    }

    // Incremental Gauss-Jordan over GF(2): accept, in reliability order, 91
    // linearly independent generator rows. For accepted equation j:
    //   red[j] . plain = XOR of received bits y_k for k in rhs[j]
    // Full Jordan keeps each red[j] reduced to its single pivot column, so
    // at the end plain[pivot_col[j]] = parity(rhs[j] & y).
    bits91_t red[FTX_LDPC_K];
    bits91_t rhs[FTX_LDPC_K];
    int pivot_col[FTX_LDPC_K];
    int accepted_idx[FTX_LDPC_K];
    int nacc = 0;

    for (int t = 0; t < FTX_LDPC_N && nacc < FTX_LDPC_K; ++t)
    {
        int r = order[t];
        bits91_t row = g_rows[r];
        bits91_t m = { { 0, 0 } };
        for (int j = 0; j < nacc; ++j)
        {
            if (bits91_get(&row, pivot_col[j]))
            {
                bits91_xor(&row, &red[j]);
                bits91_xor(&m, &rhs[j]);
            }
        }
        if (bits91_zero(&row))
            continue; // dependent on already-accepted rows
        // This equation participates in its own RHS.
        bits91_flip(&m, nacc);
        // Pivot on the lowest set bit; eliminate it from earlier rows.
        int pc = 0;
        while (!bits91_get(&row, pc))
            ++pc;
        for (int j = 0; j < nacc; ++j)
        {
            if (bits91_get(&red[j], pc))
            {
                bits91_xor(&red[j], &row);
                bits91_xor(&rhs[j], &m);
            }
        }
        red[nacc] = row;
        rhs[nacc] = m;
        pivot_col[nacc] = pc;
        accepted_idx[nacc] = r;
        nacc++;
    }
    if (nacc < FTX_LDPC_K)
        return false; // generator not full rank over the received bits (can't happen)

    // Hard decisions of the accepted received bits.
    bits91_t y = { { 0, 0 } };
    for (int j = 0; j < FTX_LDPC_K; ++j)
        if (log174[accepted_idx[j]] > 0)
            bits91_flip(&y, j);

    bits91_t best_plain;
    float best_dist = 0.0f;
    int best_depth = -2;

    for (int flip = -1; flip < depth && flip < FTX_LDPC_K; ++flip)
    {
        // flip == -1: base decode. flip >= 0: toggle the (flip+1)-th least
        // reliable accepted bit (mirrors ft8mon's y1[91-1-ii] ^= 1).
        bits91_t ytry = y;
        if (flip >= 0)
            bits91_flip(&ytry, FTX_LDPC_K - 1 - flip);

        bits91_t plain = { { 0, 0 } };
        for (int j = 0; j < FTX_LDPC_K; ++j)
            if (bits91_parity_and(&rhs[j], &ytry))
                bits91_flip(&plain, pivot_col[j]);

        int nhard;
        float dist;
        if (!evaluate(&plain, log174, OSD_NHARD_MAX, &nhard, &dist))
            continue;
        if (best_depth == -2 || dist < best_dist)
        {
            best_plain = plain;
            best_dist = dist;
            best_depth = flip;
            if (flip == -1)
                break; // base decode passed all gates: accept immediately
        }
    }

    // Order-2: toggle every PAIR of the `depth` least reliable accepted bits
    // (C(depth,2) trials; 66 at depth 12). Reaches decodes where TWO
    // moderately unreliable bits are wrong at once — measured on the bench
    // corpus this converts co-channel near-misses (BP a few parity checks
    // short) that no single flip can fix, e.g. two overlapping strong
    // signals that block each other's first decode and therefore the whole
    // subtract-and-redecode chain. Skipped when the base decode already
    // passed (early break above leaves best_depth == -1). The same CRC +
    // nhard gates in evaluate() bound the false-decode risk; cost is a few
    // microseconds per trial.
    if (best_depth != -1)
    {
        for (int f1 = 0; f1 < depth - 1 && f1 < FTX_LDPC_K - 1; ++f1)
        {
            for (int f2 = f1 + 1; f2 < depth && f2 < FTX_LDPC_K; ++f2)
            {
                bits91_t ytry = y;
                bits91_flip(&ytry, FTX_LDPC_K - 1 - f1);
                bits91_flip(&ytry, FTX_LDPC_K - 1 - f2);

                bits91_t plain = { { 0, 0 } };
                for (int j = 0; j < FTX_LDPC_K; ++j)
                    if (bits91_parity_and(&rhs[j], &ytry))
                        bits91_flip(&plain, pivot_col[j]);

                int nhard;
                float dist;
                if (!evaluate(&plain, log174, OSD_NHARD_MAX_ORDER2, &nhard, &dist))
                    continue;
                if (best_depth == -2 || dist < best_dist)
                {
                    best_plain = plain;
                    best_dist = dist;
                    best_depth = f2; // deepest single index touched
                }
            }
        }
    }

    if (best_depth == -2)
        return false;

    encode_codeword(&best_plain, plain174);
    if (out_depth)
        *out_depth = best_depth;
    return true;
}
