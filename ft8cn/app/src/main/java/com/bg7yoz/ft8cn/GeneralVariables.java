package com.bg7yoz.ft8cn;
/**
 * Common variables. There is a memory leak risk with mainContext; to be addressed later.
 * mainContext
 */

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.Log;

import androidx.lifecycle.MutableLiveData;

import com.bg7yoz.ft8cn.callsign.CallsignDatabase;
import com.bg7yoz.ft8cn.callsign.CallsignInfo;
import com.bg7yoz.ft8cn.connector.ConnectMode;
import com.bg7yoz.ft8cn.database.ControlMode;
import com.bg7yoz.ft8cn.database.DatabaseOpr;
import com.bg7yoz.ft8cn.ft8transmit.QslRecordList;
import com.bg7yoz.ft8cn.html.HtmlContext;
import com.bg7yoz.ft8cn.rigs.BaseRigOperation;
import com.bg7yoz.ft8cn.timer.UtcTimer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


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

    public static boolean distanceInMiles = true;//Display distances in miles (true) or kilometers (false)

    public static boolean deepDecodeMode = false;//Whether deep decode mode is enabled

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
    private static float baseFrequency = 1000;//Audio frequency

    public static boolean simpleCallItemMode = false;//Compact message mode

    public static boolean clearDecodesEveryCycle = false;//Clear the decode list at the start of each cycle

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

    public static String cloudlogServerAddress = "";//Cloudlog server address
    public static String cloudlogApiKey = "";//Cloudlog API key
    public static String cloudlogStationID = "";//Cloudlog station ID
    public static String qrzApiKey = ""; //QRZ API key
    public static String qrzXmlUsername = ""; //QRZ XML API username (for callsign lookups)
    public static String qrzXmlPassword = ""; //QRZ XML API password
    public static boolean pskOverlayEnabled = false; //PSK Reporter map overlay (issue #33)
    public static boolean synFrequency = false;//Same-frequency transmit
    public static int transmitDelay = 500;//Transmit delay; also allows decoding time for the previous cycle
    public static int pttDelay = 100;//PTT response time; radios typically need some response time after PTT command, default 100ms
    public static int lateStartTolerance = 2000;//Max ms into a cycle that a manual TX may start; leading audio is clipped so TX still ends on the cycle boundary. 0-4000.
    public static int manualTimeCorrectionMs = 0;//Manual clock correction (ms) applied to UtcTimer.delay; for field use without internet NTP. Range -2000..2000. See TimeSyncSettings.
    public static boolean earlyDecode = true;//Fast turnaround: decode a shorter RX window so CQ decodes appear ~1s before the cycle boundary, enabling a next-slot reply.
    public static int operatingMode = FT8Common.FT8_MODE;//Current operating mode (FT8Common.FT8_MODE / FT4_MODE); persisted as config "operatingMode".
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
    public static java.util.HashSet<String> excludedBands = new java.util.HashSet<>();

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

    //The following 4 parameters are for ICOM network connection
    public static String icomIp = "255.255.255.255";
    public static int icomUdpPort = 50001;
    public static String icomUserName = "ic705";
    public static String icomPassword = "";


    public static boolean autoFollowCQ = true;//Auto-follow CQ
    public static boolean huntCallsCQ = false;//Hunt+CQ hybrid: call CQ when idle, answer CQs when heard
    public static boolean autoCallFollow = true;//Auto-call followed callsigns
    public static boolean autoUpdateGridFromGPS = false;//Use device GPS to keep Maidenhead grid current
    public static ArrayList<String> QSL_Callsign_list = new ArrayList<>();//Successfully QSL'd callsigns
    public static ArrayList<String> QSL_Callsign_list_other_band = new ArrayList<>();//Successfully QSL'd callsigns on other bands
    public static HashSet<String> QSL_Grid_list = new HashSet<>();//Distinct worked 4-char Maidenhead grids (any band)
    public static HashSet<String> QSL_Pota_list = new HashSet<>();//Distinct hunted POTA park refs (UPPER), any band

    // Decode-list highlight toggles (Settings → Decode Highlights). Gate the
    // status pill shown for each worked-before category in resolveQsoStatus().
    public static boolean highlightNewDxcc = true;//Highlight stations from an unworked DXCC entity
    public static boolean highlightNewGrid = false;//Off by default — most grids are "new", so it's noisy
    public static boolean highlightNewBand = true;//Highlight stations worked only on other bands
    public static boolean highlightWorked = true;//Tag stations already worked
    public static boolean highlightPota = true;//Highlight spotted POTA activators (new parks stand out)

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


    public static final ArrayList<String> followCallsign = new ArrayList<>();//Followed callsigns

    public static ArrayList<Ft8Message> transmitMessages = new ArrayList<>();//List for the calling UI, followed entries

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

    public static int getSpectrumWidth() {
        return spectrumWidth;
    }

    public static void setSpectrumWidth(int width) {
        mutableSpectrumWidth.postValue(width);
        GeneralVariables.spectrumWidth = width;
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
     * Check if a 4-character Maidenhead grid has been previously worked (any band).
     * Caller should pass the first 4 characters upper-cased.
     */
    public static boolean checkQSLGrid(String grid) {
        if (grid == null || grid.length() < 4) return false;
        return QSL_Grid_list.contains(grid.substring(0, 4).toUpperCase());
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

    public static synchronized void deleteArrayListMore(ArrayList<Ft8Message> list) {
        if (list.size() > GeneralVariables.MESSAGE_COUNT) {
            while (list.size() > GeneralVariables.MESSAGE_COUNT) {
                list.remove(0);
            }
        }
    }

    /**
     * Determine if it is an integer.
     *
     * @param str Input string
     * @return Returns true if integer, false otherwise
     */

    public static boolean isInteger(String str) {
        if (str != null && !"".equals(str.trim()))
            return str.matches("^[0-9]*$");
        else
            return false;
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
//                , "com.bg7yoz.ft8cn.fileprovider", file);
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
