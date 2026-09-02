package com.k1af.ft8af.rigs;

/**
 * IcomRig is a generic Icom rig control class. For WiFi mode, actual control is via IComWifiConnector (extends WifiConnector).
 * IComWifiConnector contains IComWifiRig for specific rig operations.
 */

import android.util.Log;

import com.k1af.ft8af.Ft8Message;
import com.k1af.ft8af.GeneralVariables;
import android.os.SystemClock;
import com.k1af.ft8af.R;
import com.k1af.ft8af.connector.ConnectMode;
import com.k1af.ft8af.database.ControlMode;
import com.k1af.ft8af.ft8transmit.GenerateFT8;
import com.k1af.ft8af.icom.IComPacketTypes;
import com.k1af.ft8af.ui.ToastMessage;

import java.util.Timer;
import java.util.TimerTask;

public class IcomRig extends BaseRig {
    private static final String TAG = "IcomRig";

    /**
     * How often the frequency-poll timer asks the rig for its current dial. Sized
     * to the same 2 s cadence Yaesu38Rig / KenwoodTS590Rig / Elecraft use: fast
     * enough that a user turning the VFO on the rig sees the app follow within
     * a slot, slow enough that even a chatty CI-V stream stays comfortably below
     * the rig's command rate. See {@link #startReadFreqTimer}.
     */
    static final long READ_FREQ_PERIOD_MS = 2000;
    /**
     * Delay before the timer's first tick. This is only a cheap way to keep the
     * poll off the connect path — it is measured from {@code IcomRig}
     * construction, which happens before the connector logs in, so it cannot on
     * its own guarantee the connect-time handshake is done. The gate that
     * actually does that is {@link #READ_FREQ_CONNECT_SETTLE_MS}, applied per
     * tick in {@link #mayPollDial}. See {@link #startReadFreqTimer}.
     */
    static final long READ_FREQ_START_DELAY_MS = 2000;
    /**
     * How long after the link comes up the dial poll stays quiet, when the
     * connect-time frequency push can't be observed landing.
     *
     * <p>{@link #READ_FREQ_START_DELAY_MS} alone is not enough, because a
     * {@code Timer} start delay runs from {@code IcomRig} construction and the
     * rig is constructed in {@code MainViewModel.connectRig} <em>before</em> the
     * connector logs in. The handshake we need to clear finishes at
     * {@code onConnected + 1500 ms} (the posted {@code setOperationBand})
     * {@code + 800 ms} (its delayed FA write) — i.e. always later than 2000 ms
     * after construction, no matter how fast the login is. Polling into that
     * window reads the rig's power-on dial, which {@code onFreqChanged} then
     * adopts as {@code commandedBandHz}, and the connect-time retune "preserves"
     * the wrong frequency.
     *
     * <p>2500 ms covers that 2300 ms handshake with margin, measured from when
     * this timer first observes the link up.
     */
    static final long READ_FREQ_CONNECT_SETTLE_MS = 2500;

    private final int ctrAddress = 0xE0;//receive address, default 0xE0; rig reply can also be 0x00
    private byte[] dataBuffer = new byte[0];//data buffer
    private int alc = 0;
    private int swr = 0;
    private boolean alcMaxAlert = false;
    private boolean swrAlert = false;
    /**
     * How this rig schedules its repeating polls. A seam so a test can verify
     * that the constructor schedules the dial poll (task, delay, period, fixed
     * delay versus fixed rate) and that {@link #onDisconnecting()} cancels it,
     * without a real {@link Timer} and without sleeping — the tick body alone
     * being tested would stay green if the constructor stopped scheduling it.
     */
    interface PollScheduler {
        /** Repeating task with a fixed delay between the end of one run and the next. */
        Cancellable scheduleFixedDelay(Runnable task, long delayMs, long periodMs);

        /** Repeating task at a fixed rate (catches up missed runs). */
        Cancellable scheduleFixedRate(Runnable task, long delayMs, long periodMs);
    }

    /** A scheduled poll that can be stopped. */
    interface Cancellable {
        void cancel();
    }

    /** The production scheduler: one {@link Timer} thread per poll. */
    static final class TimerScheduler implements PollScheduler {
        @Override
        public Cancellable scheduleFixedDelay(Runnable task, long delayMs, long periodMs) {
            Timer timer = new Timer();
            timer.schedule(wrap(task), delayMs, periodMs);
            return () -> { timer.cancel(); timer.purge(); };
        }

