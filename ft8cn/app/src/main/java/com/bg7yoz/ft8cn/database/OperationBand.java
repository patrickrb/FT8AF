package com.bg7yoz.ft8cn.database;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;

import com.bg7yoz.ft8cn.rigs.BaseRigOperation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;

/**
 * Reads the list of available carrier bands, stored in assets/bands.txt
 * @author BGY70Z
 * @date 2023-03-20
 */

public class OperationBand {
    private static final String TAG="OperationBand";
    private final Context context;
    private static OperationBand operationBand = null;

    public static long getDefaultBand() {
        return 14074000;
    }

    public static String getDefaultWaveLength() {
        return "20m";
    }

    public static ArrayList<Band> bandList = new ArrayList<>();
    public OperationBand(Context context) {
        this.context = context;
        //Load band data into memory
        getBandsFromFile();
    }

    public static OperationBand getInstance(Context context) {
        if (operationBand == null) {
            operationBand=new OperationBand(context);
            return operationBand;
        } else {
            return operationBand;
        }
    }

    /**
     * Gets operating band data by list index; returns default value 14.074 MHz, 20m if not found
     * @param index index
     * @return
     */
    public Band getBandByIndex(int index){
        if (!isValidBandIndex(index)){
            return new Band(getDefaultBand(),getDefaultWaveLength());
        }else {
            return bandList.get(index);
        }
    }

    /**
     * True when {@code index} is a valid position in {@link #bandList}.
     * Centralises the bounds check so every accessor rejects both negative
     * indices and indices at/after the end. {@link #getBandFreq(int)} previously
     * used an off-by-one {@code index > size} guard that let {@code index == size}
     * through into {@code bandList.get(index)} and threw IndexOutOfBounds.
     */
    static boolean isValidBandIndex(int index){
        return index>=0 && index<bandList.size();
    }

    /**
     * Checks if the frequency is in the frequency list; if not, adds this frequency to the band list
     * @param freq
     * @return
     */
    public static int getIndexByFreq(long freq){
        int result=-1;
        for (int i = 0; i < bandList.size(); i++) {
            if (bandList.get(i).band==freq){
                result=i;
                break;
            }
        }
        if (result==-1){
            bandList.add(new Band(freq, BaseRigOperation.getMeterFromFreq(freq)));
            result=bandList.size()-1;
        }
        return result;
    }
    /**
     * Reads the FT8 signal list from the bands.txt file.
     */
    public void getBandsFromFile(){
        AssetManager assetManager = context.getAssets();
        try {
            bandList.clear();
            InputStream inputStream= assetManager.open("bands.txt");
            String[] st=getLinesFromInputStream(inputStream,"\n");
            bandList.addAll(parseBandLines(st));
            inputStream.close();
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Error extracting data from band list file: "+e.getMessage() );
        }
    }

    /**
     * Whether a bands.txt line is a band entry (vs. a comment or blank line).
     * A line is parsed as a band only when it is non-blank, is not a {@code #}
     * comment, and contains a colon. Skipping {@code #} comments lets comments
     * contain colons (e.g. URLs, frequency ranges) without the loader trying to
     * {@code Long.parseLong} them and crashing band-list loading.
     */
    static boolean isBandLine(String line) {
        if (line == null) return false;
        String trimmed = line.trim();
        if (trimmed.isEmpty() || trimmed.startsWith("#")) return false;
        return trimmed.contains(":");
    }

    /**
     * Parses bands.txt lines into {@link Band} entries, skipping comment/blank
     * lines (see {@link #isBandLine}) and any line that fails to parse — e.g. a
     * truncated {@code "20m:"} with no frequency, which would otherwise throw
     * out of the {@link Band#Band(String)} constructor. A single malformed line
     * is logged and skipped rather than aborting the whole band-list load and
     * leaving the app with no bands.
     */
    static ArrayList<Band> parseBandLines(String[] lines){
        ArrayList<Band> out = new ArrayList<>();
        if (lines == null) return out;
        for (String line : lines) {
            if (!isBandLine(line)) continue;
            try {
                out.add(new Band(line));
            } catch (RuntimeException e) {
                Log.e(TAG, "Skipping malformed band line \""+line+"\": "+e.getMessage());
            }
        }
        return out;
    }

