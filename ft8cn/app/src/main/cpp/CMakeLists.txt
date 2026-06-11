# FT8AF native USB audio module.
#
# Builds libft8af_usb.so containing our libusb-backed USB Audio Class
# capture path, with libusb 1.0.27 statically linked in. The shared
# library is packaged alongside the upstream-prebuilt libft8cn.so
# (which we don't compile here — see jniLibs.srcDirs = ['libs']).
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
# Prebuilt libft8cn.so (imported)
# ---------------------------------------------------------------------------
# The FT8/FT4 DSP lives in the upstream prebuilt libft8cn.so packaged under
# app/libs/<abi>/. We don't compile it, but we link ft8af_usb against it so the
# FT4 encode JNI shim (ft4_encode_jni.cpp) can call the prebuilt's `ft4_encode`.

add_library(ft8cn_prebuilt SHARED IMPORTED)
set_target_properties(ft8cn_prebuilt PROPERTIES
        IMPORTED_LOCATION
        ${CMAKE_CURRENT_SOURCE_DIR}/../../../libs/${ANDROID_ABI}/libft8cn.so
)

# ---------------------------------------------------------------------------
# ft8af_dsp — from-source FT2 decoder (static)
# ---------------------------------------------------------------------------
# The prebuilt libft8cn.so decodes FT8/FT4 only. FT2 (FT4 at double baud) is decoded
# with the in-tree kgoba ft8_lib (pinned at 6f528128 — see ft8_lib/FT8_LIB_PIN.txt, the
# same commit the prebuilt was built from), with FT2 added to the protocol switch.
#
# Only the decode slice is compiled (no encode.c — FT2 TX reuses the prebuilt's ft4_encode
# via ft4_encode_jni.cpp). Built with -fvisibility=hidden so these symbols (ft8_find_sync,
# monitor_init, ldpc/crc tables, ...) stay INTERNAL to libft8af_usb.so and never clash with
# or interpose the identically-named symbols the prebuilt libft8cn.so exports. The JNI
# entry points in ft2_decode_jni.cpp keep default visibility (JNIEXPORT) and are compiled
# into the shared lib directly so they aren't garbage-collected from a static archive.

set(FT8LIB_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/ft8_lib)

add_library(ft8af_dsp STATIC
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
        -fvisibility=hidden
        -Wno-unused-function
)

# ---------------------------------------------------------------------------
# libft8af_usb.so — our JNI bridge
# ---------------------------------------------------------------------------

add_library(ft8af_usb SHARED
        usb_audio.cpp
        usb_audio_capture.cpp
        ft4_encode_jni.cpp
        ft8cn_glue/ft2_decode_jni.cpp
)

# ft2_decode_jni.cpp includes "ft8/..", "common/.." headers from ft8_lib.
target_include_directories(ft8af_usb PRIVATE ${FT8LIB_ROOT})

find_library(log-lib log)

target_link_libraries(ft8af_usb
        usb1.0
        ft8af_dsp
        ft8cn_prebuilt
        ${log-lib}
)
