# FT8AF native module.
#
# Builds a single libft8af.so containing:
#   - All FT8/FT4/FT2 DSP (encode, decode, FFT, resample, hash, GFSK)
#   - USB Audio Class capture (libusb-backed)
#
# The prebuilt libft8cn.so is no longer used — all native functionality is
# now built from the in-tree kgoba ft8_lib (pinned at 6f528128) and the
# ft8af_glue JNI wrappers.
#
# Why libusb: Android's UsbRequest API silently fails to drive
# isochronous transfers on some kernels (notably automotive Android
# variants), throwing IllegalStateException on first queue. libusb
# uses USBDEVFS ioctls directly and is the most battle-tested option
# for USB audio on Android.

cmake_minimum_required(VERSION 3.18.1)

project(ft8af_native)

# ---------------------------------------------------------------------------
# libusb 1.0.27 (static)
# ---------------------------------------------------------------------------
# Source list mirrors libusb/android/jni/libusb.mk — only the Linux/USBFS
# backend + POSIX threads/events; the macOS/Windows/Haiku/etc. backends in
# libusb/os/ are intentionally not compiled.

set(LIBUSB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/libusb)

add_library(usb1.0 STATIC
        ${LIBUSB_ROOT}/libusb/core.c
        ${LIBUSB_ROOT}/libusb/descriptor.c
        ${LIBUSB_ROOT}/libusb/hotplug.c
        ${LIBUSB_ROOT}/libusb/io.c
        ${LIBUSB_ROOT}/libusb/sync.c
        ${LIBUSB_ROOT}/libusb/strerror.c
        ${LIBUSB_ROOT}/libusb/os/linux_usbfs.c
        ${LIBUSB_ROOT}/libusb/os/events_posix.c
        ${LIBUSB_ROOT}/libusb/os/threads_posix.c
        ${LIBUSB_ROOT}/libusb/os/linux_netlink.c
)

target_include_directories(usb1.0
        PUBLIC
        ${LIBUSB_ROOT}/libusb
        PRIVATE
        ${LIBUSB_ROOT}/android
        ${LIBUSB_ROOT}/libusb/os
)

# libusb assumes POSIX threads, eventfd, timerfd, etc. — all present on
# Android. Hidden visibility matches upstream's Android build so we don't
# accidentally re-export libusb symbols from our .so.
target_compile_options(usb1.0 PRIVATE
        -fvisibility=hidden
        -pthread
        -Wno-pointer-arith
        -Wno-deprecated-declarations
)

# ---------------------------------------------------------------------------
# ft8af_dsp — from-source FT8/FT4/FT2 DSP (static)
# ---------------------------------------------------------------------------
# The full kgoba ft8_lib (pinned at 6f528128) including encode.c. All DSP
# symbols are linked into the single libft8af.so shared library; no prebuilt
# dependency remains.

set(FT8LIB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib)

add_library(ft8af_dsp STATIC
        ${FT8LIB_ROOT}/ft8/encode.c
        ${FT8LIB_ROOT}/ft8/decode.c
        ${FT8LIB_ROOT}/ft8/ldpc.c
        ${FT8LIB_ROOT}/ft8/crc.c
        ${FT8LIB_ROOT}/ft8/message.c
        ${FT8LIB_ROOT}/ft8/pack.c
        ${FT8LIB_ROOT}/ft8/unpack.c
        ${FT8LIB_ROOT}/ft8/text.c
        ${FT8LIB_ROOT}/ft8/constants.c
        ${FT8LIB_ROOT}/common/monitor.c
        ${FT8LIB_ROOT}/fft/kiss_fft.c
        ${FT8LIB_ROOT}/fft/kiss_fftr.c
)

target_include_directories(ft8af_dsp PUBLIC ${FT8LIB_ROOT})

target_compile_options(ft8af_dsp PRIVATE
        -Wno-unused-function
)

# ---------------------------------------------------------------------------
# libft8af.so — unified JNI bridge (replaces both libft8cn.so and libft8af_usb.so)
# ---------------------------------------------------------------------------

add_library(ft8af SHARED
        # USB audio
        usb_audio.cpp
        usb_audio_capture.cpp
        # GFSK synthesis
        ft8af_glue/gfsk.c
        # JNI wrappers — encode/pack/GFSK
        ft8af_glue/ft8_encode_jni.cpp
        # JNI wrappers — callsign hash
        ft8af_glue/ft8_hash_jni.cpp
        # JNI wrappers — FFT display
        ft8af_glue/ft8_fft_jni.cpp
        ft8af_glue/fft_display.c
        # JNI wrappers — audio resampler
        ft8af_glue/ft8_resample_jni.cpp
        # JNI wrappers — FT8 decoder (replaces prebuilt)
        ft8af_glue/ft8_decode_jni.cpp
        # Deep-decode signal subtraction (shared with host bench + desktop)
        ft8af_glue/ft8_subtract.c
        # JNI wrappers — FT2/FT4 decoder (from-source, was in ft8af_usb)
        ft8af_glue/ft2_decode_jni.cpp
)

target_include_directories(ft8af PRIVATE ${FT8LIB_ROOT})

find_library(log-lib log)

target_link_options(ft8af PRIVATE "-Wl,-z,max-page-size=16384")

target_link_libraries(ft8af
        usb1.0
        ft8af_dsp
        ${log-lib}
)