    public static String getBandInfo(int index){
        if (bandList.isEmpty()){
            return new Band(getDefaultBand(),getDefaultWaveLength()).getBandInfo();
        }
        if (!isValidBandIndex(index)){
            return bandList.get(0).getBandInfo();
        }
        return bandList.get(index).getBandInfo();
    }

    /**
     * Reads strings from an InputStream
     * @param inputStream input stream
     * @param deLimited delimiter for each line of data
     * @return String returns a string, or null if it fails
     */
    public static String[] getLinesFromInputStream(InputStream inputStream, String deLimited) {
        try {
            byte[] bytes = new byte[inputStream.available()];
            inputStream.read(bytes);
            return (new String(bytes)).split(deLimited);
        }catch (IOException e){
            return null;
        }
    }
    /**
     * Indices into {@link #bandList} for bands whose waveLength the user has not
     * hidden, in file order. Used by the band pickers so excluded bands (e.g. 6m,
     * 60m in regions where they're prohibited) don't appear. Also filtered to the
     * current operating mode so the picker shows the right dials (FT8 vs FT4).
     */
    public static java.util.List<Integer> getVisibleBandIndices(){
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        int mode = com.bg7yoz.ft8cn.GeneralVariables.operatingMode;
        for (int i = 0; i < bandList.size(); i++) {
            Band b = bandList.get(i);
            if (b.mode == mode && !com.bg7yoz.ft8cn.GeneralVariables.isBandExcluded(b.waveLength)) {
                out.add(i);
            }
        }
        return out;
    }

    /**
     * Distinct band names (e.g. "160m","6m") in file order. Drives the
     * Enabled Bands toggle list in Settings.
     */
    public static java.util.List<String> getAllWaveLengths(){
        java.util.LinkedHashSet<String> s = new java.util.LinkedHashSet<>();
        for (Band b : bandList) {
            s.add(b.waveLength);
        }
        return new java.util.ArrayList<>(s);
    }

    public static long getBandFreq(int index){
        if (!isValidBandIndex(index)){
            return getDefaultBand();
        }
        return bandList.get(index).band;
    }

    /**
     * The dial frequency for a given waveLength in a given mode, or -1 if no entry exists.
     * Used to retune within the current band when the operating mode changes (FT8 <-> FT4);
     * the band itself never changes, only the in-band dial. Prefers the marked (*) entry,
     * falling back to the first matching entry.
     *
     * @param waveLength band name, e.g. "20m"
     * @param mode       FT8Common.FT8_MODE / FT4_MODE
     * @return dial frequency in Hz, or -1 if this band has no entry in that mode
     */
    public static long getModeBandFreq(String waveLength, int mode) {
        long firstMatch = -1;
        for (Band b : bandList) {
            if (b.mode == mode && b.waveLength.equals(waveLength)) {
                if (b.marked) {
                    return b.band;
                }
                if (firstMatch == -1) {
                    firstMatch = b.band;
                }
            }
        }
        return firstMatch;
    }

    public static class Band {
        public long band;
        public String waveLength;
        public boolean marked=false;
        public int mode = com.bg7yoz.ft8cn.FT8Common.FT8_MODE;//FT8 unless tagged otherwise in bands.txt

        public Band(long band, String waveLength) {
            this.band = band;
            this.waveLength = waveLength;
        }

        public Band(String s) {
            String[] info=s.split(":");
            marked= (info[0].equals("*"));
            band=Long.parseLong(info[1]);
            //Format: marked:freq:waveLength[:mode]. waveLength is field 2; an optional 4th
            //field tags the mode by ModeProfile.displayName ("FT4"/"FT2"), otherwise FT8.
            //Resolving against ModeProfile keeps future modes a one-entry add (no new branch).
            waveLength=info[2];
            if (info.length > 3 && !info[3].trim().isEmpty()) {
                String tag = info[3].trim();
                for (com.bg7yoz.ft8cn.ModeProfile m : com.bg7yoz.ft8cn.ModeProfile.values()) {
                    if (m.displayName.equalsIgnoreCase(tag)) {
                        mode = m.id;
                        break;
                    }
                }
            }
        }
        @SuppressLint("DefaultLocale")
        public String getBandInfo(){
                return String.format("%s %.3f MHz (%s)"
                        ,marked?"*":" "
                        ,(float)(band/1000000f)
                        ,waveLength);
        }
    }


}
