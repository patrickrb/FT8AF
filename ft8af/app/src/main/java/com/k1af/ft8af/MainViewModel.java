package com.k1af.ft8af;
/**
 * -----2022.5.6-----
 * MainViewModel class for decoding FT8 signals and storing decode-related variable data. Lives for the entire app lifecycle.
 * 1. Total decoded count: decoded_counter and mutable_Decoded_Counter.
 * 2. Decoded message list: Messages displayed as Ft8Message, list implemented with ArrayList generic. ft8Messages, mutableFt8MessageList.
 * 3. Both decoding and recording require time synchronization, with UTC time in 15-second cycles. Sync events are triggered by the UtcTimer class.
 * 4. Current UTC time: timerSec, update frequency (heartbeat) determined by UtcTimer, tentatively 100 milliseconds.
 * 5. Get the current MainViewModel instance via the getInstance class method, ensuring a unique instance.
 * 6. Recording implemented with HamAudioRecorder class; currently records to file then reads file data for the decode module. Needs to be changed to direct array method ----TO DO---
 * 7. Decoding uses JNI interface to call native C code. Interface name is ft8af, maintained by CMakeLists.txt in the cpp folder. Function call interfaces are in decode_ft8.cpp.
 * -----2022.5.9-----
 * If the system is not transmitting, the trigger will initiate recording each cycle. Since starting and stopping recording wastes some time,
 * if the previous recording action is not interrupted, consecutive cycles will have overlapping recording actions, causing the second recording to fail.
 * Therefore, before starting the second cycle's recording, the previous cycle's recording must be stopped, resulting in each recording
 * starting ~300ms after the cycle begins (emulator result). Actual recording length is typically around 14.77 seconds.
 * <p>
 *
 * 2023-08-16 Modified by DS1UFX (based on v0.9), added (tr)uSDX audio over CAT support.
 *
 * @author BG7YOZ
 * @date 2022.8.22
 */

import static com.k1af.ft8af.GeneralVariables.getStringFromResource;

import com.k1af.ft8af.concurrent.SafeExecutor;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;
import android.content.IntentFilter;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;

import com.k1af.ft8af.callsign.CallsignDatabase;
import com.k1af.ft8af.callsign.CallsignInfo;
import com.k1af.ft8af.callsign.OnAfterQueryCallsignLocation;
import com.k1af.ft8af.bluetooth.ScoPolicy;
import com.k1af.ft8af.connector.BluetoothRigConnector;
import com.k1af.ft8af.connector.CableConnector;
import com.k1af.ft8af.connector.CableSerialPort;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.connector.FlexConnector;
import com.k1af.ft8af.connector.IComWifiConnector;
import com.k1af.ft8af.connector.X6100Connector;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.database.DatabaseOpr;
import com.k1af.ft8af.database.OnAfterQueryFollowCallsigns;
import com.k1af.ft8af.database.OperationBand;
import com.k1af.ft8af.database.RigNameList;
import com.k1af.ft8af.flex.FlexRadio;
import com.k1af.ft8af.flex.RadioTcpClient;
import com.k1af.ft8af.ft8listener.FT8SignalListener;
import com.k1af.ft8af.ft8listener.OnFt8Listen;
import com.k1af.ft8af.ft8transmit.FT8TransmitSignal;
import com.k1af.ft8af.ft8transmit.MeterProtectionController;
import com.k1af.ft8af.ft8transmit.OnDoTransmitted;
import com.k1af.ft8af.ft8transmit.OnTransmitSuccess;
import com.k1af.ft8af.ft8transmit.TuneMethod;
import com.k1af.ft8af.html.LogHttpServer;
import com.k1af.ft8af.icom.WifiRig;
import com.k1af.ft8af.log.QSLCallsignRecord;
import com.k1af.ft8af.log.QSLRecord;
import com.k1af.ft8af.log.SWLQsoList;
import com.k1af.ft8af.log.ThirdPartyService;
import com.k1af.ft8af.rigs.BaseRig;
import com.k1af.ft8af.rigs.BaseRigOperation;
import com.k1af.ft8af.rigs.CatConnectionState;
import com.k1af.ft8af.rigs.CatLiveness;
import com.k1af.ft8af.rigs.DiscoveryTX500Rig;
import com.k1af.ft8af.rigs.ElecraftRig;
import com.k1af.ft8af.rigs.Flex6000Rig;
import com.k1af.ft8af.rigs.FlexNetworkRig;
import com.k1af.ft8af.rigs.GuoHeQ900Rig;
import com.k1af.ft8af.rigs.IcomRig;
import com.k1af.ft8af.rigs.InstructionSet;
import com.k1af.ft8af.rigs.KenwoodKT90Rig;
import com.k1af.ft8af.rigs.KenwoodTS2000Rig;
import com.k1af.ft8af.rigs.KenwoodTS440Rig;
import com.k1af.ft8af.rigs.KenwoodTS570Rig;
import com.k1af.ft8af.rigs.KenwoodTS590Rig;
import com.k1af.ft8af.rigs.OnRigStateChanged;
import com.k1af.ft8af.rigs.TrUSDXRig;
import com.k1af.ft8af.rigs.Wolf_sdr_450Rig;
import com.k1af.ft8af.rigs.XieGu6100NetRig;
import com.k1af.ft8af.rigs.XieGu6100Rig;
import com.k1af.ft8af.rigs.XieGuRig;
import com.k1af.ft8af.rigs.Yaesu2Rig;
import com.k1af.ft8af.rigs.Yaesu2_847Rig;
import com.k1af.ft8af.rigs.Yaesu38Rig;
import com.k1af.ft8af.rigs.Yaesu38_450Rig;
import com.k1af.ft8af.rigs.Yaesu39Rig;
import com.k1af.ft8af.rigs.YaesuDX10Rig;
import com.k1af.ft8af.spectrum.SpectrumListener;
import com.k1af.ft8af.timer.OnUtcTimer;
import com.k1af.ft8af.timer.UtcTimer;
import com.k1af.ft8af.ui.ToastMessage;
import com.k1af.ft8af.wave.HamRecorder;
import com.k1af.ft8af.wave.OnGetVoiceDataDone;
import com.k1af.ft8af.x6100.X6100Radio;

