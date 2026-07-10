package com.k1af.ft8af.timer;
/**
 * UtcTimer class, used to trigger actions at the start of each FT8 QSO cycle.
 * FT8 QSOs require clock synchronization, based on UTC time, with a 15-second cycle (FT4 is 7.5 seconds).
 * This class uses Timer and TimerTask to implement timed action triggers.
 * Since FT8 requires clock synchronization (second precision) and actions trigger at the start of each cycle,
 * the current approach uses a 100ms heartbeat to detect whether we are at the start of a cycle (UTC time modulo cycle seconds).
 * If so, the doHeartBeatTimer callback is invoked. To prevent duplicate actions, a 1-second wait follows each trigger before entering the next heartbeat cycle (since modulo is by seconds).
 * IMPORTANT! To prevent callback actions from taking too long and affecting the next action trigger,
 * all callbacks are invoked using multithreading. Be careful about thread safety when using them.
 * <p>
 * @author BG7YOZ
 * @date 2022.5.7
 */

import android.annotation.SuppressLint;

import com.k1af.ft8af.ui.ToastMessage;

import org.apache.commons.net.ntp.NTPUDPClient;
import org.apache.commons.net.ntp.TimeInfo;

import java.io.IOException;
import java.net.InetAddress;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.TimeZone;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;


public class UtcTimer {
    /** Slot (cycle) length in milliseconds: 15000 FT8, 7500 FT4, 3750 FT2. */
    private final int slotMillis;
    private final boolean doOnce;
    private final OnUtcTimer onUtcTimer;


    private long utc;
    public static volatile int delay = 0;//total clock delay (milliseconds)
    private volatile boolean running = false;//determines whether to trigger cycle actions

    private final Timer secTimer = new Timer();
    private final Timer heartBeatTimer = new Timer();
    private int time_sec = 0;//time offset
    private final ExecutorService cachedThreadPool = Executors.newCachedThreadPool();
    private final Runnable doSomething = new Runnable() {
        @Override
        public void run() {
            onUtcTimer.doOnSecTimer(utc);
        }
    };
    private final ExecutorService heartBeatThreadPool = Executors.newCachedThreadPool();
    private final Runnable doHeartBeat = new Runnable() {
        @Override
        public void run() {
            onUtcTimer.doHeartBeatTimer(utc);
        }
    };

    /**
     * Class method. Get the string representation of UTC time.
     *
     * @param time the time value
     * @return String displaying UTC time as a string
     */
    @SuppressLint("DefaultLocale")
    public static String getTimeStr(long time) {
        long curtime = time / 1000;
        long hour = ((curtime) / (60 * 60)) % 24;//hours
        long sec = (curtime) % 60;//seconds
        long min = ((curtime) % 3600) / 60;//minutes
        return String.format("UTC : %02d:%02d:%02d", hour, min, sec);
    }

    /**
     * Display UTC time in HHMMSS format
     *
     * @param time
     * @return
     */
    @SuppressLint("DefaultLocale")
    public static String getTimeHHMMSS(long time) {
        long curtime = time / 1000;
        long hour = ((curtime) / (60 * 60)) % 24;//hours
        long sec = (curtime) % 60;//seconds
        long min = ((curtime) % 3600) / 60;//minutes
        return String.format("%02d%02d%02d", hour, min, sec);
    }

