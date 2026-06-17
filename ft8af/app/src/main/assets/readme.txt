Disclaimer:
   FT8CN is intended for research purposes, to learn how to decode and transmit FT8 signals. The developers are not responsible for any consequences resulting from user operation of this app.
   Please comply with local laws and regulations when using FT8CN.
   Considering the performance and battery life limitations of mobile phones, signal processing uses lightweight algorithms without deep decoding.
   If you have suggestions or issues, please submit them to "FAQ."

Disclaimer
FT8CN aims to learn how to decode, transmit FT8 signal for research purposes, which is not responsible for the consequences caused by the user's operation.
Please comply with local laws and regulations when using FT8CN.
Considering the performance and endurance limitations of the mobile phone, the processing of the signal adopts lightweight operations instead of deep decoding and other processing.
Please click "FAQ" if you have good suggestions or questions .


BG7YOZ
2022-07-01

 2025-01-04(0.93)
  1. Fixed transmission supervision time calculation error.
  2. Fixed issue where the first record was missing when downloading log data.
  3. Added log data sharing feature.
  4. Added automatic log upload to QRZ and CloudLog (code contributed by SydneyOwl).
  5. Added SWR and ALC alarm switches.

 2024-01-22(0.92)
  1. Added QSO indicator for messages in the waterfall display.
  2. Added support for new radio models.
  3. Added serial port parameter settings.
  4. Added new decoded message types, supporting all FT8 message types.
  5. Fixed serial port error messages being Chinese-only.
  6. Improved SWL QSO log recording.
  7. Improved QSO procedure for messages with /P or /R suffix callsigns.


 2023-09-14(0.91 patch 1)
  1. Fixed Yaesu FT-891/991 USB-DATA mode selection error.

 2023-09-11(0.91)
  1. Fixed issue where RR73 was incorrectly sent as 73 when transmitting non-standard messages (i3=4).
  2. Fixed issue in multi-pass decode mode where responses to previous cycle decoded messages were delayed and 73 was missed.
  3. Fixed issue where the sender's callsign could be incorrect when generating messages where both parties have compound callsigns.
  4. Optimized auto-caller.

  5. Added (tr)uSDX Audio over CAT feature, code contributed by DS1UFX.
  6. Added support for XieGu X6100 WiFi mode (firmware version 1.1.7, transmit audio not yet resolved).
  7. Added support for Kenwood TS-570D.
  8. Added Yaesu FT-891/991 USB-DATA mode.
  9. Added one-click QSO log query for callsigns in the message list.
  10. Added compact message list display mode.

 2023-08-13(0.90)
  1. Added web interface interaction mode during log import.
  2. Fixed map crash when log data volume is too large.
  3. Optimized database structure, improved log data import and update speed (recommended to backup logs before updating to this version).
  4. Fixed some spelling errors.
  5. Added UA3REO Wolf SDR radio.
  6. Added GUOHE PMR-171 radio.

 2023-07-08(0.89)
  1. Added multi-pass decoding feature, which increases decode depth in multi-pass mode and attempts to decode overlapping signals.
  2. Fixed issue where worked zones were not updated promptly after importing ADI files.
  3. Fixed audio distortion on iCom radios in network mode during transmission.
  4. Fixed RR73 deadlock issue in certain situations.
  5. Improved decoding stability.
  6. Fixed occasional incorrect display of the comment field in decoded messages.
  7. Updated log export prompts.


 2023-05-02(0.88 Patch 2)
  1. Added audio output settings (bit depth, sample rate).
  2. Added conditional query and export for logs.
  3. Changed log query to display in descending time order.
  4. Fixed duplicate SWL QSO records issue.
  5. Optimized web UI for major browsers.
  patch 2
  6. Fixed crash when locating QSO records.

 2023-03-24(0.87)
  1. Added map positioning display for queried QSO log results.
  2. Added FlexRadio meter display and parameter settings (transmission not yet supported).
  3. Added automatic time synchronization feature (server is Microsoft NTP).
  4. Added SWL mode with save and export capabilities for decoded messages and QSOs (SWL QSO criteria: at least both parties' reports plus a closing message of 73, RR73, or RRR).
  5. Enhanced web interface query features.
  6. Fixed distance calculation error in "Callsign and Grid Mapping Table" web query.
  7. Adjusted radio model options for XieGu G90S future firmware.
  8. Fixed UI lag when there are many decoded messages.
  9. Optimized log query performance.

 2023-02-06(0.86)
  1. Improved log import robustness, reporting format errors in log data.
  2. Fixed occasional crash due to array index out of bounds when calculating SNR.
  3. Fixed inaccurate count calculation after importing logs.

 2023-01-28(0.85)
  1. Added excluded callsign prefix feature (excluded prefixes have the highest priority, filtering out unwanted calls for the auto-caller).
  2. Added sunrise/sunset gray line on the GridTracker map.
  3. Added clear followed callsign list feature.
  4. Added clear cached QSO message feature.
  5. Added call modifier feature. For example: CQ POTA xxxxxx xxxx, or CQ DX xxxxxx xxxx. Modifier range: 000-999, A-Z, AA-ZZ, AAA-ZZZ, AAAA-ZZZZ.
 2023-01-08(0.84)
  1. Optimized map color scheme.
  2. Fixed crash caused by thread synchronization issues when displaying notification messages.
  3. Fixed failed log imports due to non-standard log field descriptions.
  4. Optimized callsign hash table processing.
 2023-01-07(0.83)
  1. Added free text transmission feature.
  2. Fixed crash caused by Execute-only memory violation error.
  3. Fixed radio model configuration error for certain models.
  4. Updated log import/export operations, added confirmation field in exported logs, auto-updates confirmation items during import.
  5. Fixed multiple memory leaks.
  6. Fixed inaccurate partner grid in some log QSOs.
  7. Fixed screen timeout issue during extended use of the grid tracker screen.
 2022-12-31(0.8.1)
  Goodbye 2022! May tomorrow be better!
  Note! This version updates the database structure. Please export and backup your logs before upgrading. After upgrading, you cannot roll back to older versions.
  Note! This version adds ICOM network control. It is recommended to use the radio's WiFi connected to the phone's AP (preferred), or the phone connected to the radio's AP.
  Note! Using a router for connection is not recommended. If the router performance is insufficient, it will cause packet loss during audio transmission!
  1. Added network (WiFi) support for ICOM series radios.
  2. Added SWR and ALC high-value warning for ICOM series radios.
  3. Added SWR and ALC high-value warning for Kenwood TS series radios.
  4. Added SWR and ALC high-value warning for YAESU series radios.
  5. Added SWR and ALC high-value warning for Elecraft series radios.
  6. Added automatic Data mode connector switching for some ICOM radios under different connection methods.
  7. Added signal strength adjustment for ALC tuning.
  8. Added support for calling 3-character callsigns.
  9. Added support for additional radios.
  10. Added access to latest version releases. https://github.com/N0BOY/FT8CN/releases
  11. Added callsign-to-grid mapping table (database upgrade).
  12. Added map visualization feature (similar to GridTracker).
  13. Added ability to call from the map.
  14. Fixed crash on some devices caused by memory issues during audio data processing.
  15. Fixed inaccurate QTH in some messages.
  16. Fixed zone indicator displaying on transmission items in some cases.
  17. Fixed crash when transmitting messages with 3-character callsigns.
  18. Optimized spectrum display, fixed text display issues at low resolutions.
  19. Updated coordinates for some regions.
  20. Optimized message list processing strategy, reduced memory issues.
  21. Fixed notification message not updating when switching target callsign after reaching no-response threshold.
  22. Fixed inability to resolve geographic location for some non-standard callsigns.
  23. Fixed crash caused by memory access mechanisms on newer Android and ARM64 versions.
2022-11-08(0.79)
  1. Changed XieGu X6100 operating mode to U-DIG mode.
  2. Changed audio data format from 16-bit integer to 32-bit float mode.
  3. Fixed memory leak in FFT processing.
  4. Added Flex-6000 series network connection mode support, currently receive-only, no transmission support.
  5. Added screen sleep prevention.
  6. Controlled message history limit (temporarily set to 3000 messages).
  7. Added full-screen mode.
  8. Added quick frequency switching.
  9. Fixed crash on some radios (iCOM, XieGu) caused by poor data transmission quality.
  10. Fixed misidentification of non-standard callsigns with fewer than 6 characters.
  Known issues:
  1. Flex radio connection only works within the same subnet; direct IP input connection not yet available.
2022-11-18(0.79 Patch 4)
  1. Fixed decode button not working on some devices.
  2. Added direct IP input connection for Flex radio, resolving cross-subnet connection issues.
2022-10-06(0.78)
  1. Continued optimization of auto-caller logic, fixed target inconsistency when auto-call is enabled.
  2. Added delete confirmation dialog for log deletion.
2022-10-01(0.77)
  1. Fixed case-sensitivity issue in band statistics.
  2. Previously contacted callsigns not on the current band are now displayed in blue font.
  3. Added new radio models.
2022-09-24(0.76)
  1. Adjusted historical QSO callsign rules to distinguish by band (wavelength).
  2. Fixed transmission supervision self-decrementing error.
  3. Continued fixing inaccurate signal reports in logs.
  4. Continued optimizing auto-caller strategy.
2022-09-17(0.75)
  1. Continued fixing QSO log signal report issues (reversed reports, inaccurate values).
  2. Added Bluetooth connection permission request for Android 12.
  3. Enabled delayed command sending for certain radio models with slow USB response.
  4. Changed YAESU FT450D operating mode to USER-U mode.
  5. Continued optimizing auto-caller, adjusted auto-caller mechanism, moved automatic log recording earlier.
  6. Auto-closes PTT when exiting the app during transmission.
  7. Fixed duplicate messages with hashed callsigns caused by oversampling.
  8. Added Japanese, Greek, and Spanish UI.
  9. Fixed auto-call error for followed messages not on the same frequency band.
2022-09-09(0.74)
    1. Added English help text.
    2. Callsign query results now displayed in descending time order.
    3. Changed ICOM radio operating mode to USB-D mode.
    4. Added QRZ callsign lookup feature.
    5. Fixed imprecise signal report values in logs.
2022-09-03(0.73)
  1. Fixed inaccurate start time in some logs.
  2. Optimized unworked zone marking.
  3. Based on message history, added distance marking for messages without grid reports.
2022-08-28(0.72)
  1. Fixed auto-caller calling own callsign.
  2. Distinguished previously contacted callsigns by contact frequency.
  3. Enhanced "tracking information" content in the web interface.
  4. Re-added callsign query list to QSO records and adjusted display content.
  5. Fixed crash due to array index overflow.
  6. Reduced permission requests, removed storage permission, kept microphone and location permissions (can be declined).
  7. Fixed crash when microphone permission is not granted.
2022-08-27(0.71)
  1. Optimized PTT on-duration during transmission cycle to ensure complete receive message cycle.
  2. Fixed Q900 Bluetooth audio send/receive adaptation, fully implementing Bluetooth control and audio capability.
  3. Improved zone marking in messages.
  4. Added new radio support.
  5. Fixed message list not auto-scrolling when new messages arrive.
2022-08-22(0.7)
  1. Added DXCC zone data statistics.
  2. Added ITU zone data statistics.
  3. Added CQ zone data statistics.
  4. Added distance statistics for each band.
  5. Added marking for unworked DXCC, ITU, and CQ zone callsigns.
  6. Fixed inaccurate calculation for callsigns with 1-letter prefix and 2-digit number.
2022-08-13(0.63)
  1. Fixed non-standard callsign identification, resolving calculation errors for some non-standard callsigns.
  2. Continued optimizing layouts (especially landscape mode).
  3. Added Traditional Chinese location information.
2022-08-11(0.62)
  1. Changed FT-817/818 series working mode from USB to DIGI mode.
  2. Echo transmitted messages back to the call list.
  3. Fixed crash on some devices when manually interrupting transmission.
  4. Fixed crash when transmitting with empty callsign.
  5. Fixed control issues with certain radio models.
  6. Added English language pack.
  7. Optimized layouts.
2022-08-06(0.6)
  1. Restructured radio-related low-level architecture to support multiple radio models.
  2. Completed command sets for GUOHE, YAESU, and KENWOOD radio models.
  3. Implemented Bluetooth serial port (SPP mode) control.
  4. Implemented Bluetooth audio capture.
  5. Changed rules to prevent calling own callsign.
  6. Added support for non-standard and compound callsigns.
  7. Added submitted transmitted messages to call list when no audio is captured during transmission.
2022-07-17(0.51)
  1. Fixed incorrect band wavelength with help from BA2BI.
  2. Fixed duplicate entries in carrier band list on settings page.
  3. Fixed DTR not triggering transmission.
  4. Added saving radio frequency after frequency change; uses radio frequency if QSO succeeds.
  5. Added WSPR-2 frequency protection; transmission is blocked when radio frequency is within the WSPR-2 range.
  6. Fixed missing grid information for partner callsigns in v0.5 logs.
  7. Fixed auto-followed CQ targets not being auto-called in v0.5.
  8. Fixed inability to delete followed callsigns from web interface.
  9. Added progress bars for transmission and monitoring.
  10. Added log import/export sync with automatic LoTW confirmation.
  11. Added manual confirmation.
  12. Added radio PTT response delay setting.
  13. Added quick call by swiping left in the message list (effective within 2.5 seconds before the current cycle).
  14. Added "Today's Logs" to log export.
  15. Fixed inability to delete callsigns containing slashes.
  16. Added simple filter for QSO record queries.
2022-07-10(0.5)
  This is a major update. Completed the auto-caller and added log query and export features. This completes a fully functional QSO-capable app.
  Additional changes:
  1. Fixed waterfall display text overlap.
  2. Added radio support and baud rates.
  3. Fixed crash when location permission is not granted.
  4. Added DTR support.
  5. Fixed various minor bugs discovered along the way.
  6. Added automatic transmission supervision.
  7. Added auto-follow CQ toggle.
  8. Added auto-call followed callsigns toggle.
  9. Added marking for messages with excessive time offset.
  Known issues:
  1. If the other party starts calling from the 2nd message, the saved log will not contain their grid, even though grid information exists in the message context.
  2. When auto-follow CQ is enabled with auto-reply for followed callsigns, CQ messages may not be replied to.
  These issues will be fixed in the next version.
2022-07-02(0.44)
  1. Added feedback collection entry point.
  2. Fixed crash on settings page.
  3. Added X5105 to the device list.
2022-07-01(0.43)
  1. Fixed RTS-controlled PTT issue for some radios with help from BG7IKK.
  2. BI1NIZ registered an account for issue collection and FAQ.
  3. Added red transmission frequency marker on the spectrum ruler.
2022-06-30(0.42)
  1. BH7ACO helped resolve the XieGu X6100 driver. (Unresolved issue: X6100 sometimes disconnects unexpectedly. Workaround: delay SSB mode command by 1 second. Solution is not ideal.)
2022-06-29(0.41)
  1. Confirmed successful control testing for IC-705, IC-7100, and IC-7300.
  2. BH2RSJ helped establish an app testing group, with members providing feedback and suggestions.
  3. Modified startup sequence to ensure configuration parameters are loaded on time.
  4. Fixed radio frequency change incorrectly switching filter to FIL2.
2022-06-27
  1. Added radio CAT control, currently supporting some ICOM series radios. Only tested successfully with IC-705 due to lack of other ICOM models to verify serial port driver compatibility.
  2. Found list of ICOM radios supporting CI-V command control and their default addresses.
2022-06-20
  1. Added help feature.
  2. Added waterfall display marking feature.
  3. Made some dark mode adaptations for Android 10+.
  4. Changed app icon (designed by BG7YOY).



Acknowledgments:
   Steve Franke (K9AN), Bill Somerville (G4WJS), and Joe Taylor (K1JT) developed the FT8 and FT4 protocols (FT stands for the initials of Franke and Taylor). Their paper "The FT4 and FT8 Communication Protocols" provided detailed information on the design principles and implementation details in WSJT-X, serving as the fundamental guide for this app.
   Karlis Goba (YL3JG) provided reference implementations for the code.
Credits:
   BG7YOY - Provided guidance on basic radio theory during FT8CN development and designed the app icon.
   BG4IGX - Provided practical guidance when I was getting started in amateur radio. You can find many of his tutorial videos on social media.
   BD7MXN - Helped test radio connection and control for some models and provided improvement suggestions.
   BH2RSJ - Helped establish an FT8CN testing group, providing valuable feedback and improvement suggestions.
   BH7ACO - Helped resolve radio drivers and related configuration parameters.
   BG7IKK - Helped test radios that only support PTT transmission via RTS control.
   BI1NIZ - Helped register an account for issue collection and FAQ features.
   BD3OOX and the Shijiazhuang Amateur Radio Club - FT8CN's callsign regional data was extracted from the JTDX Shijiazhuang edition, enabling callsign location down to the provincial level in China.
   VR2UPU (BD7MJO) - Provided guidance on FT8 development and usage experience, and helped with multilingual support.
   BA2BI - Provided help and guidance on amateur radio fundamentals and QSO log processing.
   BI3QXJ - Provided professional guidance on the command set for certain radio series.
   BG6TQD - Helped test command sets for certain radio models.
   BG5CSS - Provided a radio for testing.
   BG7YXN - Provided a radio for testing.
   BG7YRB - Provided help with callsign rule calculations.
   BG8KAH - Provided equipment for testing.
   BA7LVG - Completed Japanese translation proofreading.
   JE6WUD - Completed Japanese translation proofreading.
   BG6RI - Helped resolve log signal report issues.
   SV1EEX - Completed Greek and Spanish UI translation.
   VR2VRC - Helped fix historical callsign reading rules.
   BA7NQ - Provided equipment for testing.
   BD7MYM - Provided guidance on testing certain radio models.
   N0BOY - Helped provide GitHub hosting and translation work.
   BG5JNT - Helped fix non-standard callsign identification issues.
   BH3NEK - Assisted with testing certain radio models.
   BG2ALB - Assisted with testing certain radio models.
   BG6DRU - Assisted with testing certain radio models.
   BG7NQF - Provided hidden commands for certain radio models and conducted compatibility testing.
   BH2VSQ - Assisted with testing certain radio models.
   BG7YBW - Assisted with testing various features.
   BH1RNN - Assisted with testing various features.
   BG7BSM - Assisted with debugging some bugs.
   BH4FTI - Discovered and assisted with debugging some bugs.
   BG8BXM - Promoted FT8CN usage with tutorial videos on social media.
   BG7MFQ - Promoted FT8CN usage and helped with testing.
   BG2EFX - Provided large-volume log data for testing.
   DS1UFX - Contributed (tr)uSDX audio over CAT code.
   BG8HT - Provided a radio for testing.
   UB6LUM - Helped resolve operating mode settings for certain radio models.
   SydneyOwl - Contributed code for uploading logs to QRZ and Cloudlog.

