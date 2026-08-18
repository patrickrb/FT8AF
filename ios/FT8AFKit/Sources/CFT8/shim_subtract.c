// Shim TU: compiles the real ft8af_glue subtraction source in place (no copy).
// See Package.swift / ios/README.md for why the C lives outside this package.
//
// This pulls in both ft8_subtract_signal (waterfall-domain) and
// ft8_subtract_signal_time (coherent time-domain, WSJT-X subtractft8 approach),
// the exact same C the Android JNI glue (ft8_decode_jni.cpp) calls from
// ReBuildSignal.doSubtractSignal. ft8_subtract.c references synth_gfsk_dphi_alloc
// (compiled by shim_gfsk.c) and ft8_encode (compiled by shim_encode.c); both are
// separate TUs in this same CFT8 target, so the symbols resolve at link time.
#include "../../../../ft8af/app/src/main/cpp/ft8af_glue/ft8_subtract.c"
