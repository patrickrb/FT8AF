package com.k1af.ft8af.flex;

import java.util.HashMap;

/**
 * Mapping table of Meter IDs to definitions. After starting Flex, Meter ID values are not fixed, so a hash table is needed.
 * @author BGY70Z
 * @date 2023-03-20
 */
public class FlexMeterInfos extends HashMap<Integer, FlexMeterInfos.FlexMeterInfo> {
    private static final String TAG = "FlexMeters";
    public int sMeterId = -1;
    public int tempCId = -1;
    public int swrId = -1;
    public int pwrId = -1;
    public int alcId = -1;

    public FlexMeterInfos(String content) {
        setMeterInfos(content);
    }

    /**
     * Based on the radio's response message, builds the mapping table of each METER's ID to its meter definition
     *
     * @param content message
     */
    public synchronized void setMeterInfos(String content) {
        String[] temp;
        if (content.length() == 0) return;
        temp = content.substring(content.indexOf("meter ") + "meter ".length()).split("#");
        for (int i = 0; i < temp.length; i++) {
            String[] val = temp[i].split("=");
            if (val.length == 2) {
                if (val[0].contains(".")) {
                    int index;
                    try {
                        index = Integer.parseInt(val[0].substring(0, val[0].indexOf(".")));
                    } catch (NumberFormatException e) {
                        continue;
                    }
                    FlexMeterInfo meterInfo;

                    meterInfo = this.get(index);
                    if (meterInfo == null) {
                        meterInfo = new FlexMeterInfo();
                        this.put(index, meterInfo);
                    }


                    if (val[0].toLowerCase().contains(".src")) {

                        meterInfo.src = val[1];
                    }
                    if (val[0].toLowerCase().contains(".num")) {
                        meterInfo.num = val[1];
                    }
                    if (val[0].toLowerCase().contains(".nam")) {
                        meterInfo.nam = val[1];
                    }
                    if (val[0].toLowerCase().contains(".low")) {
                        try { meterInfo.low = Float.parseFloat(val[1]); }
                        catch (NumberFormatException ignored) {}
                    }
                    if (val[0].toLowerCase().contains(".hi")) {
                        try { meterInfo.hi = Float.parseFloat(val[1]); }
                        catch (NumberFormatException ignored) {}
                    }
                    if (val[0].toLowerCase().contains(".desc")) {
                        meterInfo.desc = val[1];
                    }
                    if (val[0].toLowerCase().contains(".unit")) {
                        String s = val[1].toUpperCase();
                        if (s.contains("DB")) {
                            meterInfo.unit = FlexMeterType.dBm;
                        } else if (s.contains("SWR")) {
                            meterInfo.unit = FlexMeterType.swr;
                        } else if (s.contains("DEG")) {
                            meterInfo.unit = FlexMeterType.Temperature;
                        } else if (s.contains("VOLT")) {
                            meterInfo.unit = FlexMeterType.volt;
                        } else {
                            meterInfo.unit = FlexMeterType.other;
                        }

                    }
                    if (val[0].toLowerCase().contains(".fps")) {
                        meterInfo.fps = val[1];
                    }
                    if (val[0].toLowerCase().contains(".peak")) {
                        meterInfo.peak = val[1];
                    }
                }
            }
        }
        resolveMeterIds();
//        sMeterId=getMeterId("LEVEL");
//        tempCId=getMeterId("PATEMP");
//        swrId=getMeterId("SWR");
//        pwrId=getMeterId("FWDPWR");
//        alcId=getMeterId("ALC");
    }

    /**
     * Re-resolve the five fast-lookup meter ids from the full accumulated meter
     * table, deterministically. A Flex reports DUPLICATE meters with the same
     * name — a second receive slice has its own LEVEL meter, and a second
     * transmit block (num=9) has its own ALC — so the original "remember the
     * id of whatever name I last saw" logic latched ALC onto the inactive id33
     * and the S-meter onto the unused slice-1 id27 (both idle at -150), which is
     * why those two meters read dead while temp/SWR/PWR (unique names) were fine.
     *
     * Fix: scan every known meter in ascending id order and keep the LOWEST id
     * whose name matches exactly. The lowest id is the original receive slice /
     * the active transmit block — the meter that actually moves. Rescanning the
     * whole table on every call makes this idempotent: re-requesting the meter
     * list (e.g. each time the HUD opens) can no longer flip a good id to a
     * duplicate, even when this FlexMeterInfos instance is reused across reconnects.
     */
    private synchronized void resolveMeterIds() {
        sMeterId = -1;
        tempCId = -1;
        swrId = -1;
        pwrId = -1;
        alcId = -1;
        java.util.List<Integer> keys = new java.util.ArrayList<>(keySet());
        java.util.Collections.sort(keys);
        for (int key : keys) {
            FlexMeterInfo info = get(key);
            if (info == null || info.nam == null) continue;
            String name = info.nam.toUpperCase();
            if (sMeterId == -1 && name.equals("LEVEL")) sMeterId = key;
            else if (tempCId == -1 && name.equals("PATEMP")) tempCId = key;
            else if (swrId == -1 && name.equals("SWR")) swrId = key;
            else if (pwrId == -1 && name.equals("FWDPWR")) pwrId = key;
            else if (alcId == -1 && name.equals("ALC")) alcId = key;
        }
    }

    /**
     * Looks up Meter ID; returns -1 if not found
     *
     * @return id
     */
    private int getMeterId(String s) {
        for (int key : this.keySet()) {
            if (get(key).nam.equalsIgnoreCase(s)) {
                return key;
            }
        }
        return -1;
    }


    public static class FlexMeterInfo {
        public String src;
        public String num;
        public String nam;
        public float low;
        public float hi;
        public String desc;
        public FlexMeterType unit = FlexMeterType.other;
        public String fps;
        public String peak;

        @Override
        public String toString() {
            return "{" +
                    "src='" + src + '\'' +
                    ", num='" + num + '\'' +
                    ", nam='" + nam + '\'' +
                    ", low='" + low + '\'' +
                    ", hi='" + hi + '\'' +
                    ", desc='" + desc + '\'' +
                    ", unit='" + unit + '\'' +
                    ", fps='" + fps + '\'' +
                    ", peak='" + peak + '\'' +
                    '}';
        }
    }
}
