package com.k1af.ft8af.timer;
/**
 * Interface for the UtcTimer class, used for UtcTimer callbacks.
 * UtcTimer is an action trigger that fires actions when a clock cycle arrives; the trigger callback function is DoOnSecTimer.
 * UtcTimer loops at a fixed frequency (currently defaults to 100 milliseconds); the callback function at each frequency is doHeartBeatTimer.
 * IMPORTANT! doHeartBeatTimer must not perform time-consuming operations and must complete within the heartbeat interval,
 * otherwise thread backlog may occur and affect performance.
 *
 * @author BG7YOZ
 * @date 2022.5.6
 */
public interface OnUtcTimer {
    void doHeartBeatTimer(long utc);//heartbeat callback, triggered on each cycle of the trigger; only handle simple tasks, avoid excessive CPU usage to prevent thread stacking
    void doOnSecTimer(long utc);//triggered at the specified time interval
}