import radio.ks3ckc.ft8af.UsbPermissionIntentsKt;
import radio.ks3ckc.ft8af.pskreporter.PskReporterSender;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class MainViewModel extends ViewModel {
    String TAG = "ft8af MainViewModel";
    public final MutableLiveData<Boolean> mutableConfigLoaded = new MutableLiveData<>(false);
    public boolean configIsLoaded = false;

    /** Write debug line to the app's external files debug.log */
    private void fileLog(String msg) {
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx == null) return;
            java.io.File dir = ctx.getExternalFilesDir(null);
            if (dir == null) return;
            String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)
                    .format(new java.util.Date());
            new java.io.FileWriter(new java.io.File(dir, "debug.log"), true)
                    .append(ts + " " + msg + "\n").close();
        } catch (Exception ignored) {}
        Log.d(TAG, msg);
    }

    private static MainViewModel viewModel = null;//current existing instance.
    //public static Application application;


    //public int decoded_counter = 0;//total decoded count
    public final ArrayList<Ft8Message> ft8Messages = new ArrayList<>();//message list
    public UtcTimer utcTimer;//timer for sync-triggered actions.


    //public CallsignDatabase callsignDatabase = null;//callsign information database
    public DatabaseOpr databaseOpr;//configuration info and related data database


    public MutableLiveData<Integer> mutable_Decoded_Counter = new MutableLiveData<>();//total decoded count
    // Per-cycle decode state (label overlay + decode count). Synchronized: slot N's
    // late/deep pass and slot N+1's early pass call afterDecode concurrently (#398).
    private final DecodeCycleState decodeCycleState = new DecodeCycleState();
    public MutableLiveData<ArrayList<Ft8Message>> mutableFt8MessageList = new MutableLiveData<>();//message list
    // Needed-DX alerts: posts sound+vibrate notifications for new DXCC/state CQ stations.
    public final com.k1af.ft8af.alert.DxAlertNotifier dxAlertNotifier =
            new com.k1af.ft8af.alert.DxAlertNotifier(GeneralVariables.getMainContext());
    // Callsign from a tapped Needed-DX notification; the Decode screen observes this to
    // scroll to + highlight that station (pre-select). Set by ComposeMainActivity.
    public MutableLiveData<String> mutablePreselectCallsign = new MutableLiveData<>();
    public MutableLiveData<Long> timerSec = new MutableLiveData<>();//current UTC time. Update frequency determined by UtcTimer, ~100ms when not triggered.
    public MutableLiveData<Boolean> mutableIsRecording = new MutableLiveData<>();//whether currently recording
    public MutableLiveData<Boolean> mutableHamRecordIsRunning = new MutableLiveData<>();//whether HamRecord is running
    public MutableLiveData<Float> mutableTimerOffset = new MutableLiveData<>();//time delay of this cycle
    public MutableLiveData<Boolean> mutableIsDecoding = new MutableLiveData<>();//triggers marker action in spectrum display
    public MutableLiveData<Integer> mutableOperatingMode = new MutableLiveData<>(GeneralVariables.operatingMode);//current mode (FT8/FT4) for Compose observers
    /** Decoded messages in this cycle (used for drawing on spectrum). */
    public ArrayList<Ft8Message> getCurrentMessages() {
        return decodeCycleState.getMessages();
    }

    /** Number of decoded items in this cycle. */
    public int getCurrentDecodeCount() {
        return decodeCycleState.getDecodeCount();
    }

    /** Clear the waterfall label overlay (spectrum views call this when they (re)attach). */
    public void clearCurrentMessages() {
        decodeCycleState.clearMessages();
    }

    public MutableLiveData<Boolean> mutableIsFlexRadio = new MutableLiveData<>();//whether it's a Flex radio
    public MutableLiveData<Boolean> mutableIsXieguRadio = new MutableLiveData<>();//whether it's a XieGu radio

    private final ExecutorService getQTHThreadPool = Executors.newCachedThreadPool();
    private final ExecutorService sendWaveDataThreadPool = Executors.newCachedThreadPool();
    private final GetQTHRunnable getQTHRunnable = new GetQTHRunnable(this);
    private final SendWaveDataRunnable sendWaveDataRunnable = new SendWaveDataRunnable();


    //variables for displaying shared log generation progress
    public MutableLiveData<String> mutableShareInfo=new MutableLiveData<>("");//shared data status
    public MutableLiveData<Integer> mutableSharePosition=new MutableLiveData<>(0);//current position of shared data
    public MutableLiveData<Boolean> mutableShareRunning=new MutableLiveData<>(false);//whether generating shared data
    public MutableLiveData<Integer> mutableShareCount=new MutableLiveData<>(0);//total shared count
    public MutableLiveData<Boolean> mutableImportShareRunning=new MutableLiveData<>(false);//whether importing shared data

    // QSO bottom-sheet persistence (Compose UI). The callsign the sheet is
    // currently bound to (null = no sheet) and whether the user has
    // minimized it (slid down). Lives in the ViewModel so the sheet stays
    // open across navigation away from Decode and back.
    public MutableLiveData<String> qsoSheetCallsign = new MutableLiveData<>(null);
    public MutableLiveData<Boolean> qsoSheetMinimized = new MutableLiveData<>(false);

    // Decode-screen filter selection (Compose UI). Lives in the ViewModel so
    // the chosen filter survives navigation away from Decode and back, rather
    // than resetting to "All" each time the screen is recreated.
    public MutableLiveData<String> decodeFilter = new MutableLiveData<>("All");


    public HamRecorder hamRecorder;//recording object
    public FT8SignalListener ft8SignalListener;//object for listening to and decoding FT8 signals
    public FT8TransmitSignal ft8TransmitSignal;//object for transmitting signals
    // Deep-pass decodes that arrived mid-TX, replayed to the sequencer after key-up
    private final PendingSequencerDecodes pendingSequencerDecodes = new PendingSequencerDecodes();
    public MeterProtectionController meterProtectionController;//ALC auto-volume + SWR halt
    public SpectrumListener spectrumListener;//object for drawing the spectrum
    public boolean markMessage = true;//whether to mark messages toggle

    //rig control mode
    public OperationBand operationBand = null;

    private SWLQsoList swlQsoList = new SWLQsoList();//records SWL QSO objects, checks SWL QSOs to prevent duplicates.


    public MutableLiveData<ArrayList<CableSerialPort.SerialPort>> mutableSerialPorts = new MutableLiveData<>();
    private ArrayList<CableSerialPort.SerialPort> serialPorts;//serial port list
    public BaseRig baseRig;//rig
    //Observable CAT connection state for the UI status chip. The callbacks below
    //fire off the UI thread, so LiveData is updated via postValue. catConnectionState
    //is a synchronous mirror of the same value: a failed Bluetooth connect calls
    //onRunError() immediately followed by onDisconnected(), and reading LiveData's
    //value across those two posts would race — so the ERROR-preserving guard reads
    //the synchronous field instead.
    public final MutableLiveData<CatConnectionState> mutableCatConnectionState =
            new MutableLiveData<>(CatConnectionState.DISCONNECTED);
    private volatile CatConnectionState catConnectionState = CatConnectionState.DISCONNECTED;

    private void setCatConnectionState(CatConnectionState state) {
        catConnectionState = state;
        mutableCatConnectionState.postValue(state);
    }
    private final OnRigStateChanged onRigStateChanged = new OnRigStateChanged() {
        @Override
        public void onDisconnected() {
            //disconnected from rig. A failed connect fires onRunError() then
            //onDisconnected(); afterDisconnect() preserves ERROR so the chip can
            //stay red until the next connect attempt (onConnecting) or a success.
            stopCatLivenessWatchdog();
            setCatConnectionState(CatConnectionState.afterDisconnect(catConnectionState));
            ToastMessage.show(getStringFromResource(R.string.disconnect_rig));
        }

        @Override
        public void onConnecting() {
            //connection attempt started
            setCatConnectionState(CatConnectionState.CONNECTING);
        }

        @Override
        public void onConnected() {
            //connected to rig
            setCatConnectionState(CatConnectionState.CONNECTED);
            ToastMessage.show(getStringFromResource(R.string.connected_rig));
            // Push the app's current band/frequency to the rig on every connect —
            // including an automatic reconnect, which previously left the rig on
            // whatever frequency it powered up on ("no frequency set after connecting").
            // setOperationBand() no-ops if the rig isn't actually connected and has its
            // own settle delay, so a short post keeps us off the connect-callback thread
            // without racing the link coming up.
            new Handler(Looper.getMainLooper()).postDelayed(MainViewModel.this::setOperationBand, 1500);
            // (Re)start the liveness watchdog for this connection.
            startCatLivenessWatchdog();
        }

        @Override
        public void onPttChanged(boolean isOn) {

        }

        @Override
        public void onRigResponded() {
            // The rig answered with a valid frequency (changed or not) — the liveness signal.
            // onFreqChanged only fires on a change, so it can't be used here (a stable dial
            // would look dead and falsely trip the watchdog).
            markRigResponded();
        }

        @Override
        public void onFreqChanged(long freq) {
            //current frequency: %s
            ToastMessage.show(String.format(getStringFromResource(R.string.current_frequency)
                    , BaseRigOperation.getFrequencyAllInfo(freq)));
            //write frequency changes back to global variables
            // Compare the meter (wavelength) band, not the band-list index:
            // OperationBand.getIndexByFreq() appends a new entry for any previously-
            // unseen frequency, so a small in-band dial move changes the index even
            // though the band is the same. Wavelength only changes on a real band hop.
            String oldWaveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band);
            GeneralVariables.band = freq;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(freq);
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
            String newWaveLength = BaseRigOperation.getMeterFromFreq(freq);

            // A real band hop invalidates the clear-CQ-slot occupancy history —
            // the recorded offsets belong to the old band's activity (issue #418).
            if (ft8TransmitSignal != null && !newWaveLength.equals(oldWaveLength)) {
                ft8TransmitSignal.clearBandActivity();
            }

            // The rig reported a band change (e.g. the operator turned the dial) —
            // optionally clear the stale decodes + reset the TX target.
            if (shouldClearOnBandChange(GeneralVariables.clearOnBandModeChange,
                    oldWaveLength, newWaveLength)) {
                clearDecodesAndTarget();
            }

            databaseOpr.getAllQSLCallsigns();//read out successfully contacted callsigns

        }

        @Override
        public void onRunError(String message) {
            //rig communication error
            stopCatLivenessWatchdog();
            setCatConnectionState(CatConnectionState.ERROR);
            ToastMessage.show(String.format(getStringFromResource(R.string.radio_communication_error)
                    , message));
        }
    };

    // ===== CAT liveness watchdog =====
    // A Bluetooth/serial CAT link can stay "connected" after the radio is powered off (the
    // BT module keeps the socket up), so the chip stayed green and frequency writes went
    // nowhere. This watchdog periodically reads the rig's frequency and, if a previously-
    // responsive rig goes quiet for too long, flips the chip to error. See CatLiveness for
    // the guard logic (never judges during TX; only arms after the first reply, so a rig
    // that doesn't echo frequency reads is never falsely marked dead).
    private static final long CAT_LIVENESS_TICK_MS = 3000;
    private Timer catLivenessTimer;
    private volatile long lastRigResponseMs = 0;
    private volatile boolean sawRigResponseSinceConnect = false;

    /** Record that the rig just demonstrably responded (called from onRigResponded). */
    private void markRigResponded() {
        lastRigResponseMs = System.currentTimeMillis();
        sawRigResponseSinceConnect = true;
    }

    private synchronized void startCatLivenessWatchdog() {
        stopCatLivenessWatchdog();
        lastRigResponseMs = System.currentTimeMillis();
        sawRigResponseSinceConnect = false;
        catLivenessTimer = new Timer("cat-liveness");
        catLivenessTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                catLivenessTick();
            }
        }, CAT_LIVENESS_TICK_MS, CAT_LIVENESS_TICK_MS);
    }

    private synchronized void stopCatLivenessWatchdog() {
        if (catLivenessTimer != null) {
            catLivenessTimer.cancel();
            catLivenessTimer.purge();
            catLivenessTimer = null;
        }
    }

    /** One watchdog tick: probe the rig, then declare it dead if it's gone quiet too long. */
    private void catLivenessTick() {
        try {
            boolean connected = isRigConnected();
            boolean transmitting = ft8TransmitSignal != null && ft8TransmitSignal.isTransmitting();
            // Actively probe (a frequency read); the reply lands in onRigResponded ->
            // markRigResponded() (onFreqChanged only fires on a change, so a stable dial
            // can't be used). On a dead-but-powered BT module the write succeeds but no
            // reply comes, so the quiet timer below eventually trips.
            if (CatLiveness.shouldProbe(connected, transmitting) && baseRig != null) {
                baseRig.readFreqFromRig();
            }
            if (CatLiveness.isRigStale(connected, transmitting, sawRigResponseSinceConnect,
                    System.currentTimeMillis(), lastRigResponseMs, CatLiveness.DEFAULT_TIMEOUT_MS)) {
                stopCatLivenessWatchdog();
                setCatConnectionState(CatConnectionState.ERROR);
                ToastMessage.show(String.format(
                        getStringFromResource(R.string.radio_communication_error),
                        getStringFromResource(R.string.disconnect_rig)));
            }
        } catch (Exception e) {
            // Never let a probe failure crash the timer thread; a real I/O error already
            // surfaces via onRunError(). Log the exception object (not just getMessage(),
            // which can be null) so the stack trace is preserved.
            Log.w(TAG, "cat liveness tick failed", e);
        }
    }

    //message list for signal transmission
    //public ArrayList<Ft8Message> transmitMessages = new ArrayList<>();
    //public MutableLiveData<ArrayList<Ft8Message>> mutableTransmitMessages = new MutableLiveData<>();
    public MutableLiveData<Integer> mutableTransmitMessagesCount = new MutableLiveData<>();


    public boolean deNoise = false;//suppress noise in the spectrum

    //*********variables needed for log query********************
    public boolean logListShowCallsign = false;//display format in the log query list
    public String queryKey = "";//query keyword
    public int queryFilter = 0;//filter: 0=all, 1=confirmed, 2=unconfirmed
    public MutableLiveData<Integer> mutableQueryFilter = new MutableLiveData<>();
    public ArrayList<QSLCallsignRecord> callsignRecords = new ArrayList<>();
    //public ArrayList<QSLRecordStr> qslRecords=new ArrayList<>();
    //********************************************
    //followed callsign list
    //public ArrayList<String> followCallsign = new ArrayList<>();


    //log management HTTP SERVER
    private final LogHttpServer httpServer;

    /**
     * Get the MainViewModel instance, ensuring a unique instance exists throughout the entire app lifecycle.
     *
     * @param owner ViewModelStoreOwner owner, typically an Activity or Fragment.
     * @return MainViewModel Returns a MainViewModel instance.
     */
    public static MainViewModel getInstance(ViewModelStoreOwner owner) {
        if (viewModel == null) {
            viewModel = new ViewModelProvider(owner).get(MainViewModel.class);
        }
        return viewModel;
    }

    /**
     * Nullable peek at the singleton for observers that must never boot the engine —
     * only ComposeMainActivity may create the instance. The Android Auto screens use
     * this: null means the phone UI hasn't run yet this process, and the car display
     * shows an "open the app on your phone" message instead.
     */
    @Nullable
    public static MainViewModel peekInstance() {
        return viewModel;
    }

    /**
     * The value afterDecode() must post to mutableIsDecoding (the spectrum-display
     * "decoding" marker) once a decode pass finishes. beforeListen() turns the marker
     * on at the start of every cycle, so every pass must turn it back off when done —
     * a silent slot (rawDecodeCount == 0), an all-own-echo slot (keptCount == 0), and a
     * normal slot alike. Routing all three exit paths of afterDecode() through this keeps
     * them in agreement; an early return that skipped it once left the marker stuck on.
     */
    static boolean decodingMarkerAfterPass(int rawDecodeCount, int keptCount) {
        return false;//a completed decode pass always ends the decoding state
    }

    /**
     * Get the specified message from the message list.
     *
     * @param position Position in the Mutable type list.
     * @return Returns a decoded Ft8Message object.
     */
    public Ft8Message getFt8Message(int position) {
        return Objects.requireNonNull(ft8Messages.get(position));
    }

    /**
     * MainViewModel constructor accomplishes the following:
     * 1. Create a UTC-synchronized clock. The clock is UtcTimer class, internally implemented with Timer and TimerTask. Callbacks are multi-threaded; thread safety must be considered.
     * 2. Create Mutable-type decoded message list.
     */
    //@RequiresApi(api = Build.VERSION_CODES.N)
    public MainViewModel() {

        //get configuration info.
        databaseOpr = DatabaseOpr.getInstance(GeneralVariables.getMainContext()
                , "data.db");
        mutableIsDecoding.postValue(false);//decode state
        //create recording object
        hamRecorder = new HamRecorder(null);
        hamRecorder.startRecord();

        mutableIsFlexRadio.setValue(false);
        mutableIsXieguRadio.setValue(false);

        //create timer for displaying time — a 1-second (1000ms) tick, just to refresh the clock.
        utcTimer = new UtcTimer(1000, false, new OnUtcTimer() {
            @Override
            public void doHeartBeatTimer(long utc) {//clock info when not triggered

            }

            @Override
            public void doOnSecTimer(long utc) {//triggered at specified interval
                timerSec.postValue(utc);//send current UTC time
                mutableIsRecording.postValue(hamRecorder.isRunning());
                mutableHamRecordIsRunning.postValue(hamRecorder.isRunning());//send current timer state
            }
        });
        utcTimer.start();//start timer

        //synchronize time. Microsoft's NTP server
        UtcTimer.syncTime(null);

        // Seed with a snapshot, never the live ft8Messages instance. observeAsState
        // compares structurally, and every later snapshot published from
        // publishFt8MessageList() is content-equal to the live list it was copied
        // from — so a UI seeded with the live list stays pinned to it forever
        // (equal values are skipped, the state keeps the old reference) and Compose
        // ends up iterating the very list the decode thread mutates:
        // ConcurrentModificationException in buildQsoLog during recomposition.
        mutableFt8MessageList.setValue(uiSeedSnapshot(ft8Messages));

        //create listener object; callback actions handle decoding, transmitting, adding to followed callsign list, etc.
        ft8SignalListener = new FT8SignalListener(databaseOpr, new OnFt8Listen() {
            @Override
            public void beforeListen(long utc) {
                // "Clear every cycle" mode: wipe the previous slot's decodes at the very
                // start of each cycle, before decoding runs. Doing it here (rather than in
                // afterDecode) means even a silent slot — zero decodes, or everything
                // filtered out as own-TX echoes, both of which return early from
                // afterDecode — still clears, so the list only ever shows the current slot.
                // Deep decodes later in this same cycle augment the cleared list instead of
                // re-wiping it.
                if (GeneralVariables.clearDecodesEveryCycle) {
                    synchronized (ft8Messages) {
                        ft8Messages.clear();
                    }
                    decodeCycleState.resetCount();
                    mutable_Decoded_Counter.postValue(0);
                    publishFt8MessageList();
                }
                mutableIsDecoding.postValue(true);
            }

            @Override
            public void afterDecode(long utc, float time_sec, int sequential
                    , ArrayList<Ft8Message> decoded, boolean isDeep) {
                if (decoded.size() == 0) {
                    // beforeListen set mutableIsDecoding=true at the start of this cycle;
                    // clear it here so a silent slot doesn't leave the spectrum-display
                    // decoding marker stuck on until the next non-empty cycle (mirrors the
                    // own-echo-only early return below).
                    // Refresh the waterfall label overlay too: a silent normal pass must
                    // clear the previous slot's labels so they aren't re-stamped onto the
                    // waterfall every cycle (worst on the fast FT4/FT2 slots). A silent deep
                    // pass keeps the normal pass's labels. See WaterfallLabelMessages.
                    decodeCycleState.labelsAfterPass(new ArrayList<>(), isDeep);
                    mutableIsDecoding.postValue(decodingMarkerAfterPass(0, 0));
                    return;//no messages decoded, don't trigger action
                }

                // Filter out own-TX loopback echoes. When the rig monitors TX audio to
                // line-out the decoder hears our own transmission and decodes it; a decode
                // whose sender is our own callsign can only be that loopback, so it must
                // not reach the message list, QSO panel, or SWL database. See
                // OwnTxEchoFilter for the rationale. The QSO panel already shows what we
                // send via its synthesized TX entry, and PSKReporter / the auto-sequence
                // already ignore own-callsign messages.
                OwnTxEchoFilter filtered = OwnTxEchoFilter.filter(decoded);
                ArrayList<Ft8Message> messages = filtered.kept;
                // Diagnostic for the "missing other station responses" report: record how
                // many decodes survived, how many own-echoes were dropped, and whether any
                // message addressed to us was decoded this cycle. Lets us tell "decoded but
                // mis-rendered" from "never decoded" from a pulled debug.log. Skip deep
                // passes to avoid log spam (they re-report the same cycle).
                if (!isDeep) {
                    fileLog(filtered.decodeLogLine(sequential));
                }
                if (messages.size() == 0) {
                    //nothing left after filtering own echoes
                    // Same overlay refresh as the no-decode case: an all-filtered slot is
                    // visually silent, so clear the previous slot's labels (normal pass)
                    // rather than leaving them to be re-stamped.
                    decodeCycleState.labelsAfterPass(new ArrayList<>(), isDeep);
                    mutableIsDecoding.postValue(decodingMarkerAfterPass(decoded.size(), 0));
                    return;
                }

                // Diagnostic: log every CQ message so we can see what the JNI decoder
                // populates for "CQ DX" / "CQ POTA" style broadcasts.
                for (Ft8Message m : messages) {
                    if (m.checkIsCQ()) {
                        fileLog(String.format(
                                "CQ_DEBUG i3=%d n3=%d to=[%s] from=[%s] extra=[%s] modifier=[%s] msgText=[%s]",
                                m.i3, m.n3,
                                m.callsignTo, m.callsignFrom, m.extraInfo,
                                m.modifier == null ? "null" : m.modifier,
                                m.getMessageText()));
                    }
                }

                synchronized (ft8Messages) {
                    // "Clear every cycle" mode does its wipe in beforeListen (start of the
                    // cycle), so by here the list is already fresh — just append. The
                    // excess-trim must hold the same lock: it removes from the list, and
                    // an unguarded removal can race the snapshot copy in
                    // publishFt8MessageList().
                    ft8Messages.addAll(messages);//add messages to list
                    GeneralVariables.deleteArrayListMore(ft8Messages);//remove excess messages; FT8CN limits the total displayable messages
                }

                publishFt8MessageList();//post an immutable snapshot so the UI recomposes immediately
                mutableTimerOffset.postValue(time_sec);//this cycle's time offset


                findIncludedCallsigns(messages);//find matching messages and add to the call list

                // Clear-CQ-slot occupancy (issue #418): every kept decode (all
                // passes — deep passes see signals the fast pass missed) feeds
                // the history; a CQ idling on a now-occupied offset relocates
                // from inside this call, hold-down-paced.
                ft8TransmitSignal.recordBandActivity(messages);

                //check transmit procedure. Parse transmit procedure from message list
                //if exceeded cycle by 2 seconds, should not parse
                int autoReplyBudgetMs = Math.max(2000, GeneralVariables.lateStartTolerance);
                if (!ft8TransmitSignal.isTransmitting()
                        && !isDeep//deep passes go through the evidence-only path below
                        && (ft8SignalListener.timeSec
                        + GeneralVariables.pttDelay
                        + GeneralVariables.transmitDelay <= autoReplyBudgetMs)) {//budget scales with late-start tolerance
                    ft8TransmitSignal.parseMessageToFunction(messages);//parse messages and process
                }

                // Deep/subtraction/late passes routinely find replies the fast
                // pass missed; feed them to the sequencer as positive evidence
                // (advance/RR73/complete/enqueue — never no-reply counting, see
                // parseMessageToFunction(list, true)). When they land before TX
                // keys (~:29.0-:29.8 vs key-up ~:29.9) this upgrades the very
                // next transmission; when TX is already playing they are
                // stashed and replayed after key-up so the evidence still
                // advances the next cycle instead of being dropped.
                if (isDeep) {
                    if (ft8TransmitSignal.isTransmitting()) {
                        // UtcTimer.getSystemTime(), not System.currentTimeMillis():
                        // Ft8Message.utcTime is in the NTP/GPS-corrected time base,
                        // and the stash ages entries against it — mixing bases would
                        // shift eviction by the (possibly large) clock correction.
                        pendingSequencerDecodes.stash(messages, UtcTimer.getSystemTime());
                    } else {
                        ft8TransmitSignal.parseMessageToFunction(messages, true);
                    }
                }
                // First delivery with TX idle (typically the own-slot fast pass
                // ~1s after key-up): replay anything stashed mid-TX.
                if (!ft8TransmitSignal.isTransmitting() && !pendingSequencerDecodes.isEmpty()) {
                    ArrayList<Ft8Message> stashed =
                            pendingSequencerDecodes.drain(UtcTimer.getSystemTime());
                    if (!stashed.isEmpty()) {
                        ft8TransmitSignal.parseMessageToFunction(stashed, true);
                    }
                }

                decodeCycleState.labelsAfterPass(messages, isDeep);

                // Take the count from the same atomic update we made, not a re-read of
                // shared state a concurrently-delivering pass may have advanced since.
                int decodeCountAfterPass = decodeCycleState.countAfterPass(messages.size(), isDeep);

                //decode state, triggers marker action in spectrum display
                mutableIsDecoding.postValue(decodingMarkerAfterPass(decoded.size(), messages.size()));


                getQTHRunnable.messages = messages;
                // Guard against the ViewModel-teardown race: a late deep-decode
                // pass can deliver here after onCleared() shut the pool down,
                // and a raw execute() on a terminated pool throws
                // RejectedExecutionException on this decode thread (crash).
                SafeExecutor.tryExecute(getQTHThreadPool, getQTHRunnable);//query location via thread pool

                //this variable also notifies message list changes
                mutable_Decoded_Counter.postValue(
                        decodeCountAfterPass);//notify the UI of the total message count

                if (GeneralVariables.saveSWLMessage) {
                    databaseOpr.writeMessage(messages);//write SWL messages to database
                }
                //check QSO of SWL, and save to the QSO list in SWLQSOTable
                if (GeneralVariables.saveSWL_QSO) {
                    swlQsoList.findSwlQso(messages, ft8Messages, new SWLQsoList.OnFoundSwlQso() {
                        @Override
                        public void doFound(QSLRecord record) {
                            databaseOpr.addSWL_QSO(record);//save SWL QSO to database
                            ToastMessage.show(record.swlQSOInfo());
                        }
                    });
                }
                //find callsign-to-grid mapping from the list and add to the table
                getCallsignAndGrid(messages);

                //upload decoded spots to PSKReporter
                PskReporterSender.INSTANCE.enqueue(messages);
            }
        });

        ft8SignalListener.setOnWaveDataListener(new FT8SignalListener.OnWaveDataListener() {
            @Override
            public void getVoiceData(int duration, boolean afterDoneRemove, OnGetVoiceDataDone getVoiceDataDone) {
                hamRecorder.getVoiceData(duration, afterDoneRemove, getVoiceDataDone);
            }
        });


        ft8SignalListener.startListen();

        //start PSKReporter spot upload sender
        PskReporterSender.INSTANCE.start();

        //spectrum listener object
        spectrumListener = new SpectrumListener(hamRecorder);


        //create transmit object; callbacks: before transmit, after transmit, after QSL success.
        ft8TransmitSignal = new FT8TransmitSignal(databaseOpr, new OnDoTransmitted() {
            private boolean needControlSco() {//determine whether SCO needs to be enabled based on control mode
                return ScoPolicy.needControlSco(GeneralVariables.connectMode,
                        GeneralVariables.controlMode,
                        baseRig != null,
                        baseRig != null && baseRig.supportWaveOverCAT());
            }

            /**
             * Assert PTT (+ pause SCO) through the configured control path. Shared
             * by the FT8 TX path and the tune carrier (issue #408) so the
             * CAT/RTS/DTR branching lives in one place; with no control path
             * (VOX), this is a no-op and the audio itself keys the rig.
             */
            private void beginKeying() {
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        //if (GeneralVariables.connectMode != ConnectMode.NETWORK) stopSco();
                        if (needControlSco()) stopSco();
                        baseRig.setPTT(true);
                    }
                }
            }

            /** Release PTT (+ restore SCO); counterpart of {@link #beginKeying()}. */
            private void endKeying() {
                if (GeneralVariables.controlMode == ControlMode.CAT
                        || GeneralVariables.controlMode == ControlMode.RTS
                        || GeneralVariables.controlMode == ControlMode.DTR) {
                    if (baseRig != null) {
                        baseRig.setPTT(false);
                        //if (GeneralVariables.connectMode != ConnectMode.NETWORK) startSco();
                        if (needControlSco()) startSco();
                    }
                }
            }

            @Override
            public void onBeforeTransmit(Ft8Message message, int functionOder) {
                beginKeying();
                if (ft8TransmitSignal.isActivated()) {
                    GeneralVariables.transmitMessages.add(message);
                    //mutableTransmitMessages.postValue(GeneralVariables.transmitMessages);
                    mutableTransmitMessagesCount.postValue(1);
                }
            }

            @Override
            public void onAfterTransmit(Ft8Message message, int functionOder) {
                endKeying();
            }

            @Override
            public void onTuneKeyDown() {
                beginKeying();
            }

            @Override
            public void onTuneKeyUp() {
                endKeying();
            }

            @Override
            public void onTransmitByWifi(Ft8Message msg) {
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                    if (baseRig != null) {
                        if (baseRig.isConnected()) {
                            sendWaveDataRunnable.baseRig = baseRig;
                            sendWaveDataRunnable.message = msg;
                            //send network data packets via thread pool
                            SafeExecutor.tryExecute(sendWaveDataThreadPool, sendWaveDataRunnable);
                        }
                    }
                }
            }

            //2023-08-16 Modified by DS1UFX (based on v0.9), for (tr)uSDX audio over CAT support.
            @Override
            public boolean supportTransmitOverCAT() {
                if (GeneralVariables.controlMode != ControlMode.CAT) {
                    return false;
                }
                if (baseRig == null) {
                    return false;
                }
                if (!baseRig.isConnected() || !baseRig.supportWaveOverCAT()) {
                    return false;
                }
                return true;
            }

            @Override
            public void onTransmitOverCAT(Ft8Message msg) {//send audio message via CAT
                if (!supportTransmitOverCAT()) {
                    return;
                }
                sendWaveDataRunnable.baseRig = baseRig;
                sendWaveDataRunnable.message = msg;
                SafeExecutor.tryExecute(sendWaveDataThreadPool, sendWaveDataRunnable);
            }

        }, new OnTransmitSuccess() {//when QSO is successful
            @Override
            public void doAfterTransmit(QSLRecord qslRecord) {
                databaseOpr.addQSL_Callsign(qslRecord);//two operations: record callsign and QSL

                // QSO-complete alert (opt-in). Fires once per logged contact.
                dxAlertNotifier.notifyQsoComplete(qslRecord);

                // record to third-party service; may take some time
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        boolean cloudlogOk = false;
                        boolean qrzOk = false;
                        if (GeneralVariables.enableCloudlog){
                            cloudlogOk = ThirdPartyService.UploadToCloudLog(qslRecord);
                        }
                        if (GeneralVariables.enableQRZ){
                            qrzOk = ThirdPartyService.UploadToQRZ(qslRecord);
                        }
                        if (databaseOpr != null && (cloudlogOk || qrzOk)) {
                            ThirdPartyService.markQsoSynced(
                                    databaseOpr.getDb(), qslRecord, cloudlogOk, qrzOk);
                        }
                    }
                }).start();

                if (qslRecord.getToCallsign() != null) {//add successfully contacted zone to zone list
                    GeneralVariables.callsignDatabase.getCallsignInformation(qslRecord.getToCallsign()
                            , new OnAfterQueryCallsignLocation() {
                                @Override
                                public void doOnAfterQueryCallsignLocation(CallsignInfo callsignInfo) {
                                    GeneralVariables.addDxcc(callsignInfo.DXCC);
                                    GeneralVariables.addItuZone(callsignInfo.ITUZone);
                                    GeneralVariables.addCqZone(callsignInfo.CQZone);
                                }
                            });
                }
            }
        });


        //create meter protection controller (ALC auto-volume + SWR halt)
        meterProtectionController = new MeterProtectionController();
        meterProtectionController.setTransmitSignal(ft8TransmitSignal);
        ft8TransmitSignal.setMeterProtectionController(meterProtectionController);

        //open HTTP SERVER
        httpServer = new LogHttpServer(this, LogHttpServer.DEFAULT_PORT);
        try {
            httpServer.start();
        } catch (IOException e) {
            Log.e(TAG, "http server error:" + e.getMessage());
        }
    }

    public void setTransmitIsFreeText(boolean isFreeText) {
        if (ft8TransmitSignal != null) {
            ft8TransmitSignal.setTransmitFreeText(isFreeText);
        }
    }

    public boolean getTransitIsFreeText() {
        if (ft8TransmitSignal != null) {
            return ft8TransmitSignal.isTransmitFreeText();
        }
        return false;
    }


    /**
     * Find matching messages and add to the call list.
     *
     * @param messages Messages
     */
    private synchronized void findIncludedCallsigns(ArrayList<Ft8Message> messages) {
        Log.d(TAG, "findIncludedCallsigns: searching for followed callsigns");
        if (ft8TransmitSignal.isActivated() && ft8TransmitSignal.sequential != UtcTimer.getNowSequential()) {
            return;
        }
        int count = 0;
        for (Ft8Message msg : messages) {
            //related to my callsign, related to followed callsigns
            //if (msg.getCallsignFrom().equals(GeneralVariables.myCallsign)
            if (GeneralVariables.checkIsMyCallsign(msg.getCallsignFrom())
                    //|| msg.getCallsignTo().equals(GeneralVariables.myCallsign)
                    || GeneralVariables.checkIsMyCallsign(msg.getCallsignTo())
                    || GeneralVariables.callsignInFollow(msg.getCallsignFrom())
                    || (msg.getCallsignTo() != null && GeneralVariables.callsignInFollow(msg.getCallsignTo()))
                    || (GeneralVariables.autoFollowCQ && msg.checkIsCQ())) {//is CQ and auto-follow CQ is enabled
                //check if the callsign has not been previously contacted
                msg.isQSL_Callsign = GeneralVariables.checkQSLCallsign(msg.getCallsignFrom());
                if (!GeneralVariables.checkIsExcludeCallsign(msg.callsignFrom)) {//only add to list if not in excluded callsign prefix list
                    count++;
                    GeneralVariables.transmitMessages.add(msg);
                }
            }
        }
        GeneralVariables.deleteArrayListMore(GeneralVariables.transmitMessages);//remove excess messages
        //mutableTransmitMessages.postValue(GeneralVariables.transmitMessages);
        mutableTransmitMessagesCount.postValue(count);
    }

    /**
     * Clear the transmit message list.
     */
    public void clearTransmittingMessage() {
        GeneralVariables.transmitMessages.clear();
        mutableTransmitMessagesCount.postValue(0);
    }


    /**
     * Find the callsign-to-grid mapping from the message list.
     *
     * @param messages Message list
     */
    private void getCallsignAndGrid(ArrayList<Ft8Message> messages) {
        for (Ft8Message msg : messages) {
            if (GeneralVariables.checkFun1(msg.extraInfo)) {//check if it's a grid
                //if not in the memory table, or inconsistent, write to database
                if (!GeneralVariables.getCallsignHasGrid(msg.getCallsignFrom(), msg.maidenGrid)) {
                    databaseOpr.addCallsignQTH(msg.getCallsignFrom(), msg.maidenGrid);//write to database
                }
                GeneralVariables.addCallsignAndGrid(msg.getCallsignFrom(), msg.maidenGrid);
            }
        }
    }

    /**
     * Clear the message list.
     */
    public void clearFt8MessageList() {
        synchronized (ft8Messages) {
            ft8Messages.clear();
        }
        decodeCycleState.resetCount();
        mutable_Decoded_Counter.postValue(0);
        publishFt8MessageList();
    }

    /**
     * Whether a band change should wipe the decode list and reset the TX target.
     * True only when the auto-clear option is on AND the meter (wavelength) band
     * actually changed, so a no-op band re-select or a small in-band dial report from
     * the rig doesn't clear. Compares wavelength rather than the band-list index
     * because {@link OperationBand#getIndexByFreq} appends a new entry for any unseen
     * frequency, so the index can change within a single band. Pure decision logic,
     * unit-tested without the Android runtime.
     */
    public static boolean shouldClearOnBandChange(boolean enabled, String oldWaveLength, String newWaveLength) {
        return enabled && !java.util.Objects.equals(oldWaveLength, newWaveLength);
    }

    /**
     * Copy used to seed {@code mutableFt8MessageList} — the UI must never be
     * handed the live {@code ft8Messages} instance. Because observeAsState
     * compares structurally and every snapshot published later is content-equal
     * to the live list it was copied from, a UI seeded with the live list keeps
     * that reference forever (equal writes are skipped) and Compose iterates the
     * very list the decode thread mutates — ConcurrentModificationException
     * during recomposition. Package-visible for testing.
     */
    static ArrayList<Ft8Message> uiSeedSnapshot(ArrayList<Ft8Message> live) {
        return new ArrayList<>(live);
    }

    /**
     * Wipe the now-stale decode list and reset the TX target to CQ after a band or
     * mode change, so the decode screen and TX target reflect the new band/mode
     * instead of leftover spots from the old one (tester request). Callers gate this
     * on {@link GeneralVariables#clearOnBandModeChange} and on an actual change.
     */
    public void clearDecodesAndTarget() {
        clearFt8MessageList();
        if (ft8TransmitSignal != null && !ft8TransmitSignal.isTransmitting()) {
            ft8TransmitSignal.resetToCQ();
        }
    }

    /**
     * Publish the current decode list to the UI as a fresh snapshot.
     *
     * <p>The Compose decode screen observes {@link #mutableFt8MessageList} with
     * structural equality. Re-posting the live {@link #ft8Messages} instance after
     * mutating it in place is a no-op for recomposition — Compose is handed the same
     * object it already holds, sees no change, and the UI only refreshes when some
     * unrelated state (the 1 Hz clock) happens to trigger a recompose, up to a second
     * later. That lag is what made the Clear button feel dead: you tapped it and
     * nothing happened until the next clock tick. Posting a defensive copy gives
     * Compose a structurally distinct value, so the list updates immediately.
     */
    public void publishFt8MessageList() {
        final ArrayList<Ft8Message> snapshot;
        synchronized (ft8Messages) {
            snapshot = new ArrayList<>(ft8Messages);
        }
        mutableFt8MessageList.postValue(snapshot);
    }


    /**
     * Delete a single file
     *
     * @param fileName the filename of the file to delete
     */
    public static void deleteFile(String fileName) {
        File file = new File(fileName);
        // If the file at the given path exists and is a file, delete it directly
        if (file.exists() && file.isFile()) {
            file.delete();
        }
    }

    /**
     * Add a callsign to the followed callsign list
     *
     * @param callsign the callsign
     */
    public void addFollowCallsign(String callsign) {
        if (!GeneralVariables.followCallsign.contains(callsign)) {
            GeneralVariables.followCallsign.add(callsign);
            databaseOpr.addFollowCallsign(callsign);
        }
    }


    /**
     * Start calling the station that sent {@code message} (e.g. a station calling CQ).
     * This is the single entry point shared by the decode-list row tap and the
     * QsoSheet "Call" button: it follows the callsign, activates TX if idle, sets up
     * the QSO sequence, and requests an immediate transmit (which fires this slot if
     * we are still inside the late-start window, otherwise at the next matching slot).
     *
     * @param message the decoded message whose sender we want to call
     */
    public void callStation(Ft8Message message) {
        if (message == null || ft8TransmitSignal == null) {
            return;
        }
        // Don't hijack an in-progress transmission, and ignore rows with no usable
        // sender or our own decoded callsign (avoids accidentally "calling" ourselves).
        if (ft8TransmitSignal.isTransmitting()) {
            return;
        }
        String from = message.callsignFrom;
        if (from == null || from.trim().isEmpty()
                || from.equalsIgnoreCase(GeneralVariables.myCallsign)) {
            return;
        }

        addFollowCallsign(from);
        if (!ft8TransmitSignal.isActivated()) {
            ft8TransmitSignal.setActivated(true);
            GeneralVariables.transmitMessages.add(message);
            GeneralVariables.resetLaunchSupervision();
        }
        // Tapping a decode works one specific station: mark this run as a single
        // QSO so it stops after completion instead of idling on CQ — unless Hunt
        // or Auto-CQ after QSO is enabled. (Must come after setActivated(true),
        // which clears the flag on its deactivation path.)
        ft8TransmitSignal.beginSingleQso();
        // When the tapped message is directed at us (someone answering our CQ),
        // auto-derive the function order so we reply with RPT (order 2) instead
        // of grid (order 1). For messages not directed at us (we're initiating),
        // start at order 1 as before.
        int order = callStationOrder(message.getCallsignTo());
        ft8TransmitSignal.setTransmit(
                message.getFromCallTransmitCallsign(),
                order,
                message.extraInfo);
        ft8TransmitSignal.transmitNow();
    }

    /**
     * Determine the starting function order when the user taps a decoded message
     * to call a station. If the message is directed at us (someone answering our
     * CQ), auto-derive so we reply with the correct next step (RPT). Otherwise
     * start at order 1 (grid, initiating a new QSO).
     *
     * <p>Package-visible for testing.
     *
     * @param callsignTo message's "to" callsign (empty/CQ for broadcast messages)
     * @return function order: -1 for auto-derive, 1 for initiate
     */
    static int callStationOrder(String callsignTo) {
        if (callsignTo != null && GeneralVariables.checkIsMyCallsign(callsignTo)) {
            return -1; // auto-derive: setTransmit will compute the correct next step
        }
        return 1; // initiating: start with grid
    }

    // ===== FT8 DXpedition Hound mode =====

    /**
     * Enter DXpedition Hound mode and start calling the Fox. Mutually exclusive
     * with Hunt (auto-answer-CQ), which is disabled here. The rig should already
     * be tuned to the Fox's published dial frequency.
     *
     * @param foxCall    the Fox's base callsign (the DXpedition)
     * @param callFreqHz initial Hound TX audio frequency, 1000-4000 Hz
     */
    public void startHoundMode(String foxCall, float callFreqHz) {
        if (foxCall == null || foxCall.trim().length() < 3) return;
        if (GeneralVariables.myCallsign == null
                || GeneralVariables.myCallsign.length() < 3) return;
        GeneralVariables.houndMode = true;
        GeneralVariables.houndFoxCall = foxCall.trim().toUpperCase();
        GeneralVariables.autoFollowCQ = false;// Hound and Hunt are mutually exclusive
        GeneralVariables.resetLaunchSupervision();
        ft8TransmitSignal.startHound(GeneralVariables.houndFoxCall, callFreqHz);
    }

    /** Leave Hound mode and return to the normal idle/CQ state. */
    public void stopHoundMode() {
        GeneralVariables.houndMode = false;
        GeneralVariables.houndFoxCall = "";
        ft8TransmitSignal.setActivated(false);
        ft8TransmitSignal.resetToCQ();
    }


    /**
     * Get the followed callsign list from the database
     */
    public void getFollowCallsignsFromDataBase() {
        databaseOpr.getFollowCallsigns(new OnAfterQueryFollowCallsigns() {
            @Override
            public void doOnAfterQueryFollowCallsigns(ArrayList<String> callsigns) {
                for (String s : callsigns) {
                    if (!GeneralVariables.followCallsign.contains(s)) {
                        GeneralVariables.followCallsign.add(s);
                    }
                }
            }
        });
    }


    /**
     * Handle a TUNE tap according to the tune-method setting (issue #425).
     * Returns true when the tap was handled here — either the rig's internal
     * ATU was started (it keys its own carrier and stops by itself) or the
     * operator chose Internal but no capable rig is connected (toast, no
     * surprise carrier). Returns false when the caller should run the normal
     * low-power carrier tune.
     */
    public boolean tryStartTuneViaAtu() {
        boolean atuAvailable = baseRig != null && baseRig.isConnected()
                && baseRig.supportsAtuTune() && !baseRig.isPttOn();
        int action = TuneMethod.decide(GeneralVariables.tuneMethod, atuAvailable);
        if (action == TuneMethod.ACTION_RIG_ATU) {
            fileLog("tune: starting rig ATU (method=" + GeneralVariables.tuneMethod
                    + ", rig=" + baseRig.getName() + ")");
            baseRig.startAtuTune();
            ToastMessage.show(getStringFromResource(R.string.tune_atu_started));
            return true;
        }
        if (action == TuneMethod.ACTION_UNAVAILABLE) {
            fileLog("tune: method=INTERNAL but no ATU-capable rig connected");
            ToastMessage.show(getStringFromResource(R.string.tune_atu_unavailable));
            return true;
        }
        return false;
    }

    /**
     * Set the operating carrier frequency. Only operates if the rig is connected.
     */
    public void setOperationBand() {
        if (!isRigConnected()) {
            fileLog("setOperationBand: rig not connected, skipping");
            return;
        }

        fileLog("setOperationBand: sending USB mode, then freq=" + GeneralVariables.band
                + " in 800ms (controlMode=" + GeneralVariables.controlMode + ")");
        //set USB mode first, then set frequency
        baseRig.setUsbModeToRig();//set USB mode

        //delay 1 second before sending the second command to prevent XieGu X6100 disconnection issues
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                fileLog("setOperationBand: setting freq=" + GeneralVariables.band
                        + " (rig.getFreq=" + baseRig.getFreq() + ")");
                baseRig.setFreq(GeneralVariables.band);//set frequency
                baseRig.setFreqToRig();
            }
        }, 800);
    }

    /**
     * Switch the operating mode (FT8 &lt;-&gt; FT4). Rejected while transmitting so we never
     * end up with a half-FT8/half-FT4 QSO. Persists the mode, rebuilds the RX and TX cycle
     * timers for the new period, retunes the dial to the new mode's frequency within the
     * SAME band, and notifies UI observers.
     *
     * <p>Radio-safety: the band/waveLength NEVER changes here, only the in-band dial. Jumping
     * to a band the user hasn't tuned (ATU/antenna) could present high SWR and damage the PA.
     * If the current band has no dial in the new mode (e.g. 160m/60m have no FT4 allocation),
     * the frequency is left exactly as-is.
     *
     * @param modeId FT8Common.FT8_MODE / FT4_MODE
     * @return true if switched (or already in that mode); false if rejected mid-transmit
     */
    public boolean setOperatingMode(int modeId) {
        if (ft8TransmitSignal != null && ft8TransmitSignal.isTransmitting()) {
            return false;//don't switch mid-transmit
        }
        // Normalize once: an unknown id (e.g. a forward-compat value) degrades to FT8, and
        // we then compare/store/retune/publish the normalized id everywhere so nothing
        // operates on an unsupported mode.
        ModeProfile mode = ModeProfile.fromId(modeId);
        int normId = mode.id;
        if (normId == GeneralVariables.operatingMode) {
            return true;//no-op
        }

        // Leave any armed TX/QSO state before changing the cycle.
        if (ft8TransmitSignal != null) {
            ft8TransmitSignal.setActivated(false);
        }

        GeneralVariables.operatingMode = normId;
        databaseOpr.writeConfig("operatingMode", String.valueOf(normId), null);

        // Rebuild both cycle timers for the new period (FT8 15s -> FT4 7.5s).
        if (ft8SignalListener != null) {
            ft8SignalListener.rebuildTimer(mode);
        }
        if (ft8TransmitSignal != null) {
            ft8TransmitSignal.rebuildTimer(mode);
            // Mode switch changes the slot length and usually the dial: the
            // clear-CQ-slot occupancy history no longer applies (issue #418).
            ft8TransmitSignal.clearBandActivity();
        }

        // Retune within the SAME band to the new mode's dial (see safety note above).
        String waveLength = BaseRigOperation.getMeterFromFreq(GeneralVariables.band);
        long newFreq = OperationBand.getModeBandFreq(waveLength, normId);
        if (newFreq > 0 && newFreq != GeneralVariables.band) {
            GeneralVariables.band = newFreq;
            GeneralVariables.bandListIndex = OperationBand.getIndexByFreq(newFreq);
            databaseOpr.writeConfig("bandFreq", String.valueOf(newFreq), null);
            databaseOpr.getAllQSLCallsigns();
            GeneralVariables.mutableBandChange.postValue(GeneralVariables.bandListIndex);
            setOperationBand();//push the new dial to the rig over CAT (no-op if not connected)
        }

        // Mode changed (and possibly retuned within the band) — optionally wipe the
        // now-stale decodes + reset the TX target so the screen reflects the new mode.
        if (GeneralVariables.clearOnBandModeChange) {
            clearDecodesAndTarget();
        }

        mutableOperatingMode.postValue(normId);
        return true;
    }

    /**
     * Apply the operating mode loaded from config at startup. The RX/TX cycle timers are
     * built in the constructor (defaulting to FT8) before the persisted mode is read from
     * the DB asynchronously, so a persisted FT4 would otherwise run on FT8's 15s cycle and
     * leave the UI pill stuck on FT8. Call this once config loading completes to rebuild the
     * timers for the persisted mode and sync the LiveData. Unlike {@link #setOperatingMode},
     * it does NOT retune the dial — the band is restored separately from config.
     */
    public void applyLoadedOperatingMode() {
        ModeProfile mode = GeneralVariables.currentMode();
        if (ft8SignalListener != null) {
            ft8SignalListener.rebuildTimer(mode);
        }
        if (ft8TransmitSignal != null) {
            ft8TransmitSignal.rebuildTimer(mode);
        }
        mutableOperatingMode.postValue(mode.id);
    }

    public void setCivAddress() {
        if (baseRig != null) {
            baseRig.setCivAddress(GeneralVariables.civAddress);
        }
    }

    public void setControlMode() {
        if (baseRig != null) {
            baseRig.setControlMode(GeneralVariables.controlMode);
        }
        //Notify observers (CAT status chip visibility) of the mode change.
        GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
    }


    /**
     * Connect to rig via USB
     *
     * @param context context
     * @param port    serial port
     */
    public void connectCableRig(Context context, CableSerialPort.SerialPort port) {
        if (GeneralVariables.controlMode == ControlMode.VOX) {//if currently VOX, switch to CAT mode
            GeneralVariables.controlMode = ControlMode.CAT;
            databaseOpr.writeConfig("ctrMode", String.valueOf(ControlMode.CAT), null);
            GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
        }
        connectRig();

        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        CableConnector connector = new CableConnector(context, port, GeneralVariables.baudRate
                //, GeneralVariables.controlMode);
                , GeneralVariables.controlMode,baseRig);

        //2023-08-16 Modified by DS1UFX (based on v0.9), for (tr)uSDX audio over CAT support.
        connector.setOnCableDataReceived(new CableConnector.OnCableDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                Log.i(TAG, "call hamRecorder.doOnWaveDataReceived");
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);
        connector.connect();

        //delay 1 second before setting mode, to prevent some rigs from not responding in time
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 1000);

    }

    public void connectBluetoothRig(Context context, BluetoothDevice device) {
        // Remember this device so the SPP/CAT link can auto-reconnect on the next app launch
        // (issue #223). Centralized here so every entry point -- the Compose picker, the legacy
        // SelectBluetoothDialog, and the startup auto-connect path -- persists consistently.
        if (!device.getAddress().equals(GeneralVariables.bluetoothDeviceAddress)) {
            GeneralVariables.bluetoothDeviceAddress = device.getAddress();
            databaseOpr.writeConfig("bluetoothDeviceAddress", device.getAddress(), null);
        }

        GeneralVariables.controlMode = ControlMode.CAT;//Bluetooth control mode, only CAT control is supported
        GeneralVariables.mutableControlMode.postValue(GeneralVariables.controlMode);
        connectRig();
        if (baseRig == null) {
            return;
        }
        baseRig.setControlMode(GeneralVariables.controlMode);
        BluetoothRigConnector connector = BluetoothRigConnector.getInstance(context, device.getAddress()
                , GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(connector);

        // Route HFP audio over SCO as soon as the CAT rig is wired -- but only when a Bluetooth
        // audio profile (headset/A2DP) is actually connected. SPP-only CAT adapters have no audio
        // path, so forcing SCO + headset mode on them would be wrong and disruptive (PR #227
        // review). Without this, SCO is only (re)started as a side effect of the TX/RX cycle
        // (needControlSco()/startSco()), which never fires until a rig is connected -- so on
        // relaunch audio was dead in both directions until the user manually reconfigured
        // Bluetooth (issue #223). The BluetoothStateBroadcastReceive path remains the fallback
        // for devices whose audio profile connects after this point.
        new Handler(Looper.getMainLooper()).post(() -> {
            if (isBTConnected()) {
                setBlueToothOn();
            }
        });

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 5000);
    }

    /**
     * Connect to ICOM or XieGu X6100 series rig via network
     * @param wifiRig ICom or XieGu WiFi mode rig
     */
    public void connectWifiRig(WifiRig wifiRig) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }

        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        //currently Icom and XieGu X6100 share the same connector
        IComWifiConnector iComWifiConnector = new IComWifiConnector(GeneralVariables.controlMode
                ,wifiRig);
        iComWifiConnector.setOnWifiDataReceived(new IComWifiConnector.OnWifiDataReceived() {
            @Override
            public void OnWaveReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }

            @Override
            public void OnCivReceived(byte[] data) {

            }
        });

        iComWifiConnector.connect();
        connectRig();//assign baseRig

        baseRig.setControlMode(GeneralVariables.controlMode);
        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(iComWifiConnector);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 1000);
    }

    /**
     * Connect to FlexRadio
     *
     * @param context   context
     * @param flexRadio FlexRadio object
     */
    public void connectFlexRadioRig(Context context, FlexRadio flexRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        FlexConnector flexConnector = new FlexConnector(context, flexRadio, GeneralVariables.controlMode);
        flexConnector.setOnWaveDataReceived(new FlexConnector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });
        flexConnector.connect();
        connectRig();

        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(flexConnector);