        @Override
        public Cancellable scheduleFixedRate(Runnable task, long delayMs, long periodMs) {
            Timer timer = new Timer();
            timer.scheduleAtFixedRate(wrap(task), delayMs, periodMs);
            return () -> { timer.cancel(); timer.purge(); };
        }

        private static TimerTask wrap(Runnable task) {
            return new TimerTask() {
                @Override
                public void run() {
                    task.run();
                }
            };
        }
    }

    private final PollScheduler scheduler;
    private Cancellable meterPoll;//querying meters while keyed
    private Cancellable readFreqPoll;//polling frequency (rig->app dial follow)
    //When this timer first saw the link up (SystemClock.elapsedRealtime(), so a
    //wall-clock correction cannot stretch or shrink the window), or 0 while it is
    //down. The poll gate is relative to THIS, not to construction — see
    //READ_FREQ_CONNECT_SETTLE_MS. Volatile: written on the Timer thread, cleared on
    //the disconnect thread (onDisconnecting).
    private volatile long connectedSinceMs = 0L;
    //GeneralVariables.operatorDialDeliveredAtMs as it stood when the window was
    //taken. "The connect-time push landed on THIS connection" is then "the stamp
    //changed since", which no clock step in either direction can fake or hide —
    //comparing the wall-clock stamp against a connect time could.
    private volatile long deliveredStampAtConnect = 0L;
    //The connector's connection generation connectedSinceMs was taken in. A link
    //that dropped and reopened entirely between two ticks (CableConnector retries
    //within 500 ms; ticks are 2 s apart) never shows this timer a "down" sample,
    //so without this the old settle window would be inherited and the first tick
    //of the new session would poll straight into its connect handshake.
    private volatile int settleGeneration = -1;

    private boolean oldVersion = false;//for older rigs that may not support SWR query
    //private boolean isPttOn = false;

    @Override
    public void setPTT(boolean on) {
        // isPttOn() is what the poll timers read to stay quiet during TX, so
        // publish it BEFORE the key-down goes out and only AFTER the unkey is on
        // the wire. With the flag flipped to false first, a tick landing between
        // the flip and the PTT-off command saw "not transmitting" and could send
        // a frequency read ahead of the unkey — the mid-transmit CI-V read the
        // gate exists to prevent (Copilot review on #789).
        if (on) {
            super.setPTT(true);
        }
        alcMaxAlert = false;
        swrAlert = false;
        try {
            dispatchPtt(on);
        } finally {
            if (!on) {
                super.setPTT(false);
            }
        }
    }

