64-bit (x86_64) Hamlib runtime DLLs
===================================

These are the win64 Hamlib release DLLs (Hamlib 4.7.1, MinGW cross-build — see
../README.w64-bin.txt). `build.rs::copy_hamlib_dlls()` copies every *.dll here
next to the built x86_64 exe so rig control works out of the box.

Contents:
  libhamlib-4.dll      the Hamlib library itself (LGPL)
  libusb-1.0.dll       USB backend dependency
  libgcc_s_seh-1.dll   MinGW gcc runtime (SEH unwinding — x64)
  libwinpthread-1.dll  MinGW pthreads runtime

To update, drop a newer win64 Hamlib release's DLLs in place here.

Licensing/attribution for these binaries lives one level up in ../LICENSE.txt,
../COPYING.txt (GPL) and ../COPYING.LIB.txt (LGPL, covers the library DLL).
