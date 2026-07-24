package com.k1af.ft8af;
/**
 * Common variables. There is a memory leak risk with mainContext; to be addressed later.
 * mainContext
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.k1af.ft8af.callsign.CallsignDatabase;
import com.k1af.ft8af.callsign.CallsignInfo;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.database.DatabaseOpr;
import com.k1af.ft8af.ft8transmit.QslRecordList;
import com.k1af.ft8af.html.HtmlContext;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.timer.UtcTimer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;


public class GeneralVariables {
    private static final String TAG = "GeneralVariables";
    public static String VERSION = BuildConfig.VERSION_NAME;//Version number "0.62 (Beta 4)";
    public static int VERSION_CODE = BuildConfig.VERSION_CODE;//Monotonic build number (CI: GITHUB_RUN_NUMBER + 100)
    public static String BUILD_DATE = BuildConfig.apkBuildTime;//Build time
    public static int MESSAGE_COUNT = 3000;//Maximum message cache count
    public static boolean saveSWLMessage = false;//Save decoded messages switch
    public static boolean saveSWL_QSO = false;//Save QSOs from decoded messages switch
    public static boolean enableCloudlog = false;//Whether Cloudlog auto-sync is enabled
    public static boolean enableQRZ = false;//Whether QRZ auto-sync is enabled
    public static boolean enablePskReporter = true;//Whether PSKReporter spot upload is enabled
    public static boolean enableAdifExport = true;//Append each logged QSO to a running ft8af_log.adi (real-time ADIF mirror for backup + desktop-logger import)
    // WSJT-X UDP interface (issue: interop with GridTracker/JTAlert/N1MM/Log4OM).
    // Off by default; persisted as config keys udp_enabled/udp_host/udp_port/udp_accept_requests.
    public static boolean udpEnabled = false;//Whether the WSJT-X UDP broadcast is enabled
    public static String udpHost = "127.0.0.1";//WSJT-X UDP server address (unicast/broadcast/multicast)
    public static int udpPort = 2237;//WSJT-X UDP server port (WSJT-X default 2237)
    public static boolean udpAcceptRequests = false;//Act on inbound Reply/Halt/Free-Text/Replay requests
    // Dark feature flag for issue #437 (auto per-location Wavelog station profiles).
    // Default false and NOT branched into any live upload path yet — this increment
    // only lands the pure LocationSignature/resolver/create_station/cache foundation.
    public static boolean perLocationStationEnabled = false;

    public static boolean distanceInMiles = true;//Display distances in miles (true) or kilometers (false)

    // Show the great-circle beam heading (bearing to the station) next to the
    // distance on each decode row, for operators pointing a directional antenna.
    // Off by default — only useful with a beam, and it adds a column most ops
    // (verticals/wires) don't need.
    public static boolean showBeamHeading = false;

    // Deep decode (subtract-and-redecode + extra LDPC iterations) is on by default so the app
    // pulls weak signals out from under strong ones the way WSJT-X does at its default depth.
    // A persisted "deepMode" config row still overrides this; only installs that never touched
    // the setting pick up the new default. See ModeProfile#deepDecodeBudgetMillis for the loop
    // time bound.
    public static boolean deepDecodeMode = true;//Whether deep decode mode is enabled

    // Hold the screen awake while the app is in the foreground. On by default —
    // that was the hard-coded behaviour before this became a setting — but a long
    // portable session is the case where you want it off: an always-on panel at
    // outdoor brightness is one of the two biggest heat sources on the phone, and
    // a hot phone browns out its own OTG accessory rail (see the 2026-07-23 field
    // log: 48.6C battery, twelve USB re-enumerations). RX keeps running with the
    // screen off via RxForegroundService, so turning this off costs nothing but
    // having to wake the phone to look at the waterfall.
    public static boolean keepScreenOn = true;

    public static boolean audioOutput32Bit = true;//Audio output type: true=float, false=int16
    public static int audioSampleRate = 12000;//Transmit audio sample rate

    public static int audioInputDeviceId = 0;//Audio input device ID, 0=system default, -1=USB audio
    public static int audioOutputDeviceId = 0;//Audio output device ID, 0=system default, -1=USB audio

    // USB audio device VID/PID, used to re-find the device after restart
    public static int usbAudioInputVendorId = 0;
    public static int usbAudioInputProductId = 0;
    public static int usbAudioOutputVendorId = 0;
    public static int usbAudioOutputProductId = 0;

    /**
     * Whether the given USB VID:PID is the device the user picked for direct
     * USB audio <em>input</em> (audioInputDeviceId == -1 means "USB direct").
     * Used on a USB-attach event to decide whether a freshly-plugged device
     * needs an audio permission request so the recorder can rebind to it
     * instead of staying on the built-in mic.
     */
    public static boolean isConfiguredUsbAudioInput(int vid, int pid) {
        return audioInputDeviceId == -1 && usbAudioInputVendorId != 0
                && usbAudioInputVendorId == vid && usbAudioInputProductId == pid;
    }

    /**
     * Whether the given USB VID:PID is the device the user picked for direct
     * USB audio <em>output</em> (audioOutputDeviceId == -1 means "USB direct").
     */
    public static boolean isConfiguredUsbAudioOutput(int vid, int pid) {
        return audioOutputDeviceId == -1 && usbAudioOutputVendorId != 0
                && usbAudioOutputVendorId == vid && usbAudioOutputProductId == pid;
    }

    public static MutableLiveData<Float> mutableVolumePercent = new MutableLiveData<>();
    public static float volumePercent = 0.8f;//Audio playback volume, as a percentage

    // RX input gain (issue #356): multiplier applied to incoming audio samples
    // before resampling/decoding. 1.0 = 100% = unchanged behavior. Persisted
    // under the "inputVolume" config key as a percent (0..200).
    public static volatile float inputGainPercent = 1.0f;
    // Live RX input level (peak + short-term RMS of post-gain samples),
    // published by HamRecorder once per metering window for the UI meter.
    public static final MutableLiveData<com.k1af.ft8af.wave.InputAudioLevel.Levels>
            mutableInputLevel = new MutableLiveData<>();

    public static boolean showTxVolumeSlider = true;//Show inline TX volume slider on main screen
    public static MutableLiveData<Boolean> mutableShowTxVolumeSlider = new MutableLiveData<>(true);

    //Save TX output level per band (issue #355), defaults off (global level only).
    // volatile: written from DatabaseOpr's background config-load thread and the
    // Settings toggle, read from UI + MeterProtectionController threads (same
    // convention as zoneMapReady/huntPotaOnly/perBandOutputLevels below).
    public static volatile boolean savePerBandOutputLevel = false;
    //Serialized band=level CSV ("20m=60,40m=85"); parsed/updated in PerBandOutputLevel.kt.
    public static volatile String perBandOutputLevels = "";

    //Auto-select a clear TX offset when calling CQ (issue #418), defaults off.
    //volatile: written from the config-load thread + Settings toggle, read from
    //the decode-delivery thread inside recordBandActivity.
    public static volatile boolean autoClearTxFreq = false;

    //Tune button (issue #408): hard cap on a single tune carrier in seconds
    //(clamped by TuneController), whether the tune level is independent of the
    //FT8 drive, the global independent level (0..100), and the per-band
    //independent levels (same CSV format as perBandOutputLevels; gated on the
    //same savePerBandOutputLevel toggle, separate backing store — see
    //TuneLevel.kt). volatile: written from the config-load thread + Settings,
    //read from the tune audio worker per chunk.
    public static volatile int tuneMaxOnSeconds = 10;
    public static volatile boolean tuneLevelIndependent = false;
    public static volatile int tuneLevel = 25;
    public static volatile String perBandTuneLevels = "";
    //Tune method (issue #425): TuneMethod.AUTOMATIC/INTERNAL/TONE — whether the
    //TUNE chip starts the rig's internal ATU over CAT or plays the carrier tone.
    public static volatile int tuneMethod = 0;

    public static int flexMaxRfPower = 10;//Flex radio max transmit power
    public static int flexMaxTunePower = 10;//Flex radio max tune power

    // Hidden debug mode (unlocked by tapping the version 7 times in About).
    // When true, Settings exposes the Debug screen for log viewing/sharing.
    public static boolean debugModeEnabled = false;

    private Context mainContext;

    /**
     * Append a timestamped line to the app's external-files-dir debug.log.
     * Safe to call from any thread; failures are swallowed so logging can never
     * crash the caller. This is the structured app-event log surfaced by the
     * in-app Debug screen and `adb pull` workflows.
     */
    public static void fileLog(String msg) {
        try {
            Context ctx = getMainContext();
            if (ctx == null) return;
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            String ts = new java.text.SimpleDateFormat(
                    "HH:mm:ss.SSS", java.util.Locale.US).format(new java.util.Date());
            try (FileOutputStream fos = new FileOutputStream(new File(dir, "debug.log"), true)) {
                fos.write((ts + " " + msg + "\n").getBytes());
            }
        } catch (Exception ignored) {
        }
        Log.d(TAG, msg);
    }
    public static CallsignDatabase callsignDatabase = null;

    public void setMainContext(Context context) {
        mainContext = context;
    }

    public static boolean isChina = false;//Whether the language is Chinese
    public static boolean isTraditionalChinese = false;//Whether the language is Traditional Chinese
    //public static double maxDist = 0;//Maximum distance

    //Lists of already-contacted zones
    public static final Map<String, String> dxccMap = new ConcurrentHashMap<>();
    public static final Map<Integer, Integer> cqMap = new ConcurrentHashMap<>();
    public static final Map<Integer, Integer> ituMap = new ConcurrentHashMap<>();
    // Set to true after getQslDxccToMap() finishes populating the zone maps.
    // Until then, "new DXCC/zone" flags are suppressed to avoid a race where
    // everything shows as new because the maps haven't loaded yet.
    public static volatile boolean zoneMapReady = false;
    // Already-contacted US states (USPS code, e.g. "ND"). Populated at startup from the
    // logbook by DatabaseOpr.getQslDxccToMap(), deriving each QSO's state from its grid.
    public static final java.util.Set<String> workedStates =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    // 4-char Maidenhead field -> US state code, lazily loaded from the bundled
    // assets/us_grid_states.json (the same map the Compose UI reads via UsStateLookup).
    private static volatile Map<String, String> gridStateMap = null;

    // Callsign blocklist (Settings → Callsign Blocklist). Three independent match
    // modes, generalizing the original prefix-only "excluded callsigns" feature. A
    // blocked station is hidden from the decode list AND excluded from TX/auto-seq.
    //   - excludedCallsigns: callsign PREFIX match (e.g. "RA" blocks RA0..RA9..).
    //     Reuses the legacy "excludedCallsigns" config key for backward compat.
    //   - blockedExactCallsigns: whole-call EXACT match.
    //   - blockedKeywords: SUBSTRING match (against the call, and the full message
    //     text via checkIsBlockedMessage) — catches POTA, /P, QRP, contest junk.
    private static final Map<String, Integer> excludedCallsigns = new HashMap<>();
    private static final java.util.LinkedHashSet<String> blockedExactCallsigns = new java.util.LinkedHashSet<>();
    private static final java.util.LinkedHashSet<String> blockedKeywords = new java.util.LinkedHashSet<>();

    // Callsign watchlist (Settings → Needed-DX Alerts → Watchlist). When non-empty,
    // a decoded message from a matching station fires a high-priority alert (sound +
    // vibrate + notification) via DxAlertNotifier — the "tell me the instant this
    // station is on the air" hunt tool for a rare DXpedition, a needed prefix, or a
    // friend. Entries match by callsign PREFIX (so "3Y0" catches 3Y0J and 3Y0J/MM,
    // and a full call like "W1AW" also matches "W1AW/P"), mirroring the excluded-
    // callsign prefix semantics. Unlike the needed-DX alerts it is not CQ-gated.
    private static final java.util.LinkedHashSet<String> watchCallsigns = new java.util.LinkedHashSet<>();

    /**
     * Split a user-entered list on comma / space / pipe / Chinese comma and
     * collect the non-empty, upper-cased tokens into {@code target}.
     */
    private static void parseBlockTokens(String text, java.util.Collection<String> target) {
        target.clear();
        if (text == null) return;
        String[] s = text.toUpperCase().replace(" ", ",")
                .replace("|", ",")
                .replace("，", ",").split(",");
        for (String token : s) {
            if (token.length() > 0) {
                target.add(token);
            }
        }
    }

    /**
     * Join a token collection back into the canonical comma-separated form used
     * for persistence and for display in the Settings text field.
     */
    private static String joinBlockTokens(java.util.Collection<String> tokens) {
        StringBuilder calls = new StringBuilder();
        int i = 0;
        for (String token : tokens) {
            if (i++ == 0) {
                calls.append(token);
            } else {
                calls.append(",").append(token);
            }
        }
        return calls.toString();
    }

    /**
     * Add excluded callsign prefixes (legacy name kept; this is the PREFIX list).
     *
     * @param callsigns callsigns
     */
    public static synchronized void addExcludedCallsigns(String callsigns) {
        excludedCallsigns.clear();
        if (callsigns == null) return;
        String[] s = callsigns.toUpperCase().replace(" ", ",")
                .replace("|", ",")
                .replace("，", ",").split(",");
        for (String token : s) {
            if (token.length() > 0) {
                excludedCallsigns.put(token, 0);
            }
        }
    }

    public static synchronized void addBlockedExactCallsigns(String callsigns) {
        parseBlockTokens(callsigns, blockedExactCallsigns);
    }

    public static synchronized void addBlockedKeywords(String keywords) {
        parseBlockTokens(keywords, blockedKeywords);
    }

    /**
     * Get the list of excluded callsign prefixes (the PREFIX list).
     *
     * @return the list as a comma-separated string
     */
    public static synchronized String getExcludeCallsigns() {
        return joinBlockTokens(excludedCallsigns.keySet());
    }

    public static synchronized String getBlockedExactCallsigns() {
        return joinBlockTokens(blockedExactCallsigns);
    }

    public static synchronized String getBlockedKeywords() {
        return joinBlockTokens(blockedKeywords);
    }

    /** Replace the callsign watchlist from a user-entered comma/space/pipe list. */
    public static synchronized void addWatchCallsigns(String callsigns) {
        parseBlockTokens(callsigns, watchCallsigns);
    }

    /** The watchlist in canonical comma-separated form (for persistence + display). */
    public static synchronized String getWatchCallsigns() {
        return joinBlockTokens(watchCallsigns);
    }

    /** Whether the user has any watchlist entries (gates the watchlist alert). */
    public static synchronized boolean hasWatchCallsigns() {
        return !watchCallsigns.isEmpty();
    }

    /**
     * Whether {@code callsign} matches the watchlist by PREFIX (case-insensitive):
     * "3Y0" matches 3Y0J / 3Y0J/MM, "W1AW" matches W1AW / W1AW/P. Anchored at the
     * start, so "W1AW" does not match "KW1AW". Empty list matches nothing.
     */
    public static synchronized boolean checkIsWatchedCallsign(String callsign) {
        if (callsign == null || watchCallsigns.isEmpty()) return false;
        String up = callsign.toUpperCase();
        for (String prefix : watchCallsigns) {
            if (up.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check whether a callsign is blocked by any match mode: exact whole-call,
     * prefix, or keyword substring (against the callsign itself).
     *
     * @param callsign callsign
     * @return whether it is blocked
     */
    public static synchronized boolean checkIsBlocked(String callsign) {
        if (callsign == null) return false;
        String up = callsign.toUpperCase();
        if (blockedExactCallsigns.contains(up)) {
            return true;
        }
        for (String prefix : excludedCallsigns.keySet()) {
            if (up.indexOf(prefix) == 0) {
                return true;
            }
        }
        for (String keyword : blockedKeywords) {
            if (up.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Decode-list block check: blocked if the sender's callsign is blocked, or any
     * keyword appears anywhere in the rendered message text (so keywords can match
     * message content like "POTA", not just the callsign).
     *
     * @param msg the decoded message
     * @return whether it is blocked
     */
    public static synchronized boolean checkIsBlockedMessage(Ft8Message msg) {
        if (msg == null) return false;
        if (checkIsBlocked(msg.callsignFrom)) {
            return true;
        }
        if (!blockedKeywords.isEmpty()) {
            String text = msg.getMessageText();
            if (text != null) {
                String up = text.toUpperCase();
                for (String keyword : blockedKeywords) {
                    if (up.contains(keyword)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Backward-compatible alias. Existing TX-side call sites call this; it now
     * covers all three blocklist match modes via {@link #checkIsBlocked}.
     *
     * @param callsign callsign
     * @return whether it matches
     */
    public static synchronized boolean checkIsExcludeCallsign(String callsign) {
        return checkIsBlocked(callsign);
    }


    //QSO record list, including both successful and unsuccessful
    public static QslRecordList qslRecordList = new QslRecordList();

    //Memory leak warning here, but Application Context should not leak, so suppressed
    @SuppressLint("StaticFieldLeak")
    private static GeneralVariables generalVariables = null;

    public static synchronized GeneralVariables getInstance() {
        if (generalVariables == null) {
            generalVariables = new GeneralVariables();
        }
        return generalVariables;
    }

    public static Context getMainContext() {
        return GeneralVariables.getInstance().mainContext;
    }


    public static MutableLiveData<String> mutableDebugMessage = new MutableLiveData<>();
    public static int QUERY_FREQ_TIMEOUT = 2000;//Frequency polling interval, 2 seconds
    public static int START_QUERY_FREQ_DELAY = 2000;//Delay before starting frequency polling

    public static final int DEFAULT_LAUNCH_SUPERVISION = 10 * 60 * 1000;//Transmit supervision default, 10 minutes
    private static String myMaidenheadGrid = "";
    public static MutableLiveData<String> mutableMyMaidenheadGrid = new MutableLiveData<>();

    public static int connectMode = ConnectMode.USB_CABLE;//Connection mode: USB==0, BLUE_TOOTH==1

    public static String bluetoothDeviceAddress = null;//last-selected Bluetooth (SPP/CAT) device address, persisted for auto-reconnect


    //Records callsign-to-grid mapping. todo---should also add this list to background tracking info
    //public static ArrayList<CallsignMaidenheadGrid> callsignMaidenheadGrids=new ArrayList<>();
    public static final Map<String, String> callsignAndGrids = new ConcurrentHashMap<>();
    //private static final Map<String,String> callsignAndGrids=new HashMap<>();

    public static String myCallsign = "";//My callsign
    public static String myAntenna = "";
    public static String myRigName = "";  // Set by MainViewModel.connectRig(); used in PSKReporter software string
    public static int myPowerWatts = 0;    // 0 = not set, displays as "--"
    public static String toModifier = "";//Call modifier
    public static String cqFreeText = "";//Persisted custom/directed CQ free text

    // Field Day mode settings (persisted via writeConfig)
    public static boolean fieldDayMode = false;
    public static String fieldDayClass = "A";     // A through F
    public static int fieldDayNumTx = 1;          // 1-16
    public static String fieldDaySection = "";    // ARRL/RAC section code
    private static float baseFrequency = 1000;//Audio frequency

    public static boolean simpleCallItemMode = false;//Compact message mode

    public static boolean clearDecodesEveryCycle = false;//Clear the decode list at the start of each cycle

    public static int decodeSortMode = 0;//Collapsed decode list sort: 0=last heard,1=callsign,2=SNR (DecodeSortMode.configValue)

    public static boolean clearOnBandModeChange = true;//Clear the decode list + reset TX target to CQ when the band or mode changes (default on)

    public static boolean swr_switch_on = true;//SWR alarm switch
    public static boolean alc_switch_on = true;//ALC alarm switch

    // TX Protection: ALC auto-volume and SWR TX halt (MeterProtectionController)
    public static boolean autoVolumeEnabled = false;  // ALC auto-volume control
    public static boolean swrHaltEnabled = false;      // SWR TX halt + lockout
    public static int swrHaltThreshold = 120;          // 0-255 normalized (~3.0:1)
    public static int alcTargetLow = 60;               // ALC target window low (0-255)
    public static int alcTargetHigh = 100;             // ALC target window high (0-255)

    public static MutableLiveData<Float> mutableBaseFrequency = new MutableLiveData<>();

    private static int spectrumWidth = 3500;//Spectrum display width in Hz
    public static MutableLiveData<Integer> mutableSpectrumWidth = new MutableLiveData<>();

    // FFT display developer knobs (issue #428). Wire values shared with the
    // native side (SpectrumFragment.setFFTDisplayParams) and the config DB;
    // read fresh every display frame, so no LiveData needed (same pattern as
    // pttDelay). Display-only — the decoder's FFT is unaffected.
    private static int fftWindowType = 1;//0=Rect 1=Hann(default, matches desktop/iOS) 2=Hamming 3=Blackman 4=Blackman-Harris
    private static int fftAveragingMode = 0;//0=Off(default) 1=EMA a=0.5 (light) 2=EMA a=0.25 (heavy)
    private static int spectrumBinAggregation = 0;//Spectrum-strip bin combine: 0=Max(default, legacy) 1=Average 2=RMS

    public static String cloudlogServerAddress = "";//Cloudlog server address
    public static String cloudlogApiKey = "";//Cloudlog API key
    public static String cloudlogStationID = "";//Cloudlog station ID
    public static String qrzApiKey = ""; //QRZ API key
    public static String qrzXmlUsername = ""; //QRZ XML API username (for callsign lookups)
    public static String qrzXmlPassword = ""; //QRZ XML API password
    public static boolean pskOverlayEnabled = false; //PSK Reporter map overlay (issue #33)
    public static boolean grayLineEnabled = true; //Day/night terminator (gray line) map overlay — on by default
    public static boolean synFrequency = false;//Same-frequency transmit
    // Hold TX freq: don't move the TX offset to a station you answer (WSJT-X
    // "Hold Tx Freq"). Enabled by default (issue #498) to keep the TX frequency
    // stable; the config DB only overrides this once the user explicitly toggles it.
    public static boolean holdTxFreq = true;
    public static int transmitDelay = 500;//Transmit delay; also allows decoding time for the previous cycle
    public static int pttDelay = 100;//PTT response time; radios typically need some response time after PTT command, default 100ms
    public static int lateStartTolerance = 2000;//Max ms of leading audio a late manual TX may clip past the per-mode audio slack (ModeProfile.audioSlackMillis) and still go out this cycle. Effective start budget is slack+tolerance. 0-4000. See issue #467.
    public static int manualTimeCorrectionMs = 0;//Manual clock correction (ms) applied to UtcTimer.delay; for field use without internet NTP. Range [MANUAL_TIME_CORRECTION_MIN_MS, MANUAL_TIME_CORRECTION_MAX_MS]. See TimeSyncSettings.
    public static boolean earlyDecode = true;//Fast turnaround: decode a shorter RX window so CQ decodes appear ~1s before the cycle boundary, enabling a next-slot reply.
    public static int operatingMode = FT8Common.FT8_MODE;//Current operating mode (FT8Common.FT8_MODE / FT4_MODE); persisted as config "operatingMode".
    public static int iaruRegion = 2;//Operator's IARU region (1/2/3), used to gate Message-Creator QSY frequency options to legal band edges. Default 2 (the Americas). See com.k1af.ft8af.message.SpecialMessage.
    public static boolean autoCQAfterQSO = false;//Auto-CQ: keep calling CQ after each completed QSO (chain without re-tapping). Refreshes the TX watchdog per QSO and forces pure CQ (ignores Hunt).
    public static int civAddress = 0xa4;//CI-V address
    public static int baudRate = 19200;//Baud rate
    public static long band = 14074000;//Carrier frequency band
    public static int serialDataBits = 8;//Default is 8
    public static int serialParity = 0;//UsbSerialPort.PARITY_NONE, default is 0 (none)
    public static int serialStopBits = 1;//Stop bits mapping: 1=1, 2=3, 3=1.5
    public static int instructionSet = 0;//Instruction set: 0=ICOM, 1=Yaesu gen 2, 2=Yaesu gen 3
    public static int bandListIndex = -1;//Radio band index value
    public static MutableLiveData<Integer> mutableBandChange = new MutableLiveData<>();//Band index change
    //Band names (e.g. "6m","60m") the user has hidden from the band pickers. Empty = show all.
    //Persisted in config as a comma-separated list under the key "excludedBands".
    public static volatile java.util.HashSet<String> excludedBands = new java.util.HashSet<>();

    public static boolean isBandExcluded(String waveLength) {
        return excludedBands.contains(waveLength);
    }

    public static String excludedBandsToCsv() {
        return android.text.TextUtils.join(",", excludedBands);
    }
    public static int controlMode = ControlMode.VOX;
    //Control-mode change signal so Compose can react when the user switches
    //VOX <-> CAT/RTS/DTR (e.g. to show/hide the CAT status chip). No initial
    //value: observers seed from the current controlMode until a change posts.
    public static MutableLiveData<Integer> mutableControlMode = new MutableLiveData<>();
    public static int modelNo = 0;
    public static int launchSupervision = DEFAULT_LAUNCH_SUPERVISION;//Transmit supervision
    public static long launchSupervisionStart = UtcTimer.getSystemTime();//Auto-transmit start time
    public static int noReplyLimit = 0;//No-reply count limit; 0==ignore

    public static int noReplyCount = 0;//Number of times with no reply

    // Hard cap on RR73/73 transmissions per QSO before moving on; 0==Auto
    // (classic behavior: RR73 repeats until the no-reply caps fire, 73 re-sends
    // for every RR73 received). Unlike the no-reply caps this counts actual
    // sends, so it also bounds the loops where the partner keeps transmitting
    // (re-sent R+report / RR73) and the no-reply counter never accumulates.
    public static int max73Sends = 0;

    //The following 4 parameters are for ICOM network connection
    public static String icomIp = "255.255.255.255";
    public static int icomUdpPort = 50001;
    public static String icomUserName = "ic705";
    public static String icomPassword = "";


    public static boolean autoFollowCQ = false;//Auto-follow CQ
    public static boolean huntCallsCQ = false;//Hunt+CQ hybrid: call CQ when idle, answer CQs when heard
    // volatile: written from the Compose UI thread (DecodeScreen) and read from the
    // transmit/decode processing thread (FT8TransmitSignal), like zoneMapReady above.
    public static volatile boolean huntPotaOnly = false;//Mirror of the "CQ POTA" decode filter: Hunt only calls POTA CQs (issue #333)
    public static boolean autoCallFollow = true;//Auto-call followed callsigns
    // volatile: written from the Compose settings UI thread and read from the
    // transmit thread (FT8TransmitSignal.dequeueNextCaller), like huntPotaOnly above.
    public static volatile boolean pileupStrongestFirst = false;//Pileup: auto-work the strongest waiting caller next instead of the oldest (FIFO)
    public static boolean autoUpdateGridFromGPS = false;//Use device GPS to keep Maidenhead grid current
    public static boolean disciplineClockFromGPS = false;//Discipline the app clock (UtcTimer.delay) from GPS satellite time (issue #373). Off by default — consensual.
    public static int gpsClockIntervalMinutes = 5;//How often to re-read GPS time for clock discipline. Clamped 1-30 by GpsClockUpdater.
    //Runtime status for the Time Sync UI (not persisted): the offset the last GPS fix applied
    //to UtcTimer.delay. The last-sync *timestamp* is the retained value of mutableGpsClockSync
    //below, so there's no separate field for it.
    public static volatile int gpsClockOffsetMs = 0;
    //Posted each time a GPS fix disciplines the clock, so the Time Sync screen can recompose
    //its "last sync"/offset readout. Carries the sync's System.currentTimeMillis() timestamp.
    public static MutableLiveData<Long> mutableGpsClockSync = new MutableLiveData<>();
    // Successfully QSL'd callsigns (current band). Rebuilt wholesale on the DB thread
    // (DatabaseOpr.GetAllQSLCallsign), appended to on the TX thread (addQSLCallsign), and
    // read concurrently from decode/UI threads and the NanoHTTPD web-logbook worker. It is
    // therefore a CopyOnWriteArrayList behind a volatile reference: the volatile makes the
    // wholesale ref-swap publish safely, and copy-on-write keeps every concurrent reader on a
    // stable snapshot so an in-place add() can't tear an iteration. Always assign a
    // CopyOnWriteArrayList in production; see LogHttpServer.successfulCallsignBlock.
    public static volatile List<String> QSL_Callsign_list = new CopyOnWriteArrayList<>();
    public static ArrayList<String> QSL_Callsign_list_other_band = new ArrayList<>();//Successfully QSL'd callsigns on other bands
    public static HashSet<String> QSL_Callsign_list_today = new HashSet<>();//Callsigns worked today or yesterday (any band); a set for O(1) membership checks
    public static HashSet<String> QSL_Grid_list = new HashSet<>();//Distinct worked 4-char Maidenhead grids (any band)
    public static HashSet<String> QSL_Pota_list = new HashSet<>();//Distinct hunted POTA park refs (UPPER), any band
    public static HashSet<String> QSL_Prefix_list = new HashSet<>();//Distinct worked CQ WPX prefixes (any band), see WpxPrefix

    // Decode-list highlight toggles (Settings → Decode Highlights). Gate the
    // status pill shown for each worked-before category in resolveQsoStatus().
    public static boolean highlightNewDxcc = true;//Highlight stations from an unworked DXCC entity
    public static boolean highlightNewZone = true;//Highlight stations from an unworked CQ zone (Worked All Zones)
    public static boolean highlightNewState = false;//Off by default — US-only (Worked All States); noise for non-US ops
    public static boolean highlightNewGrid = false;//Off by default — most grids are "new", so it's noisy
    public static boolean highlightNewPrefix = false;//Off by default — many prefixes are "new" early on (Worked All Prefixes / WPX)
    public static boolean highlightNewBand = true;//Highlight stations worked only on other bands
    public static boolean highlightWorked = true;//Master enable for worked-station handling (see workedStationMode)
    public static boolean highlightPota = true;//Highlight spotted POTA activators (new parks stand out)

    // Worked-station handling — modelled on WSJT-X/JTDX "worked before" highlighting,
    // which lets you "hide, ignore, or highlight stations worked before on the current
    // band, worked today, or those present in a list". When highlightWorked is enabled,
    // workedStationMode selects what to do with a station that counts as "worked" under
    // workedStationScope; when highlightWorked is off, no worked handling is applied.
    //   mode  0=HIGHLIGHT (tag with the WORKED pill — the legacy behavior),
    //         1=IGNORE    (leave visible but don't highlight),
    //         2=HIDE      (drop from the decode list, unless the station is calling me).
    //   scope 0=ON_BAND   (worked this band — the legacy basis),
    //         1=BEFORE    (worked ever, any band),
    //         2=TODAY     (worked today or yesterday, any band),
    //         3=FROM_LIST (present in the user-maintained worked-station list).
    public static int workedStationMode = 0;   // HIGHLIGHT — preserves legacy behavior
    public static int workedStationScope = 0;   // ON_BAND — preserves legacy behavior
    // Orthogonal "same mode only" refinement (config "workedSameMode", default off):
    // when on, the worked lists loaded by DatabaseOpr.GetAllQSLCallsign only count
    // QSOs made on the current operating mode, giving the "…on this band and mode"
    // variants of the scopes above. FROM_LIST is user-maintained and unaffected.
    public static boolean workedSameMode = false;
    // User-maintained "worked" callsign list backing the FROM_LIST scope. Upper-cased
    // whole-call tokens, same parse/join convention as the callsign blocklist.
    private static final java.util.LinkedHashSet<String> workedStationList = new java.util.LinkedHashSet<>();

    // Decode-list display filters (Settings → Decode Filters). Persistent
    // "show only" filters applied to the decode list in DecodeScreen.filterMessages().
    // Multiple enabled filters AND together.
    public static boolean filterShowOnlyCQ = false;//Show only CQ-type messages
    public static boolean filterDxOnly = false;//Show only stations outside my own continent
    public static boolean filterNeededOnly = false;//Show only not-yet-QSL'd stations
    public static boolean filterByContinent = false;//Show only stations from filterContinent
    public static String filterContinent = "EU";//Target continent for filterByContinent (NA/SA/EU/AF/AS/OC/AN)

    // The operator's own continent abbreviation, derived once from the callsign
    // (CallsignDatabase.getContinent). Used by the DX filter. Null until resolved.
    public static String myContinent = null;

    // The operator's own DXCC, derived once from the callsign alongside myContinent
    // (CallsignDatabase.getMessagesLocation). Used by the directional-CQ matcher to
    // match country/region tokens (e.g. "CQ JA"). Null until resolved → fail-open.
    public static String myDxcc = null;

    // Directional CQ awareness (Settings → Decode Filters). Both opt-in, default off.
    //   - respectDirectionalCQ: suppress AUTO-replies to directional CQs (CQ DX/EU/JA…)
    //     not aimed at my station. Does not affect manual taps or stations calling me.
    //   - filterDirectionalCQ: hide those same CQs from the decode list.
    public static boolean respectDirectionalCQ = false;
    public static boolean filterDirectionalCQ = false;

    // Needed-DX alerts (Settings → Needed-DX Alerts). Opt-in, default off. When enabled,
    // a station calling CQ that is a NEW unworked entity/state triggers a sound + vibrate
    // notification (DxAlertNotifier). Categories are independent.
    //   - alertNewDxcc:  alert on a new (unworked) DXCC entity   (uses Ft8Message.fromDxcc)
    //   - alertNewState: alert on a new (unworked) US state       (uses Ft8Message.fromNewState)
    public static boolean alertNewDxcc = false;
    public static boolean alertNewState = false;

    // QSO & CQ alerts (Settings → Needed-DX Alerts). Opt-in, default off.
    //   - alertOnCqReply:     notify when any decoded message is addressed to my callsign
    //                         (someone calling me). Own-TX echoes are already filtered out.
    //   - alertOnQsoComplete: notify when a QSO is logged (DxAlertNotifier.notifyQsoComplete).
    public static boolean alertOnCqReply = false;
    public static boolean alertOnQsoComplete = false;

    // Geographic continent-directed CQ tokens — matched against myContinent.
    private static final java.util.Set<String> CONTINENT_CODES =
            new java.util.HashSet<>(java.util.Arrays.asList("NA", "SA", "EU", "AF", "AS", "OC", "AN"));
    // Non-geographic activity calls — always answerable. Easily extended.
    private static final java.util.Set<String> ACTIVITY_TOKENS =
            new java.util.HashSet<>(java.util.Arrays.asList(
                    "DX", "POTA", "SOTA", "WWFF", "IOTA", "TEST", "FD", "QRP", "WW"));

    /**
     * The directional token after CQ/DE/QRZ in a decoded callsignTo (e.g. "DX" from
     * "CQ DX"), or null for a plain CQ / non-CQ message.
     */
    public static String getDirectionalCQToken(String callsignTo) {
        if (callsignTo == null) return null;
        String[] p = callsignTo.trim().toUpperCase().split("\\s+");
        if (p.length < 2) return null;
        if (!(p[0].equals("CQ") || p[0].equals("DE") || p[0].equals("QRZ"))) return null;
        return p[1];
    }

    /**
     * Whether a (possibly directional) CQ is answerable by my station. Continent-code
     * tokens are matched against {@link #myContinent}; country/region prefixes against
     * {@link #myDxcc} via the callsign database. Fail-open: plain CQ, "CQ DX",
     * 3-digit zone CQs, known activity calls, and anything we can't positively resolve
     * to a different DXCC/continent are all treated as answerable.
     *
     * @param callsignTo the decoded message's callsignTo field
     * @return true if answerable (or not a CQ at all)
     */
    public static synchronized boolean directionalCQIsForMe(String callsignTo) {
        String token = getDirectionalCQToken(callsignTo);
        if (token == null) return true;                       // plain CQ / not a CQ
        if (token.matches("[0-9]{3}")) return true;           // zone-directed CQ nnn
        if (ACTIVITY_TOKENS.contains(token)) return true;     // DX / POTA / TEST / ...
        if (CONTINENT_CODES.contains(token)) {                // continent-directed
            return myContinent == null || token.equalsIgnoreCase(myContinent);
        }
        // country/region prefix (e.g. JA) — match by DXCC
        if (callsignDatabase == null || myDxcc == null) return true;
        CallsignInfo info = callsignDatabase.getCallInfo(token);
        if (info == null || info.DXCC == null) return true;   // unresolved → fail-open
        return info.DXCC.equalsIgnoreCase(myDxcc);
    }


    // The followed-callsigns list. Mutated concurrently with no external lock:
    // the decode/DB threads add (MainViewModel.addFollowCallsign,
    // getFollowCallsignsFromDataBase) and the UI thread clears it
    // (ClearCacheDataDialog), while the web logbook renders it on a NanoHTTPD
    // worker thread (LogHttpServer). A plain ArrayList corrupts its backing
    // array / throws IndexOutOfBounds under that contention, so this is a
    // CopyOnWriteArrayList: every add/clear is atomic. Readers must iterate the
    // list itself (for-each snapshots the array) rather than size()+get(i),
    // which can still race a concurrent clear.
    // final: the thread-safety invariant depends on this always being the
    // CopyOnWriteArrayList — never reassign it to a plain List. Mutate in place
    // (add/clear) only.
    public static final List<String> followCallsign = new CopyOnWriteArrayList<>();//Followed callsigns

    // The calling-UI "followed entries" list. Mutated concurrently from three
    // threads with no external lock: the decode thread (findIncludedCallsigns
    // add + trimToMessageCount remove(0) + clear), the TX-sequencer thread
    // (FT8TransmitSignal.doComplete reverse scans, onBeforeTransmit add) and the
    // UI thread (GridTracker / GridMarkerInfoWindow add, clearTransmittingMessage).
    // A plain ArrayList corrupts its backing array / throws
    // IndexOutOfBounds under that contention, so this is a CopyOnWriteArrayList:
    // every add/remove/clear is atomic. Index scans must still snapshot the list
    // first (size() then get(i) can otherwise race a concurrent remove) — see
    // FT8TransmitSignal.doComplete.
    // final: the thread-safety invariant depends on this always being the
    // CopyOnWriteArrayList — never reassign it to a plain List, or the cross-thread
    // crash this guards against returns. Mutate in place (add/remove/clear) only.
    public static final List<Ft8Message> transmitMessages = new CopyOnWriteArrayList<>();//List for the calling UI, followed entries

    public static void setMyMaidenheadGrid(String grid) {
        myMaidenheadGrid = grid;
        mutableMyMaidenheadGrid.postValue(grid);
    }

    public static String getMyMaidenheadGrid() {
        return myMaidenheadGrid;
    }

    // ===== FT8 DXpedition "Hound" mode =====
    // When true, the TX engine runs the Hound QSO variant (call Fox high at
    // 1000-4000 Hz, auto-QSY down to where Fox calls us, reply R+rpt, log on
    // RR73) instead of the standard auto-sequencer. Mutually exclusive with the
    // Hunt auto-answer-CQ mode. houndFoxCall is the Fox's base callsign.
    public static boolean houndMode = false;
    public static String houndFoxCall = "";

    public static float getBaseFrequency() {
        return baseFrequency;
    }

    /** The descriptor for the current {@link #operatingMode} (FT8/FT4). */
    public static ModeProfile currentMode() {
        return ModeProfile.fromId(operatingMode);
    }

    public static void setBaseFrequency(float baseFrequency) {
        mutableBaseFrequency.postValue(baseFrequency);
        GeneralVariables.baseFrequency = baseFrequency;
    }

    /** Minimum/maximum spectrum display width (Hz), matching the settings UI slider range. */
    public static final int MIN_SPECTRUM_WIDTH_HZ = 2500;
    public static final int MAX_SPECTRUM_WIDTH_HZ = 5000;

    public static int getSpectrumWidth() {
        return spectrumWidth;
    }

    /**
     * Set the spectrum display width, clamped to
     * [{@link #MIN_SPECTRUM_WIDTH_HZ}, {@link #MAX_SPECTRUM_WIDTH_HZ}] Hz.
     *
     * <p>This is display-only geometry: the waterfall/spectrum views divide the
     * view pixel width by it to place the TX marker and message labels
     * (e.g. {@code WaterfallView.freq_width = w / spectrumWidth}) and it drives
     * click-to-tune. The settings UI already constrains the value, but config
     * hydration ({@code DatabaseOpr}'s {@code parseConfigInt(result, 3500)})
     * reaches this setter with whatever a hand-edited/corrupted settings backup
     * persisted, unclamped. A stored {@code 0}/negative made {@code freq_width}
     * {@code Infinity}/negative, so the marker, labels, and click-to-tune drew at
     * {@code Infinity}/{@code NaN}/mirrored coordinates. Clamping here (mirroring
     * {@link #setFftWindowType(int)}) keeps every consumer's geometry finite and
     * is byte-identical for every in-range value.
     */
    public static void setSpectrumWidth(int width) {
        int clamped = Math.max(MIN_SPECTRUM_WIDTH_HZ, Math.min(MAX_SPECTRUM_WIDTH_HZ, width));
        mutableSpectrumWidth.postValue(clamped);
        GeneralVariables.spectrumWidth = clamped;
    }

    /**
     * Inclusive bounds (ms) for the manual clock correction ({@link #manualTimeCorrectionMs}
     * / {@code UtcTimer.delay}). This is the single source of truth for the range:
     * the live settings UI's {@code TIME_CORRECTION_MIN_MS}/{@code TIME_CORRECTION_MAX_MS}
     * in {@code TimeCorrection.kt} now reference these constants, so the UI slider and
     * the reload-time clamp ({@link #clampManualTimeCorrectionMs}) can't drift apart.
     * The range is ±5 s (widened from ±2 s so an offline phone that has drifted several
     * seconds — a field-reported Samsung A50 needed over 3 s — can be pulled back).
     */
    public static final int MANUAL_TIME_CORRECTION_MIN_MS = -5000;
    public static final int MANUAL_TIME_CORRECTION_MAX_MS = 5000;

    /**
     * Clamp a manual clock correction to
     * [{@link #MANUAL_TIME_CORRECTION_MIN_MS}, {@link #MANUAL_TIME_CORRECTION_MAX_MS}] ms.
     *
     * <p>The live settings UI already clamps to this range before persisting
     * ({@code TimeSyncSettings.apply} → {@code clampCorrectionMs}), but config
     * hydration on every launch ({@code DatabaseOpr}'s {@code timeCorrectionMs}
     * branch) re-applies the persisted value to {@code UtcTimer.delay} and must
     * clamp with the <em>same</em> bounds. The reload path used to clamp to ±2000
     * while the UI allowed ±5000, so any correction beyond ±2 s was silently
     * truncated back to 2 s at startup — leaving the operator's carefully-set
     * offline clock offset wrong by up to 3 s and degrading decodes. Byte-identical
     * for every in-range value.
     */
    public static int clampManualTimeCorrectionMs(int ms) {
        return Math.max(MANUAL_TIME_CORRECTION_MIN_MS,
                Math.min(MANUAL_TIME_CORRECTION_MAX_MS, ms));
    }

    public static int getFftWindowType() {
        return fftWindowType;
    }

    /** Out-of-range values clamp to the default (1 = Hann). */
    public static void setFftWindowType(int type) {
        fftWindowType = (type >= 0 && type <= 4) ? type : 1;
    }

    public static int getFftAveragingMode() {
        return fftAveragingMode;
    }

    /** Out-of-range values clamp to the default (0 = off). */
    public static void setFftAveragingMode(int mode) {
        fftAveragingMode = (mode >= 0 && mode <= 2) ? mode : 0;
    }

    public static int getSpectrumBinAggregation() {
        return spectrumBinAggregation;
    }

    /** Out-of-range values clamp to the default (0 = max, the legacy combine). */
    public static void setSpectrumBinAggregation(int mode) {
        spectrumBinAggregation = (mode >= 0 && mode <= 2) ? mode : 0;
    }

    public static String getCloudlogServerAddress() {
        return cloudlogServerAddress;
    }

    public static String getCloudlogStationID() {
        return cloudlogStationID;
    }

    public static String getCloudlogServerApiKey() {
        return cloudlogApiKey;
    }

    public static String getQrzApiKey() {
        return qrzApiKey;
    }


    @SuppressLint("DefaultLocale")
    public static String getBaseFrequencyStr() {
        return String.format("%.0f", baseFrequency);
    }

    public static String getCivAddressStr() {
        return String.format("%2X", civAddress);
    }

    public static String getTransmitDelayStr() {
        return String.valueOf(transmitDelay);
    }

    public static String getBandString() {
        return BaseRigOperation.getFrequencyAllInfo(band);
    }

    /**
     * Check if a callsign has been successfully contacted
     *
     * @param callsign callsign
     * @return whether it exists
     */
    public static boolean checkQSLCallsign(String callsign) {
        return QSL_Callsign_list.contains(callsign);
    }

    /**
     * Check if a callsign has been successfully contacted on other bands
     *
     * @param callsign callsign
     * @return whether it exists
     */
    public static boolean checkQSLCallsign_OtherBand(String callsign) {
        return QSL_Callsign_list_other_band.contains(callsign);
    }

    /**
     * Check if a callsign has been contacted today or yesterday (any band). Backs
     * the TODAY worked-station scope; the list is (re)loaded from the log by
     * {@code GetAllQSLCallsign}.
     *
     * @param callsign callsign
     * @return whether it exists
     */
    public static boolean checkQSLCallsignToday(String callsign) {
        return QSL_Callsign_list_today.contains(callsign);
    }

    /**
     * Replace the user-maintained worked-station list (FROM_LIST scope) from a
     * user-entered comma/space/pipe-separated string.
     */
    public static synchronized void addWorkedStationList(String callsigns) {
        parseBlockTokens(callsigns, workedStationList);
    }

    /**
     * The worked-station list as the canonical comma-separated string (persistence
     * and Settings display).
     */
    public static synchronized String getWorkedStationList() {
        return joinBlockTokens(workedStationList);
    }

    /**
     * Check whether a callsign is in the user-maintained worked-station list.
     *
     * @param callsign callsign
     * @return whether it is present
     */
    public static synchronized boolean checkWorkedListCallsign(String callsign) {
        if (callsign == null) return false;
        return workedStationList.contains(callsign.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Check if a 4-character Maidenhead grid has been previously worked (any band).
     * Caller should pass the first 4 characters upper-cased.
     */
    public static boolean checkQSLGrid(String grid) {
        if (grid == null || grid.length() < 4) return false;
        return QSL_Grid_list.contains(grid.substring(0, 4).toUpperCase());
    }

    /**
     * Check whether a CQ WPX prefix (e.g. "W1", "DL0") has already been worked on
     * any band. Caller should pass a prefix already normalized by
     * {@link com.k1af.ft8af.callsign.WpxPrefix#of(String)} (upper case).
     */
    public static boolean checkQSLPrefix(String prefix) {
        if (prefix == null || prefix.isEmpty()) return false;
        return QSL_Prefix_list.contains(prefix);
    }

    /**
     * Check if a POTA park reference (e.g. "K-1234") has been previously hunted (any band).
     */
    public static boolean checkQSLPark(String ref) {
        if (ref == null || ref.isEmpty()) return false;
        return QSL_Pota_list.contains(ref.toUpperCase());
    }

    /**
     * Check if a callsign contains my callsign
     *
     * @param callsign callsign
     * @return boolean
     */
    static public boolean checkIsMyCallsign(String callsign) {
        if (GeneralVariables.myCallsign.length() == 0) return false;
        String temp = getShortCallsign(GeneralVariables.myCallsign);
        return callsign.contains(temp);
    }

    /**
     * For compound callsigns, get the callsign with prefix or suffix removed
     *
     * @return callsign
     */
    static public String getShortCallsign(String callsign) {
        if (callsign.contains("/")) {
            String[] temp = callsign.split("/");
            // An all-slash string ("/", "//", ...) splits to a zero-length array
            // because Java strips trailing empty tokens; there is no segment to
            // return, so fall back to the original rather than indexing temp[0]
            // and throwing ArrayIndexOutOfBoundsException.
            if (temp.length == 0) {
                return callsign;
            }
            int max = 0;
            int max_index = 0;
            for (int i = 0; i < temp.length; i++) {
                if (temp[i].length() > max) {
                    max = temp[i].length();
                    max_index = i;
                }
            }
            return temp[max_index];
        } else {
            return callsign;
        }
    }

    /**
     * Check if the callsign is in the followed callsign list.
     *
     * @param callsign Callsign
     * @return Whether it exists
     */
    public static boolean callsignInFollow(String callsign) {
        return followCallsign.contains(callsign);
    }

    /**
     * Add to the list of successfully contacted callsigns.
     *
     * @param callsign Callsign
     */
    public static void addQSLCallsign(String callsign) {
        if (!checkQSLCallsign(callsign)) {
            QSL_Callsign_list.add(callsign);
        }
    }

    public static String getMyMaidenhead4Grid() {
        if (myMaidenheadGrid.length() > 4) {
            return myMaidenheadGrid.substring(0, 4);
        }
        return myMaidenheadGrid;
    }

    /**
     * Auto-procedure run start time.
     */
    public static void resetLaunchSupervision() {
        launchSupervisionStart = UtcTimer.getSystemTime();
    }

    /**
     * Get the auto-procedure run duration.
     *
     * @return Milliseconds
     */
    public static int launchSupervisionCount() {
        return (int) (UtcTimer.getSystemTime() - launchSupervisionStart);
    }

    public static boolean isLaunchSupervisionTimeout() {
        if (launchSupervision == 0) return false;//0 means no supervision
        return launchSupervisionCount() > launchSupervision;
    }

    /**
     * Get message sequence from extraInfo.
     *
     * @param extraInfo Extended content in the message
     * @return Returns message sequence number
     */
    public static int checkFunOrderByExtraInfo(String extraInfo) {
        if (checkFun5(extraInfo)) return 5;
        if (checkFun4(extraInfo)) return 4;
        if (checkFun3(extraInfo)) return 3;
        if (checkFun2(extraInfo)) return 2;
        if (checkFun1(extraInfo)) return 1;
        return -1;
    }

    /**
     * Check message sequence number; returns -1 if parsing fails.
     *
     * @param message Message
     * @return Message sequence number
     */
    public static int checkFunOrder(Ft8Message message) {
        if (message.checkIsCQ()) return 6;
        return checkFunOrderByExtraInfo(message.extraInfo);

    }


    //check if this is a grid report
    public static boolean checkFun1(String extraInfo) {
        //grid report must be 4 characters, or no grid
        return (extraInfo.trim().matches("[A-Z][A-Z][0-9][0-9]") && !extraInfo.equals("RR73"))
                || (extraInfo.trim().length() == 0);

    }

    //check if this is a signal report, e.g. -10
    public static boolean checkFun2(String extraInfo) {
        if (extraInfo.trim().length() < 2) {
            return false;
        }//signal report must be at least 2 characters
        try {
            return Integer.parseInt(extraInfo.trim()) != 73;//if 73, it's message 6, not message 2
            //return true;
        } catch (Exception e) {
            return false;
        }
    }

    //check if this is an R-prefixed signal report, e.g. R-10
    public static boolean checkFun3(String extraInfo) {
        if (extraInfo.trim().length() < 3) {
            return false;
        }//R-prefixed signal report must be at least 3 characters
        //if first char is not R, or second char is R, then not message 3
        if ((extraInfo.trim().charAt(0) != 'R') || (extraInfo.trim().charAt(1) == 'R')) {
            return false;
        }

        try {
            Integer.parseInt(extraInfo.trim().substring(1));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    //check if this is RRR or RR73
    public static boolean checkFun4(String extraInfo) {
        return extraInfo.trim().equals("RR73") || extraInfo.trim().equals("RRR");
    }

    //check if this is 73
    public static boolean checkFun5(String extraInfo) {
        return extraInfo.trim().equals("73");
    }


    /**
     * Determine if this is a signal report; if so, assign the value to report.
     *
     * @param extraInfo Message extension
     * @return Signal report value; -100 if not found
     */
    public static int checkFun2_3(String extraInfo) {
        if (extraInfo.equals("73")) return -100;
        if (extraInfo.matches("[R]?[+-]?[0-9]{1,2}")) {
            try {
                return Integer.parseInt(extraInfo.replace("R", ""));
            } catch (Exception e) {
                return -100;
            }
        }
        return -100;
    }

    /**
     * Determine if this is a grid report; if so, assign the value to report.
     *
     * @param extraInfo Message extension
     * @return Signal report
     */
    public static boolean checkFun1_6(String extraInfo) {
        return extraInfo.trim().matches("[A-Z][A-Z][0-9][0-9]")
                && !extraInfo.trim().equals("RR73");
    }

    /**
     * Check if this is a QSO ending: RRR, RR73, or 73.
     *
     * @param extraInfo Message suffix
     * @return Whether
     */
    public static boolean checkFun4_5(String extraInfo) {
        return extraInfo.trim().equals("RR73")
                || extraInfo.trim().equals("RRR")
                || extraInfo.trim().equals("73");
    }

    /**
     * Extract a string from String.xml.
     *
     * @param id id
     * @return String
     */
    public static String getStringFromResource(int id) {
        if (getMainContext() != null) {
            return getMainContext().getString(id);
        } else {
            return "";
        }
    }


    /**
     * Add an already-contacted DXCC entity to the set.
     *
     * @param dxccPrefix DXCC prefix
     */
    public static void addDxcc(String dxccPrefix) {
        dxccMap.put(dxccPrefix, dxccPrefix);
    }

    /**
     * Check if this is an already-contacted DXCC entity.
     *
     * @param dxccPrefix DXCC prefix
     * @return Whether
     */
    public static boolean getDxccByPrefix(String dxccPrefix) {
        return dxccMap.containsKey(dxccPrefix);
    }

    /**
     * Add a CQ zone to the list.
     *
     * @param cqZone CQ zone number
     */
    public static void addCqZone(int cqZone) {
        cqMap.put(cqZone, cqZone);
    }

    /**
     * Check if there is an already-contacted CQ zone.
     *
     * @param cq CQ zone number
     * @return Whether it exists
     */
    public static boolean getCqZoneById(int cq) {
        return cqMap.containsKey(cq);
    }

    /**
     * Add an ITU zone to the already-contacted ITU list.
     *
     * @param itu ITU number
     */
    public static void addItuZone(int itu) {
        ituMap.put(itu, itu);
    }

    /**
     * Check if the ITU zone is in the already-contacted list.
     *
     * @param itu ITU number
     * @return Whether it exists
     */
    public static boolean getItuZoneById(int itu) {
        return ituMap.containsKey(itu);
    }

    /**
     * Add an already-contacted US state to the set.
     *
     * @param state USPS state code (e.g. "ND")
     */
    public static void addState(String state) {
        if (state == null || state.isEmpty()) return;
        workedStates.add(state.toUpperCase());
    }

    /**
     * Check if this US state has already been contacted.
     *
     * @param state USPS state code
     * @return whether it is in the worked set
     */
    public static boolean getStateWorked(String state) {
        return state != null && workedStates.contains(state.toUpperCase());
    }

    /**
     * Resolve a 4-character Maidenhead field to a US state code, or null if it is not a
     * US grid (the bundled table is US-only, so non-US grids return null naturally).
     *
     * <p>Backed by the same {@code assets/us_grid_states.json} the Compose UI reads via
     * {@code UsStateLookup}; loaded once on first use. Used both for live-decode "new
     * state" detection (CallsignDatabase) and worked-state import (DatabaseOpr).
     *
     * @param grid the station's Maidenhead grid (4+ chars); shorter/empty returns null
     * @return USPS state code, or null
     */
    public static String stateForGrid(String grid) {
        if (grid == null || grid.length() < 4) return null;
        Map<String, String> m = gridStateMap;
        if (m == null) {
            m = loadGridStateMap();
            gridStateMap = m;
        }
        return m.get(grid.substring(0, 4).toUpperCase());
    }

    private static synchronized Map<String, String> loadGridStateMap() {
        if (gridStateMap != null) return gridStateMap;
        Map<String, String> out = new HashMap<>();
        Context ctx = getMainContext();
        if (ctx != null) {
            try {
                java.io.InputStream is = ctx.getAssets().open("us_grid_states.json");
                java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(is));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                org.json.JSONObject obj = new org.json.JSONObject(sb.toString());
                Iterator<String> keys = obj.keys();
                while (keys.hasNext()) {
                    String k = keys.next();
                    out.put(k.toUpperCase(), obj.getString(k));
                }
            } catch (Exception e) {
                Log.w(TAG, "loadGridStateMap: failed to load us_grid_states.json", e);
            }
        }
        return out;
    }

    //used to trigger new grid
    public static MutableLiveData<String> mutableNewGrid = new MutableLiveData<>();

    /**
     * Add the callsign-to-grid mapping to the callsign-grid lookup table.
     *
     * @param callsign Callsign
     * @param grid     Grid
     */
    public static void addCallsignAndGrid(String callsign, String grid) {
        if (grid.length() >= 4) {
            callsignAndGrids.put(callsign, grid);
            mutableNewGrid.postValue(grid);
        }
    }

    /**
     * Callsign-grid lookup table. Look up grid by callsign.
     * If not in memory, should look up in the database.
     *
     * @param callsign Callsign
     * @return Whether a corresponding grid exists
     */
    public static boolean getCallsignHasGrid(String callsign) {
        return callsignAndGrids.containsKey(callsign);
    }

    /**
     * Callsign-grid lookup table. Look up grid by callsign, requiring both callsign and grid to match.
     * This function is for updating the lookup table database.
     *
     * @param callsign Callsign
     * @param grid     Grid
     * @return Whether a corresponding grid exists
     */
    public static boolean getCallsignHasGrid(String callsign, String grid) {
        if (!callsignAndGrids.containsKey(callsign)) return false;//this callsign doesn't exist at all
        String s = callsignAndGrids.get(callsign);
        if (s == null) return false;
        return s.equals(grid);
    }

    public static String getGridByCallsign(String callsign, DatabaseOpr db) {
        String s = callsign.replace("<", "").replace(">", "");
        if (getCallsignHasGrid(s)) {
            return callsignAndGrids.get(s);
        } else {
            db.getCallsignQTH(callsign);
            return "";
        }
    }

    /**
     * Traverse the callsign-grid lookup table and generate HTML.
     *
     * @return HTML
     */
    public static String getCallsignAndGridToHTML() {
        StringBuilder result = new StringBuilder();
        int order = 0;
        for (String key : callsignAndGrids.keySet()) {
            order++;
            HtmlContext.tableKeyRow(result, order % 2 != 0, key, callsignAndGrids.get(key));
        }
        return result.toString();
    }

    /**
     * Trim {@code list} from the front down to {@link #MESSAGE_COUNT} entries.
     * Accepts any {@link List} (the shared calling list is a CopyOnWriteArrayList),
     * so the name reflects the contract rather than a concrete ArrayList.
     */
    public static synchronized void trimToMessageCount(List<Ft8Message> list) {
        if (list.size() > GeneralVariables.MESSAGE_COUNT) {
            while (list.size() > GeneralVariables.MESSAGE_COUNT) {
                list.remove(0);
            }
        }
    }

    /**
     * Determine if it is an integer that fits in an {@code int}.
     *
     * <p>Callers use this as a guard immediately before {@code Integer.parseInt} /
     * {@code toInt()} (e.g. the ICOM/Xiegu network-port fields). A digit-only
     * regex is <em>not</em> sufficient for that contract: a string such as
     * {@code "9999999999"} is all digits yet overflows {@code int}, so the
     * subsequent parse throws {@link NumberFormatException}. Those parses run on
     * the UI thread with no surrounding try/catch, so the mismatch was a hard
     * crash. Verify the value actually parses so the guard cannot lie.
     *
     * @param str Input string
     * @return Returns true only if {@code str} is a non-empty run of digits that
     *         parses into an {@code int}, false otherwise
     */

    public static boolean isInteger(String str) {
        if (str == null || "".equals(str.trim()) || !str.matches("^[0-9]*$"))
            return false;
        try {
            Integer.parseInt(str);
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /**
     * Audio output data type; not available in network mode.
     */
    public enum AudioOutputBitMode {
        Float32,
        Int16
    }

    /**
     * Create a temporary file.
     *
     * @param context Context
     * @param prefix  Prefix
     * @param suffix  Extension
     * @return File object
     */
    public static File getTempFile(Context context, String prefix, String suffix) {
        File tempDir = context.getExternalCacheDir();
        if (tempDir == null) {
            // Error: unable to get temp directory
            Log.e(TAG, "Error creating temp file! Unable to get temp directory");
            return null;
        }

        try {
            //tempFile.deleteOnExit(); // file will be deleted when JVM exits
            return File.createTempFile(prefix, suffix, tempDir);
        } catch (IOException e) {
            Log.e(TAG, "Error creating temp file! " + e.getMessage());
            return null;
        }
    }

    /**
     * Write text data to a file.
     *
     * @param file File
     * @param data Text data
     */
    public static void writeToFile(File file, String data) {
        FileOutputStream fileOutputStream = null;
        try {
            fileOutputStream = new FileOutputStream(file, true);
            fileOutputStream.write(data.getBytes());
            Log.e(TAG, "File data write complete!");
        } catch (IOException e) {
            Log.e(TAG, String.format("Error writing file: %s", e.getMessage()));
        } finally {
            try {
                if (fileOutputStream != null) {
                    fileOutputStream.close();
                }
            } catch (IOException e) {
                Log.e(TAG, String.format("Error closing file writer: %s", e.getMessage()));
            }
        }
    }


    /**
     * Save data packet cache file.
     *
     * @param context Context
     * @param prefix  Prefix
     * @param suffix  Extension
     * @param data    Data
     * @return File object
     */
    public static File writeToTempFile(Context context, String prefix, String suffix, String data) {
        File file = getTempFile(context, prefix, suffix);
        writeToFile(file, data);
        if (file != null) {
            file.deleteOnExit(); // file will be deleted when JVM exits
        }
        return file;
    }

//    /**
//     * Share file
//     *
//     * @param context Context
//     * @param file    File object
//     * @param title   Title
//     */
//    public static void shareFile(Context context, File file, String title) {
//        Intent sharingIntent = new Intent(Intent.ACTION_SEND);
//        Uri fileUri = FileProvider.getUriForFile(context.getApplicationContext()
//                , "com.k1af.ft8af.fileprovider", file);
//        //sharingIntent.setType("application/octet-stream");
//        sharingIntent.setType("text/plain");
//        sharingIntent.putExtra(Intent.EXTRA_STREAM, fileUri);
//        sharingIntent.setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
//        context.startActivity(Intent.createChooser(sharingIntent, title));
//
//    }

    /**
     * Delete folder.
     *
     * @param dir Folder
     * @return Whether successfully deleted
     */
    public static boolean deleteDir(File dir) {
        if (dir == null) return false;
        if (dir.isDirectory()) {
            String[] children = dir.list();
            if (children != null) {
                for (String child : children) {
                    boolean success = deleteDir(new File(dir, child));
                    if (!success) {
                        return false;
                    }
                }
            }
        }
        return dir.delete();
    }

    public static void clearCache(Context context) {
        try {
            File dir = context.getExternalCacheDir();
            deleteDir(dir);
        } catch (Exception e) {
            // Handle exception
        }
    }

}
