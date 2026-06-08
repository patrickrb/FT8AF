package com.bg7yoz.ft8cn.timer;
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

import com.bg7yoz.ft8cn.ui.ToastMessage;

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
    private final int sec;
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
     * Since the cycle parameter is an int, the unit is tenths of a second.
     * Because the heartbeat frequency is fast (currently set to 100ms), heartbeat actions should be as concise as possible
     * and must complete before the next heartbeat starts, to prevent thread stacking and performance impact.
     * Heartbeat actions are not affected by cycle actions not triggering (running==false); as long as the UtcTimer instance exists, heartbeat actions run (convenient for displaying clock data).
     * This trigger requires calling the delete function to fully stop (heartbeat actions also stop).
     *
     * @param sec        clock cycle period in tenths of a second, e.g.: 15 seconds = 150, 7.5 seconds = 75
     * @param doOnce     whether to trigger only once
     * @param onUtcTimer callback functions, including heartbeat callback and cycle start action callback
     */
    public UtcTimer(int sec, boolean doOnce, OnUtcTimer onUtcTimer) {
        this.sec = sec;
        this.doOnce = doOnce;
        this.onUtcTimer = onUtcTimer;

        //initialize Timer tasks
        //TimerTask timerTask = initTask();
        //execute timer, 0 delay, 100ms period

        secTimer.schedule(secTask(), 0, 10);
        heartBeatTimer.schedule(heartBeatTask(), 0, 1000);
    }

    /**
     * Define the clock-triggered action.
     * The clock cycle is typically 15 seconds or 7.5 seconds; since the cycle parameter is an int, the unit is tenths of a second.
     * Because the heartbeat frequency is fast (currently set to 100ms), heartbeat actions should be as concise as possible
     * and must complete before the next heartbeat starts, to prevent thread stacking and performance impact.
     * Heartbeat actions are not affected by cycle actions not triggering (running==false); as long as the UtcTimer instance exists, heartbeat actions run (convenient for displaying clock data).
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

    private TimerTask secTask() {
        return new TimerTask() {


            @Override
            public void run() {

                try {
                    utc = getSystemTime();//get current UTC time
                    //utc/100 is in tenths of a second, so modulo should be 600, not 60, remember!
                    //running determines whether to trigger cycle actions
                    //+80 is time compensation for some action delays after triggering
                    //time_sec is the time offset
                    if (running && (((utc - time_sec) / 100) % 600) % sec == 0) {
                        //cycle action
                        //IMPORTANT! doHeartBeatTimer must not perform time-consuming operations and must complete within the heartbeat interval, otherwise thread backlog may occur and affect performance.
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
        return sequential(utc, com.bg7yoz.ft8cn.GeneralVariables.currentMode().slotMillis);
    }

    public static int getNowSequential() {
        return sequential(getSystemTime());
    }

    public static long getSystemTime() {
        return delay + System.currentTimeMillis();
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
                    int trueDelay = (int) ((serverTime - System.currentTimeMillis()));
                    UtcTimer.delay = trueDelay % 15000;//delay per cycle
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
