32-bit (i686 / x86) Hamlib runtime DLLs
=======================================

Drop the **win32** Hamlib release DLLs in this directory so the 32-bit
(`i686-pc-windows-msvc`) build bundles rig control. `build.rs::copy_hamlib_dlls()`
copies every *.dll here next to the built i686 exe when TARGET is `i686-*`; the
win64 set in ../x64 is deliberately NOT used because a 64-bit DLL cannot load
into a 32-bit process.

Expected files (names differ from the x64 set — the 32-bit MinGW gcc runtime is
`libgcc_s_dw2-1.dll`, not the x64 `libgcc_s_seh-1.dll`):

  libhamlib-4.dll      the Hamlib library itself (LGPL)
  libusb-1.0.dll       USB backend dependency
  libgcc_s_dw2-1.dll   MinGW gcc runtime (DWARF unwinding — 32-bit x86)
  libwinpthread-1.dll  MinGW pthreads runtime

Where to get them
-----------------
Download a win32 Hamlib release (e.g. `hamlib-w32-<ver>.zip`) from
https://github.com/Hamlib/Hamlib/releases and copy the four DLLs above out of
its `bin/` directory into here. Match the Hamlib version used for the x64 set in
../x64 (currently 4.7.1) so both installers ship the same API.

If this directory has no DLLs, the 32-bit build still succeeds — it just ships
without bundled Hamlib, and rig control degrades cleanly (`load_hamlib()` falls
back with a clear "install Hamlib" message instead of crashing). Users can still
install Hamlib system-wide or use the FLrig / serial-CAT backends.

Licensing/attribution for the Hamlib binaries lives one level up in
../LICENSE.txt, ../COPYING.txt (GPL) and ../COPYING.LIB.txt (LGPL, covers the
library DLL); the LGPL terms apply equally to the win32 DLL you place here.