//
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 3000);
    }

    /**
     * Connect to XieGu Radio
     *
     * @param context   context
     * @param xieguRadio X6100Radio object
     */
    public void connectXieguRadioRig(Context context, X6100Radio xieguRadio) {
        if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
            if (baseRig != null) {
                if (baseRig.getConnector() != null) {
                    baseRig.getConnector().disconnect();
                }
            }
        }
        GeneralVariables.controlMode = ControlMode.CAT;//network control mode
        X6100Connector xieguConnector = new X6100Connector(context, xieguRadio, GeneralVariables.controlMode);
        xieguConnector.setOnWaveDataReceived(new X6100Connector.OnWaveDataReceived() {
            @Override
            public void OnDataReceived(int bufferLen, float[] buffer) {
                //Log.e(TAG,String.format("data len:%d",bufferLen));
                hamRecorder.doOnWaveDataReceived(bufferLen, buffer);
            }
        });


        xieguConnector.connect();
        connectRig();
        xieguConnector.setBaseRig(baseRig);
        //receive data sent back from the rig
        xieguRadio.setOnReceiveDataListener(new X6100Radio.OnReceiveDataListener() {
            @Override
            public void onDataReceive(byte[] data) {
                baseRig.onReceiveData(data);
            }
        });


        baseRig.setOnRigStateChanged(onRigStateChanged);
        baseRig.setConnector(xieguConnector);

        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {//connection takes time, wait before setting frequency
            @Override
            public void run() {
                setOperationBand();//set carrier frequency
            }
        }, 3000);
    }


    /**
     * Create different rig models based on the instruction set
     */
    private void connectRig() {

        if (baseRig != null) {
            baseRig.onDisconnecting();
        }
        baseRig = null;
        //determine the rig type: ICOM, YAESU 2, YAESU 3
        switch (GeneralVariables.instructionSet) {
            case InstructionSet.ICOM:
                baseRig = new IcomRig(GeneralVariables.civAddress,true);
                break;
            case InstructionSet.ICOM_756:
                baseRig = new IcomRig(GeneralVariables.civAddress,false);
                break;
            case InstructionSet.YAESU_2:
                baseRig = new Yaesu2Rig();
                break;
            case InstructionSet.YAESU_847:
                baseRig = new Yaesu2_847Rig();
                break;
            case InstructionSet.YAESU_3_9:
                baseRig = new Yaesu39Rig(false);//Yaesu 3rd-gen commands, 9-digit frequency, USB mode
                break;
            case InstructionSet.YAESU_3_9_U_DIG:
                baseRig = new Yaesu39Rig(true);//Yaesu 3rd-gen commands, 9-digit frequency, DATA-USB mode
                break;
            case InstructionSet.YAESU_3_8:
                baseRig = new Yaesu38Rig();//Yaesu 3rd-gen commands, 8-digit frequency
                break;
            case InstructionSet.YAESU_3_450:
                baseRig = new Yaesu38_450Rig();//Yaesu 3rd-gen commands, 8-digit frequency
                break;
            case InstructionSet.KENWOOD_TK90:
                baseRig = new KenwoodKT90Rig();//Kenwood TK90
                break;
            case InstructionSet.YAESU_DX10:
                baseRig = new YaesuDX10Rig();//YAESU DX10 DX101
                break;
            case InstructionSet.YAESU_FT710:
                baseRig = new YaesuDX10Rig();//FT-710 reuses DX10 CAT; FT-710-specific fix is in CableSerialPort
                break;
            case InstructionSet.KENWOOD_TS590:
                baseRig = new KenwoodTS590Rig();//KENWOOD TS590
                break;
            case InstructionSet.GUOHE_Q900:
                baseRig = new GuoHeQ900Rig();//GuoHe Q900
                break;
            case InstructionSet.XIEGUG90S://XieGu, USB mode
                baseRig = new XieGuRig(GeneralVariables.civAddress);//XieGu G90S
                break;
            case InstructionSet.ELECRAFT:
                baseRig = new ElecraftRig();//ELECRAFT
                break;
            case InstructionSet.FLEX_CABLE:
                baseRig = new Flex6000Rig();//FLEX6000
                break;
            case InstructionSet.FLEX_NETWORK:
                baseRig = new FlexNetworkRig();
                break;
            case InstructionSet.XIEGU_6100_FT8CNS:
                if (GeneralVariables.connectMode == ConnectMode.NETWORK) {//only works in network mode
                    baseRig = new XieGu6100NetRig(GeneralVariables.civAddress);//XieGu 6100 FT8CNS mode
                }else{//otherwise use legacy mode
                    baseRig = new XieGu6100Rig(GeneralVariables.civAddress);//XieGu 6100
                }
                break;
            case InstructionSet.XIEGU_6100:
                baseRig = new XieGu6100Rig(GeneralVariables.civAddress);//XieGu 6100
                break;
            case InstructionSet.KENWOOD_TS2000:
                baseRig = new KenwoodTS2000Rig();//Kenwood TS2000
                break;
            case InstructionSet.DISCOVERY_TX500:
                baseRig = new DiscoveryTX500Rig();//Lab599 Discovery TX-500 (TS-2000 + DATA mode)
                break;
            case InstructionSet.WOLF_SDR_DIGU:
                baseRig = new Wolf_sdr_450Rig(false);
                break;
            case InstructionSet.WOLF_SDR_USB:
                baseRig = new Wolf_sdr_450Rig(true);
                break;
            case InstructionSet.TRUSDX:
                baseRig = new TrUSDXRig();//(tr)uSDX
                break;
            case InstructionSet.KENWOOD_TS570:
                baseRig = new KenwoodTS570Rig();//KENWOOD TS-570D
                break;
            case InstructionSet.KENWOOD_TS440:
                baseRig = new KenwoodTS440Rig();//KENWOOD TS-440S (TS-570 CAT, USB mode)
                break;
        }

        // Store the rig name for PSKReporter software string.
        // Use the user-selected model name from RigNameList (same source as the
        // Settings rig picker) rather than the Java class name, which can
        // differ (e.g. YaesuDX10Rig for FT-710).
        try {
            android.content.Context ctx = GeneralVariables.getMainContext();
            if (ctx != null) {
                RigNameList rigList = RigNameList.getInstance(ctx);
                GeneralVariables.myRigName =
                        rigList.getRigNameByIndex(GeneralVariables.modelNo).modelName;
            }
        } catch (Exception e) {
            GeneralVariables.myRigName = "";
        }

        if ((GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK)
                || ((GeneralVariables.instructionSet == InstructionSet.ICOM
                || GeneralVariables.instructionSet==InstructionSet.XIEGU_6100
                || GeneralVariables.instructionSet==InstructionSet.XIEGU_6100_FT8CNS)
                && GeneralVariables.connectMode == ConnectMode.NETWORK)) {
            hamRecorder.setDataFromLan();
        } else {
            if (GeneralVariables.controlMode != ControlMode.CAT || baseRig == null
                    || !baseRig.supportWaveOverCAT()) {
                hamRecorder.setDataFromMic();
            } else {
                hamRecorder.setDataFromLan();
            }
        }

        // Wire meter data callback for ALC auto-volume + SWR halt
        if (baseRig != null && meterProtectionController != null) {
            baseRig.setOnMeterData(new BaseRig.OnMeterData() {
                @Override
                public void onMeterUpdate(int normalizedAlc, int normalizedSwr) {
                    meterProtectionController.onMeterUpdate(normalizedAlc, normalizedSwr);
                }
            });
        }

        mutableIsFlexRadio.postValue(GeneralVariables.instructionSet == InstructionSet.FLEX_NETWORK);
        mutableIsXieguRadio.postValue(GeneralVariables.instructionSet == InstructionSet.XIEGU_6100_FT8CNS);

    }


    /**
     * Reinitialize the audio input to pick up newly connected USB audio devices.
     * Call this after a USB device attach event to switch to USB audio if available.
     */
    public void reinitializeAudioInput() {
        if (hamRecorder != null) {
            hamRecorder.reinitializeMicRecorder();
        }
    }

    /**
     * Check whether the rig is connected. Two cases: rigBaseClass not created, or serial port connection failed.
     *
     * @return whether connected
     */
    public boolean isRigConnected() {
        if (baseRig == null) {
            return false;
        } else {
            return baseRig.isConnected();
        }
    }

    /**
     * Re-trigger the current rig's CAT connection. Backs the tap-to-reconnect
     * status chip: Bluetooth often only connects on the second attempt, so this
     * reuses the connector's existing connect() path (which, for Bluetooth, runs
     * socketConnect() again). No-op when no rig/connector is configured.
     */
    public void reconnectRig() {
        if (baseRig == null || baseRig.getConnector() == null) {
            return;
        }
        setCatConnectionState(CatConnectionState.CONNECTING);
        baseRig.getConnector().connect();
    }

    /**
     * Get the serial port device list
     */
    public void getUsbDevice() {
        serialPorts =
                CableSerialPort.listSerialPorts(GeneralVariables.getMainContext());
        mutableSerialPorts.postValue(serialPorts);
    }


    public void startSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
            return;
        }
        audioManager.setBluetoothScoOn(true);
        audioManager.startBluetoothSco();//71ms
        audioManager.setSpeakerphoneOn(false);//enter headset mode
    }

    public void stopSco() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);//exit headset mode
        }

    }


    public void setBlueToothOn() {
        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (!audioManager.isBluetoothScoAvailableOffCall()) {
            //Bluetooth device does not support recording
            ToastMessage.show(getStringFromResource(R.string.does_not_support_recording));
        }

        /*
        MODE_NORMAL corresponds to music playback. For speaker output, call audioManager.setSpeakerphoneOn(true).
        For headset or earpiece, set mode to MODE_IN_CALL (pre-3.0) or MODE_IN_COMMUNICATION (3.0+).
         */
        audioManager.setMode(AudioManager.MODE_NORMAL);//178ms
        audioManager.setBluetoothScoOn(true);
        audioManager.stopBluetoothSco();
        audioManager.startBluetoothSco();//71ms
        audioManager.setSpeakerphoneOn(false);//enter headset mode

        //entering Bluetooth headset mode
        ToastMessage.show(getStringFromResource(R.string.bluetooth_headset_mode));

    }

    public void setBlueToothOff() {

        AudioManager audioManager = (AudioManager) GeneralVariables.getMainContext()
                .getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return;
        if (audioManager.isBluetoothScoOn()) {
            audioManager.setMode(AudioManager.MODE_NORMAL);
            audioManager.setBluetoothScoOn(false);
            audioManager.stopBluetoothSco();
            audioManager.setSpeakerphoneOn(true);//exit headset mode
        }
        //leaving Bluetooth headset mode
        ToastMessage.show(getStringFromResource(R.string.bluetooth_Headset_mode_cancelled));

    }


    /**
     * Check whether Bluetooth is connected
     *
     * @return whether connected
     */
    @SuppressLint("MissingPermission")
    public boolean isBTConnected() {
        // On Android 12+, getProfileConnectionState requires BLUETOOTH_CONNECT.
        // ComposeMainActivity.onCreate asks for it asynchronously, so on the first launch this
        // method may run before the user has answered the prompt — return false rather than crash.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Context ctx = GeneralVariables.getMainContext();
            if (ctx == null
                    || ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT)
                            != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        BluetoothAdapter blueAdapter = BluetoothAdapter.getDefaultAdapter();
        if (blueAdapter == null) return false;

        try {
            //Bluetooth headset, supports voice input and output
            int headset = blueAdapter.getProfileConnectionState(BluetoothProfile.HEADSET);
            int a2dp = blueAdapter.getProfileConnectionState(BluetoothProfile.A2DP);
            return headset == BluetoothAdapter.STATE_CONNECTED || a2dp == BluetoothAdapter.STATE_CONNECTED;
        } catch (SecurityException se) {
            return false;
        }
    }

    private static class GetQTHRunnable implements Runnable {
        MainViewModel mainViewModel;
        ArrayList<Ft8Message> messages;

        public GetQTHRunnable(MainViewModel mainViewModel) {
            this.mainViewModel = mainViewModel;
        }


        @Override
        public void run() {
            CallsignDatabase.getMessagesLocation(
                    GeneralVariables.callsignDatabase.getDb(), messages);
            // Entity/state flags are now populated — fire Needed-DX alerts before the UI refresh.
            mainViewModel.dxAlertNotifier.processDecodes(messages);
            mainViewModel.publishFt8MessageList();
        }
    }

    private static class SendWaveDataRunnable implements Runnable {
        BaseRig baseRig;
        //float[] data;
        Ft8Message message;

        @Override
        public void run() {
            if (baseRig != null && message != null) {
                baseRig.sendWaveData(message);//actual generated data is 12.64+0.04 seconds; 0.04 is zero-padded data
            }
        }
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        // The liveness watchdog otherwise only stops on disconnect/error/stale; if the
        // ViewModel is cleared while still "connected" the Timer thread would keep probing
        // the rig indefinitely. Tear it down here too.
        stopCatLivenessWatchdog();
        PskReporterSender.INSTANCE.stop();
        getQTHThreadPool.shutdown();
        sendWaveDataThreadPool.shutdown();
    }

    private static final String ACTION_USB_AUDIO_PERMISSION =
            "com.k1af.ft8af.USB_AUDIO_PERMISSION";

    /**
     * Request USB audio device permission if not already granted.
     * Mirrors ConfigFragment.requestUsbPermissionIfNeeded().
     */
    public void requestUsbPermissionIfNeeded(UsbDevice device) {
        if (device == null) return;
        Context context = GeneralVariables.getMainContext();
        if (context == null) return;
        UsbManager usbManager = (UsbManager) context.getSystemService(Context.USB_SERVICE);
        if (usbManager == null) return;

        if (usbManager.hasPermission(device)) {
            Log.d(TAG, "USB audio device already has permission");
            return;
        }

        Log.d(TAG, "Requesting USB permission for audio device: " + device.getProductName());
        PendingIntent permissionIntent = UsbPermissionIntentsKt.createUsbPermissionIntent(
                context, ACTION_USB_AUDIO_PERMISSION);

        BroadcastReceiver permReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context ctx, Intent intent) {
                if (ACTION_USB_AUDIO_PERMISSION.equals(intent.getAction())) {
                    boolean granted = intent.getBooleanExtra(
                            UsbManager.EXTRA_PERMISSION_GRANTED, false);
                    Log.d(TAG, "USB audio permission " + (granted ? "granted" : "denied"));
                    GeneralVariables.fileLog("USB audio permission "
                            + (granted ? "granted" : "denied"));
                    try { ctx.unregisterReceiver(this); } catch (Exception ignored) {}
                    if (granted) {
                        // The recorder was constructed before this permission
                        // existed and fell back to the built-in mic (see
                        // MicRecorder.openUsbAudioInput's hasPermission check).
                        // Now that we're allowed to open the device, rebind the
                        // input so RX actually uses the USB sound card. TX is
                        // immune to this bug because it opens the device lazily
                        // at transmit time, always after this grant.
                        reinitializeAudioInput();
                    }
                }
            }
        };

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION),
                    Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(permReceiver,
                    new IntentFilter(ACTION_USB_AUDIO_PERMISSION));
        }
        usbManager.requestPermission(device, permissionIntent);
    }

}