    public static String getYYYYMMDD(long time) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return simpleDateFormat.format(new Date(time));
    }

    public static String getDatetimeStr(long time) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return simpleDateFormat.format(new Date(time));
    }

    public static String getDatetimeYYYYMMDD_HHMMSS(long time) {
        @SuppressLint("SimpleDateFormat")
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd-HHmmss");
        simpleDateFormat.setTimeZone(TimeZone.getTimeZone("GMT"));
        return simpleDateFormat.format(new Date(time));
    }

    /**
     * Constructor for the clock trigger. Requires specifying the clock cycle period, typically 15 seconds or 7.5 seconds.
     * The cycle parameter is an int in milliseconds.
     * The cycle-boundary check runs on a fast 10ms task, so cycle (doOnSecTimer) callbacks should be as concise as
     * possible and must complete before the next tick, to prevent thread stacking and performance impact.
     * The heartbeat callback (doHeartBeatTimer) runs on a separate 1000ms task and is independent of whether cycle
     * actions are triggering (running==false); as long as the UtcTimer instance exists it runs (convenient for displaying clock data).
     * This trigger requires calling the delete function to fully stop (heartbeat actions also stop).
     *
     * @param slotMillis clock cycle period in milliseconds, e.g.: 15 seconds = 15000, 7.5 seconds = 7500, FT2 = 3750
     * @param doOnce     whether to trigger only once
     * @param onUtcTimer callback functions, including heartbeat callback and cycle start action callback
     */
    public UtcTimer(int slotMillis, boolean doOnce, OnUtcTimer onUtcTimer) {
        this.slotMillis = slotMillis;
        this.doOnce = doOnce;
        this.onUtcTimer = onUtcTimer;

        //initialize Timer tasks
        //secTask checks the cycle boundary every 10ms; heartBeatTask fires the display callback every 1000ms.

        secTimer.schedule(secTask(), 0, 10);
        heartBeatTimer.schedule(heartBeatTask(), 0, 1000);
    }

    /**
     * Define the clock-triggered action.
     * The clock cycle is typically 15 seconds or 7.5 seconds; the cycle parameter is an int in milliseconds.
     * The cycle-boundary check runs on a fast 10ms task, so callbacks should be as concise as possible
     * and must complete before the next tick, to prevent thread stacking and performance impact.
     * The heartbeat callback runs on a separate 1000ms task and is independent of whether cycle actions are
     * triggering (running==false); as long as the UtcTimer instance exists it runs (convenient for displaying clock data).
     *
     * @return TimerTask the action instance
     */


    private TimerTask heartBeatTask() {
        return new TimerTask() {
            @Override
            public void run() {
                //heartbeat action
                doHeartBeatEvent(onUtcTimer);
            }
        };
    }

    /** Detection window (ms) for opening a slot — see {@link #isCycleBoundary}. */
    private static final int SLOT_OPEN_WINDOW_MS = 100;

    /**
     * Whether {@code utc} lands on a cycle (slot) boundary for a slot of {@code slotMillis}
     * milliseconds, anchored to the Unix epoch.
     *
     * <p>A slot opens in the {@link #SLOT_OPEN_WINDOW_MS}-millisecond window that starts at
     * each epoch-anchored multiple of {@code slotMillis}. The 100 ms window is wide enough
     * that the 10 ms heartbeat reliably lands at least one tick inside it, and the 1 s
     * post-fire pause in {@link #secTask()} prevents a second fire within the same slot.
     *
     * <p>This works in milliseconds rather than tenths of a second so a slot length that
     * isn't a whole number of tenths — FT2's 3750 ms — sits on a clean grid. For FT8
     * (15000) and FT4 (7500) the firing instants are byte-for-byte identical to the old
     * tenths predicate {@code (utc/100) % (slotMillis/100) == 0}: both fire exactly in
     * {@code [k*slotMillis, k*slotMillis + 99]}. The earlier tenths form rounded FT2 to a
     * 3.8 s grid (37.5 tenths isn't an integer), which drifted off the absolute 3.75 s slot
     * grid that {@link #sequential(long, int)} and the transmit late-start clip math use, so
     * a normal FT2 transmit fired part-way into its slot and had its leading Costas sync
     * array clipped — audible on the air but undecodable.
     *
     * @param utc        system time in ms (epoch + {@link #delay})
     * @param offsetMs   manual time offset in ms (see {@link #setTime_sec(int)})
     * @param slotMillis slot period in milliseconds (see ModeProfile.slotMillis)
     * @return true on the heartbeat ticks that open a slot
     */
    public static boolean isCycleBoundary(long utc, int offsetMs, int slotMillis) {
        if (slotMillis <= 0) return false;
        long intoSlot = ((utc - offsetMs) % slotMillis + slotMillis) % slotMillis;
        return intoSlot < SLOT_OPEN_WINDOW_MS;
    }

    private TimerTask secTask() {
        return new TimerTask() {


            @Override
            public void run() {

                try {
                    utc = getSystemTime();//get current UTC time
                    //running determines whether to trigger cycle actions
                    //time_sec is the time offset
                    if (running && isCycleBoundary(utc, time_sec, slotMillis)) {
                        //cycle action
                        //IMPORTANT! the cycle callback (doSomething -> doOnSecTimer) must not perform time-consuming operations and must complete within the cycle interval, otherwise thread backlog may occur and affect performance.
                        cachedThreadPool.execute(doSomething);//use thread pool for invocation to reduce system overhead
                        //thread.run();

                        //if only executing the trigger action once
                        if (doOnce) {
                            running = false;
                            return;
                        }

                        //wait 1 second to prevent duplicate triggering
                        Thread.sleep(1000);
                    }


                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        };
    }

    /**
     * Action when heartbeat triggers. Called by Timer; this function is written for readability. Action executes in a newly created thread.
     *
     * @param onUtcTimer the clock trigger callback function
     */
    private void doHeartBeatEvent(OnUtcTimer onUtcTimer) {
        //heartbeat action
        heartBeatThreadPool.execute(doHeartBeat);
//        new Thread(new Runnable() {
//            @Override
//            public void run() {
//                //IMPORTANT! doHeartBeatTimer must not perform time-consuming operations and must complete within the heartbeat interval, otherwise thread backlog may occur and affect performance.
//                onUtcTimer.doHeartBeatTimer(utc);
//            }
//        }).start();
    }


    public void stop() {
        running = false;
    }

    public void start() {
        running = true;
    }

    public boolean isRunning() {
        return running;
    }

    public void delete() {
        secTimer.cancel();
        heartBeatTimer.cancel();
        cachedThreadPool.shutdownNow();
        heartBeatThreadPool.shutdownNow();
    }

    /**
     * Set the time offset; positive values shift forward
     *
     * @param time_sec the offset amount
     */
    public void setTime_sec(int time_sec) {
        this.time_sec = time_sec;
    }

    /**
     * Get the time offset
     *
     * @return time offset value (milliseconds)
     */
    public int getTime_sec() {
        return time_sec;
    }

    public long getUtc() {
        return utc;
    }

    /**
     * Calculate the time sequence based on UTC time, for an explicit slot length.
     * For FT8 (slotMillis=15000) this equals the legacy ((utc/1000)/15)%2; FT4
     * (slotMillis=7500) alternates twice as often, matching its 7.5s cycle.
     *
     * @param utc       UTC time (ms)
     * @param slotMillis cycle length in ms (15000 FT8, 7500 FT4)
     * @return sequence: 0 or 1
     */
    public static int sequential(long utc, int slotMillis) {
        return (int) ((utc / slotMillis) % 2);
    }

    /**
     * Calculate the time sequence based on UTC time, using the current operating mode's
     * cycle length.
     *
     * @param utc UTC time
     * @return sequence: 0 or 1
     */
    public static int sequential(long utc) {
        return sequential(utc, com.k1af.ft8af.GeneralVariables.currentMode().slotMillis);
    }

    public static int getNowSequential() {
        return sequential(getSystemTime());
    }

    public static long getSystemTime() {
        return delay + System.currentTimeMillis();
    }

    /**
     * The clock offset (ms) to add to {@link System#currentTimeMillis()} so the app
     * clock matches an authoritative UTC source — an NTP server's transmit timestamp.
     * A positive result means the device clock is behind the reference and must be
     * shifted forward. Written to {@link #delay} by {@link #syncTime}.
     *
     * <p><b>The full offset is returned</b>, not just its remainder within one FT8
     * cycle. Storing only {@code offset % 15000} (as {@code syncTime} did before)
     * keeps decode/TX cycle alignment intact — every slot length (15000, 7500, 3750)
     * divides 15000, so the firing instants of {@link #isCycleBoundary} are unchanged
     * — but it discards the whole-cycle part of the correction. A device clock more
     * than one cycle out of sync would then leave {@link #getSystemTime()}, and every
     * UTC value derived from it (the on-screen clock, QSO log start/end times, ADIF
     * export times, and PSKReporter {@code flowStartSeconds}), wrong by a whole
     * multiple of 15 s — silently, because RX/TX still work. This mirrors the GPS
     * clock-discipline path ({@code GpsClockUpdater.gpsClockOffsetMs}) and the manual
     * correction, both of which write the full offset.
     *
     * <p>Clamped to the {@code int} range so an absurdly wrong device clock (more than
     * ~24.8 days off) can't overflow the {@code int} field into a bogus small value.
     *
     * <p>Package-visible via {@code public static} for pure-JVM testing.
     *
     * @param referenceUtcMs authoritative UTC "now" in ms (e.g. NTP transmit time)
     * @param deviceNowMs    the device wall clock at the same instant
     *                       ({@code System.currentTimeMillis()})
     * @return the full offset in ms, clamped to {@code int} range
     */
    public static int ntpClockOffsetMs(long referenceUtcMs, long deviceNowMs) {
        long offset = referenceUtcMs - deviceNowMs;
        if (offset > Integer.MAX_VALUE) offset = Integer.MAX_VALUE;
        if (offset < Integer.MIN_VALUE) offset = Integer.MIN_VALUE;
        return (int) offset;
    }

    /**
     * Synchronize time using Microsoft's time server
     */
    public static void syncTime(AfterSyncTime afterSyncTime) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                NTPUDPClient timeClient = new NTPUDPClient();
                timeClient.setDefaultTimeout(5000);
                try {
                    InetAddress inetAddress = InetAddress.getByName("time.windows.com");
                    TimeInfo timeInfo = timeClient.getTime(inetAddress);
                    long serverTime = timeInfo.getMessage().getTransmitTimeStamp().getTime();
                    // Apply the FULL correction, not just its remainder within one cycle.
                    // Truncating to (offset % 15000) kept RX/TX cycle alignment but left the
                    // app clock — and every UTC timestamp derived from it — off by a whole
                    // multiple of 15s whenever the device clock was more than a cycle out.
                    // See ntpClockOffsetMs.
                    int trueDelay = ntpClockOffsetMs(serverTime, System.currentTimeMillis());
                    UtcTimer.delay = trueDelay;
                    if (afterSyncTime != null) {
                        afterSyncTime.doAfterSyncTimer(trueDelay);
                    }
                } catch (IOException e) {
                    if (afterSyncTime != null) {
                        afterSyncTime.syncFailed(e);
                    }
                } finally {
                    timeClient.close();
                }

            }
        }).start();
    }

    public interface AfterSyncTime {
        void doAfterSyncTimer(int secTime);

        void syncFailed(IOException e);
    }
}
