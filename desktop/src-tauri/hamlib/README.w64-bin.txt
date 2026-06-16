What is it?
===========

This ZIP archive or Windows installer contains a build of Hamlib-4.7.1
cross-compiled for MS Windows 64 bit using MinGW under Debian GNU/Linux 13
(nice, heh!).

This software is copyrighted. The library license is LGPL, and the *.EXE files
licenses are GPL.  Hamlib comes WITHOUT ANY WARRANTY. See the LICENSE.txt,
COPYING.txt, and COPYING.LIB.txt files.

Supporting documentation in the form of Unix manual pages have also been
included after being converted to HTML.


Installation and Configuration
==============================

Extract the ZIP archive into a convenient location, C:\Program Files is a
reasonable choice.

The archive directory structure is:

hamlib-w64-4.7~git
├── bin
├── doc
├── include
│   └── hamlib
└── lib
    ├── gcc
    └── msvc

The 'bin' and 'doc' directories will be of interest to users while developers
will be interested in the 'include' and 'lib' directories as well.

Make sure *all* the .DLL files are in your PATH (leave them in the 'bin'
directory and set the PATH).  To set the PATH environment variable in Windows
2000, Windows XP, and Windows 7 (need info on Vista and Windows 8/10) do the
following:

 * W2k/XP: Right-click on "My Computer"
   Win7: Right-click on "Computer"

 * W2k/XP: Click the "Advanced" tab of the "System Properties" dialog
   Win7: Click the "Advanced system settings" link in the System dialog

 * Click the "Environment Variables" button of the pop-up dialog

 * Select "Path" in the "System variables" box of the "Environment Variables"
   dialog

   NB: If you are not the administrator, system policy may not allow editing
   the path variable.  The complete path to an executable file will need to be
   given to run one of the Hamlib programs.

 * Click the Edit button

 * Now add the Hamlib path in the "Variable Value:" edit box.  Be sure to put
   a semi-colon ';' after the last path before adding the Hamlib path (NB. The
   entire path is highlighted and will be erased upon typing a character so
   click in the box to unselect the text first.  The PATH is important!!)
   Append the Hamlib path, e.g. C:\Program Files\hamlib-w64-4.7.1\bin

 * Click OK for all three dialog boxes to save your changes.


Testing with the Hamlib Utilities
=================================

To continue, be sure you have read the README.betatester file, especially the
"Testing Hamlib" section.  The primary means of testing is by way of the
rigctl utility for radios, the rotctl utility for rotators and the ampctl
utility for amplifiers.  Each is a command line program that is interactive
or can act on a single command and exit.

Documentation for each utility can be found as an HTML file in the doc
directory.

In short, the command syntax is of the form:

  rigctl -m 1020 -r COM1 -vvvvv

  -m -> Radio model 1020, or Yaesu FT-817 (use 'rigctl -l' for a list)
  -r -> Radio device, in this case COM1
  -v -> Verbosity level.  For testing four or five v characters are required.
        Five 'v's set a debug level of TRACE which generates a lot of screen
        output showing communication to the radio and values of important
        variables.  These traces are vital information for Hamlib rig backend
        development.

To run rigctl or rotctl open a cmd window (Start|Run|enter 'cmd' in the
dialog).  If text scrolls off the screen, you can scroll back with the mouse.
To copy output text into a mailer or editor (Notepad++, a free editor also
licensed under the GPL is recommended), highlight the text as a rectangle in
the cmd window, press <Enter> (or right-click the window icon in the upper left
corner and select Edit, then Copy), and paste it into your editor with Ctl-V
(or Edit|Paste from the typical GUI menu).

All feedback is welcome to the mail address below.


Uninstall
=========

To uninstall, simply delete the Hamlib directory.  You may wish to edit the
PATH as above to remove the Hamlib bin path, if desired.


Information for w64 Programmers
=================================

The DLL has a cdecl interface.

There is a libhamlib-4.def definition file for MS Visual C++/Visual Studio in
lib\msvc.  Refer to the recipe below to generate a local libhamlib-4.lib file
for use with the VC++/VS linker.

Simply '#include <hamlib/rig.h>' (or any other header) (add directory to
include path), include libhamlib-4.lib in your project and you are done.  Note:
VC++/VS cannot compile all the Hamlib code, but the API defined by rig.h has
been made MSVC friendly :-)

As the source code for the library DLLs is licensed under the LGPL, your
program is not considered a "derivative work" when using the published Hamlib
API and normal linking to the front-end library, and may be of a license of
your choosing.

As of 04 Aug 2025 a .lib file is generated using the MinGW dlltool utility.
This file is now installed to the lib\gcc directory.  As it is generated
by the MinGW dlltool utility, it is only suitable for use with MinGW/GCC.

For developers using Microsoft Visual Studio, the following recipe is
provided by Phil Rose, GM3ZZA:

My secret sauce is:

Open "Developer PowerShell for VS2022" in administrator mode. This adds the
correct directory to the path and allows update of "C:\Program Files" with the
.dll.

Then (in my case).

cd "C:\Program Files\hamlib-w64-4.7.1\lib\msvc"
lib /def:libhamlib-4.def /machine:x64

If you use any other terminal then the full path to lib.exe is needed
(today it is
"C:\Program Files\Microsoft Visual Studio\2022\Community\VC\Tools\MSVC\14.44.35207\bin\Hostx64\x64\lib.exe",
but is dependent on the version of MSVC which gets updated every few weeks).

NOTE: feedback is requested on Phil's example!  Please let know if this works
for you.

The published Hamlib API may be found at:

http://hamlib.sourceforge.net/manuals/4.1/index.html


Thank You!
==========

Patches, feedback, and contributions are welcome.

Please report problems or success to hamlib-developer@lists.sourceforge.net

Cheers,
Stephane Fillod - F8CFE
Mike Black - W9MDB (SK)
Nate Bargmann - N0NB
http://www.hamlib.org