    /** The data-mode fix-up and the PTT command itself; see {@link #setPTT}. */
    private void dispatchPtt(boolean on) {
        if (on) {
            //fix connection mode: 0x03=WLAN, 0x01=USB, 0x02=USB+MIC, ensuring audio can be sent to rig
            if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x03));
            } else if (GeneralVariables.connectMode == ConnectMode.USB_CABLE) {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x01));
            } else {
                sendCivData(IcomRigConstant.setConnectorDataMode(ctrAddress, getCivAddress(), (byte) 0x02));
            }
        }

        if (getConnector() != null) {
            if (GeneralVariables.connectMode == ConnectMode.NETWORK) {
                getConnector().setPttOn(on);
                return;
            }

            switch (getControlMode()) {
                case ControlMode.CAT://via CIV command
                    getConnector().setPttOn(IcomRigConstant.setPTTState(ctrAddress, getCivAddress()
                            , on ? IcomRigConstant.PTT_ON : IcomRigConstant.PTT_OFF));
                    break;
                //case ControlMode.NETWORK:
                case ControlMode.RTS:
                case ControlMode.DTR:

                    getConnector().setPttOn(on);
                    break;
            }
        }
    }

    @Override
    public boolean isConnected() {
        if (getConnector() == null) {
            return false;
        }
        return getConnector().isConnected();
    }

    @Override
    public void setUsbModeToRig() {
        if (getConnector() != null) {
            //Since older ICOM rigs may not support USB-D, we first set USB mode, then switch to USB-D mode.
            // This way, if USB-D is not supported, the USB-D command is simply ignored and the rig stays in USB mode.
            //getConnector().sendData(IcomRigConstant.setOperationMode(ctrAddress
            // , getCivAddress(), IcomRigConstant.USB));//usb
            getConnector().sendData(IcomRigConstant.setOperationDataMode(ctrAddress
                    , getCivAddress(), IcomRigConstant.USB));//usb-d
        }
    }

    private void sendCivData(byte[] data) {
        if (getConnector() != null) {
            getConnector().sendData(data);
        }
    }

    @Override
    public boolean supportsAtuTune() {
        return true;
    }

    @Override
    public void startAtuTune() {
        sendCivData(IcomRigConstant.startAtuTune(ctrAddress, getCivAddress()));
    }

    @Override
    public void setFreqToRig() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.setOperationFrequency(ctrAddress
                    , getCivAddress(), getFreq()));
        }
    }

    /**
     * Find command header. Returns -1 if not found, otherwise returns position of first FE FE.
     *
     * @param data data
     * @return position
     */
    private int getCommandHead(byte[] data) {
        if (data.length < 2) return -1;
        for (int i = 0; i < data.length - 1; i++) {
            if (data[i] == (byte) 0xFE && data[i + 1] == (byte) 0xFE) {
                return i;
            }
        }
        return -1;
    }

    @Override
    public void sendWaveData(Ft8Message message) {//send audio data to rig, for network mode
        if (getConnector() != null) {//pass generated audio data to Connector
            float[] data = GenerateFT8.generateFt8(message, GeneralVariables.getBaseFrequency()
                    , 12000);//ICOM rig audio sample rate is 12000
            if (data == null) {
                setPTT(false);
                return;
            }
            getConnector().sendWaveData(data);
        }
    }

    private void analysisCommand(byte[] data) {
        int headIndex = getCommandHead(data);
        if (headIndex == -1) {//no command header found
            return;
        }
        IcomCommand icomCommand;
        if (headIndex == 0) {
            icomCommand = IcomCommand.getCommand(ctrAddress, getCivAddress(), data);
        } else {
            byte[] temp = new byte[data.length - headIndex];
            System.arraycopy(data, headIndex, temp, 0, temp.length);
            icomCommand = IcomCommand.getCommand(ctrAddress, getCivAddress(), temp);
        }
        if (icomCommand == null) {
            return;
        }

        //currently only responding to frequency and mode messages
        switch (icomCommand.getCommandID()) {

            case IcomRigConstant.CMD_SEND_FREQUENCY_DATA://received frequency data
            case IcomRigConstant.CMD_READ_OPERATING_FREQUENCY:
                //get frequency
                //ToastMessage.show(byteToStr(icomCommand.getData(false)));
                setFreq(icomCommand.getFrequency(false));
                break;
            case IcomRigConstant.CMD_SEND_MODE_DATA://received mode data
            case IcomRigConstant.CMD_READ_OPERATING_MODE:
                break;
            case IcomRigConstant.CMD_READ_METER://read meter//this command is only implemented in network mode; serial port support may be added later
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_ALC) {
                    alc = IcomRigConstant.twoByteBcdToInt(icomCommand.getData(true));
                }
                if (icomCommand.getSubCommand() == IcomRigConstant.CMD_READ_METER_SWR) {
                    swr = IcomRigConstant.twoByteBcdToInt(icomCommand.getData(true));
                }
                showAlert();//check if meter value is in alert range
                notifyMeterData(alc, swr);
                break;
            case IcomRigConstant.CMD_CONNECTORS:
                break;

        }
    }

    private void showAlert() {
        if ((swr >= IcomRigConstant.swr_alert_max) && GeneralVariables.swr_switch_on) {
            if (!swrAlert) {
                swrAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.swr_high_alert));
            }
        } else {
            swrAlert = false;
        }
        if ((alc > IcomRigConstant.alc_alert_max) && GeneralVariables.alc_switch_on) {//ALC alert
            if (!alcMaxAlert) {
                alcMaxAlert = true;
                ToastMessage.show(GeneralVariables.getStringFromResource(R.string.alc_high_alert));
            }
        } else {
            alcMaxAlert = false;
        }

    }

    @Override
    public void onReceiveData(byte[] data) {
        //ToastMessage.show(byteToStr(data));

        // Append to any partial command buffered from a previous callback, then
        // process every complete command (each ending in 0xFD) and keep only the
        // trailing incomplete bytes for next time. See CivFrameSplitter for why the
        // previous hand-rolled reassembly dropped fragments and injected stray bytes.
        CivFrameSplitter.Result result = CivFrameSplitter.split(dataBuffer, data);
        for (byte[] command : result.commands) {
            analysisCommand(command);
        }
        dataBuffer = result.remainder;
    }

    @Override
    public void readFreqFromRig() {
        if (getConnector() != null) {
            getConnector().sendData(IcomRigConstant.setReadFreq(ctrAddress, getCivAddress()));
        }
    }

    @Override
    public String getName() {
        return "ICOM series";
    }

    public void startMeterTimer() {
        meterPoll = scheduler.scheduleFixedRate(() -> {
            if (isPttOn() && !oldVersion) {//measure when PTT is pressed, and rig is not an old version
                sendCivData(IcomRigConstant.getSWRState(ctrAddress, getCivAddress()));
                sendCivData(IcomRigConstant.getALCState(ctrAddress, getCivAddress()));
            }
        }, 0, IComPacketTypes.METER_TIMER_PERIOD_MS);
    }

    /**
     * Poll the rig for its current dial every {@link #READ_FREQ_PERIOD_MS} so the
     * app follows the operator turning the VFO on the rig itself (issue #753
     * follow-up: after the CI-V address hex fix the app could command the rig,
     * but rig&rarr;app dial updates still didn't land — every other CAT rig in
     * this codebase runs its own frequency poll and IcomRig was the odd one out
     * relying solely on {@link CatLiveness}'s 3 s liveness poll, which stops
     * hard on an 8 s quiet timeout).
     *
     * <p>Suppressed while transmitting: an unsolicited CI-V read during TX can
     * clobber the meter poll's SWR/ALC reads that {@link #startMeterTimer} runs
     * at 500 ms, and a rig can't turn its VFO while keyed anyway. Follows the
     * {@link ReadTaskAction} pattern already used by
     * {@link Yaesu38Rig} / {@link KenwoodTS590Rig} / {@link ElecraftRig}.
     */
    public void startReadFreqTimer() {
        // Fixed delay, not fixed rate: the latter catches up missed executions
        // after a long tick, a GC pause or a device suspend, which would fire a
        // burst of back-to-back CI-V reads. A polling backlog has no value and
        // can coalesce CAT traffic; the sibling pollers (Yaesu38Rig,
        // KenwoodTS590Rig) use fixed delay too.
        readFreqPoll = scheduler.scheduleFixedDelay(
                this::runReadFreqTick, READ_FREQ_START_DELAY_MS, READ_FREQ_PERIOD_MS);
    }

    /** When this timer first saw the link up (0 while down); for the scheduling test. */
    long connectedSinceMs() {
        return connectedSinceMs;
    }

    /**
     * One tick of the frequency-poll timer, factored out so the decision path
     * can be exercised from tests without waiting on wall-clock time. Package-
     * private for the same reason.
     */
    void runReadFreqTick() {
        runReadFreqTick(SystemClock.elapsedRealtime(), GeneralVariables.operatorDialDeliveredAtMs);
    }

    /**
     * The tick body, with the two time-dependent inputs passed in so tests can
     * drive the connect-settle gate without sleeping.
     *
     * <p>Everything is wrapped in a catch-all: an unchecked exception thrown out
     * of a {@link TimerTask} kills its {@link Timer} thread outright, which would
     * silently end dial polling for the rest of the session. The sibling rig
     * polls ({@link Yaesu38Rig}, {@link KenwoodTS590Rig}) guard theirs the same
     * way; this one logs the throwable rather than just its message so the stack
     * trace survives.
     *
     * @param nowMs             a monotonic clock ({@code SystemClock.elapsedRealtime()})
     * @param dialDeliveredAtMs {@code GeneralVariables.operatorDialDeliveredAtMs}
     */
    void runReadFreqTick(long nowMs, long dialDeliveredAtMs) {
        try {
            boolean connected = isConnected();
            if (!connected) {
                //Link down: forget the settle window so a reconnect earns a fresh one.
                connectedSinceMs = 0L;
                return;
            }
            //Also start a fresh window when the connector's link came up again since
            //the window was taken, even though every tick saw it "up": the cable
            //auto-reconnect reopens within 500 ms, so a drop and reopen can fall
            //entirely between two 2 s ticks and the new session's connect handshake
            //would otherwise be polled into. See settleGeneration.
            int generation = connectionGeneration();
            if (connectedSinceMs == 0L || generation != settleGeneration) {
                connectedSinceMs = nowMs;
                settleGeneration = generation;
                deliveredStampAtConnect = dialDeliveredAtMs;
            }
            switch (ReadTaskAction.decide(true, isPttOn())) {
                case SKIP:
                    return;
                case READ_METERS:
                    //Meter reads are handled by the dedicated meter timer (500 ms
                    //cadence); don't duplicate them here.
                    return;
                case READ_FREQ:
                    if (!mayPollDial(nowMs, connectedSinceMs, deliveredStampAtConnect,
                            dialDeliveredAtMs)) {
                        return;
                    }
                    readFreqFromRig();
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "readFreq tick failed", e);
        }
    }

    /**
     * Whether the connect-time frequency handshake is far enough along that a
     * dial reading can be trusted as the operator's dial rather than the rig's
     * power-on value.
     *
     * <p>Two ways to clear it, because neither alone is sufficient:
     * <ul>
     *   <li>{@code dialDeliveredAtMs} changed since this window was taken <em>and</em>
     *       is nonzero — {@code setOperationBand}'s delayed FA write actually reached
     *       the wire on <em>this</em> connection, so the rig is on the frequency we
     *       asked for and there is nothing left to wait for. Session state, not a
     *       clock comparison: the stamp is wall clock and a correction could
     *       otherwise make a previous session's stamp look newer than the
     *       connection (or this session's look older). Nonzero, because
     *       {@code GeneralVariables.operatorChoseDial} resets the stamp to 0 when
     *       the operator picks a new dial: that is a change, but it means a NEW
     *       write is pending, not that one landed — polling then would read the
     *       rig's pre-command dial.</li>
     *   <li>{@link #READ_FREQ_CONNECT_SETTLE_MS} elapsed since the link came up —
     *       the fallback for when that push never lands at all, e.g. RetunePolicy
     *       suppressed it as redundant on a flapping reconnect. Without this the
     *       poll could stay off for the whole session and the dial-follow feature
     *       this timer exists for would silently never run.</li>
     * </ul>
     *
     * <p>Pure and static so the gate is unit-testable without a Timer or a clock.
     *
     * @param nowMs                   a monotonic clock, same base as {@code connectedSinceMs}
     * @param connectedSinceMs        when this timer first saw the link up, or 0 if down
     * @param deliveredStampAtConnect {@code GeneralVariables.operatorDialDeliveredAtMs}
     *                                as it stood when the window was taken
     * @param dialDeliveredAtMs       {@code GeneralVariables.operatorDialDeliveredAtMs} now
     */
    static boolean mayPollDial(long nowMs, long connectedSinceMs,
                               long deliveredStampAtConnect, long dialDeliveredAtMs) {
        if (connectedSinceMs <= 0L) {
            return false;
        }
        if (dialDeliveredAtMs != 0L && dialDeliveredAtMs != deliveredStampAtConnect) {
            return true;
        }
        return nowMs - connectedSinceMs >= READ_FREQ_CONNECT_SETTLE_MS;
    }

    /** The connector's connection generation, or -1 with no connector. */
    private int connectionGeneration() {
        return getConnector() == null ? -1 : getConnector().connectionGeneration();
    }

    @Override
    public void onDisconnecting() {
        // Cancel our timers so a reconnect (which builds a fresh IcomRig via
        // MainViewModel.connectRig -> baseRig.onDisconnecting) doesn't leak the
        // previous instance's polling task or double-poll after re-connect.
        if (readFreqPoll != null) {
            readFreqPoll.cancel();
            readFreqPoll = null;
        }
        //A reconnect must serve a fresh settle window, not inherit this one's.
        connectedSinceMs = 0L;
        if (meterPoll != null) {
            meterPoll.cancel();
            meterPoll = null;
        }
    }


    public String getFrequencyStr() {
        return BaseRigOperation.getFrequencyStr(getFreq());
    }


    public IcomRig(int civAddress, boolean newRig) {
        this(civAddress, newRig, new TimerScheduler());
    }

    /** Test seam: the polls go through {@code scheduler} instead of real Timers. */
    IcomRig(int civAddress, boolean newRig, PollScheduler scheduler) {
        Log.d(TAG, "IcomRig: Create.");
        this.scheduler = scheduler;
        this.oldVersion = !newRig;//some older rigs do not support SWR query
        setCivAddress(civAddress);
        startMeterTimer();
        startReadFreqTimer();
    }
}
