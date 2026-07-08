package com.k1af.ft8af.database;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import com.k1af.ft8af.FT8Common;
import com.k1af.ft8af.ModeProfile;
import com.k1af.ft8af.rigs.BaseRigOperation;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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
     * Reads the FT8 signal list from the bands.txt file, then appends the
     * operator's persisted custom dial frequencies (issue #470) so they show up
     * in the pickers alongside the built-in band plan.
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
        // Merge user-defined dials last so bands.txt reloads never wipe them
        // (they live in SharedPreferences, not the read-only asset).
        appendCustomBands(bandList, loadCustomBands());
    }

    // --- custom (user-defined) dial frequencies (issue #470) ----------------

    private static final String CUSTOM_PREFS = "ft8af_custom_bands";
    private static final String CUSTOM_KEY = "customBands";

    /**
     * Accepted dial-frequency range (Hz) for a custom entry. The lower bound
     * screens out obvious typos; the upper bound comfortably covers the
     * microwave dials in the shipped plan (QO-100 3cm ~10.489 GHz).
     */
    static final long MIN_CUSTOM_FREQ = 1_000L;
    static final long MAX_CUSTOM_FREQ = 30_000_000_000L;

    /**
     * Parse an operator-entered dial frequency in whole Hz. Returns -1 for a
     * blank / non-numeric value so the caller can show a clear error rather than
     * throwing out of {@code Long.parseLong}.
     */
    static long parseCustomFreq(String input) {
        if (input == null) return -1;
        String t = input.trim();
        if (t.isEmpty()) return -1;
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /** Whether {@code hz} is inside the accepted custom-dial range. */
    static boolean isValidCustomFreq(long hz) {
        return hz >= MIN_CUSTOM_FREQ && hz <= MAX_CUSTOM_FREQ;
    }

    /**
     * Serialize one custom band to a bands.txt-style storage line
     * ({@code marked:freq:waveLength[:mode]}) so it round-trips through the same
     * {@link #parseBandLines} the asset loader uses. Non-FT8 entries carry a
     * mode tag; FT8 (the default) omits it.
     */
    static String toCustomLine(Band b) {
        String line = (b.marked ? "*" : " ") + ":" + b.band + ":" + b.waveLength;
        if (b.mode != FT8Common.FT8_MODE) {
            line += ":" + ModeProfile.fromId(b.mode).displayName;
        }
        return line;
    }

    /** Serialize a list of custom bands to a newline-joined blob for storage. */
    static String serializeCustomBands(List<Band> bands) {
        StringBuilder sb = new StringBuilder();
        for (Band b : bands) {
            if (sb.length() > 0) sb.append('\n');
            sb.append(toCustomLine(b));
        }
        return sb.toString();
    }

    /**
     * Parse a stored custom-band blob back into {@link Band}s, flagging each as
     * custom. Reuses {@link #parseBandLines} so a malformed line is skipped, not
     * fatal.
     */
    static ArrayList<Band> parseCustomBands(String stored) {
        ArrayList<Band> out = parseBandLines(stored == null ? null : stored.split("\n"));
        for (Band b : out) {
            b.custom = true;
        }
        return out;
    }

    /**
     * Append custom bands to {@code target}, skipping any whose freq+mode already
     * matches a built-in entry so a custom dial on a stock frequency doesn't
     * appear twice.
     */
    static void appendCustomBands(List<Band> target, List<Band> customs) {
        for (Band c : customs) {
            boolean dup = false;
            for (Band b : target) {
                if (b.band == c.band && b.mode == c.mode) {
                    dup = true;
                    break;
                }
            }
            if (!dup) target.add(c);
        }
    }

    private SharedPreferences customPrefs() {
        return context.getSharedPreferences(CUSTOM_PREFS, Context.MODE_PRIVATE);
    }

    /** Load the persisted custom dials (empty list if none / unavailable). */
    public ArrayList<Band> loadCustomBands() {
        return parseCustomBands(customPrefs().getString(CUSTOM_KEY, ""));
    }

    private void persistCustomBands(List<Band> bands) {
        customPrefs().edit().putString(CUSTOM_KEY, serializeCustomBands(bands)).apply();
    }

    /**
     * Add — or, when one already exists at this freq+mode, relabel — a custom
     * dial, then rebuild {@link #bandList} so the pickers pick it up immediately.
     * Returns null on success or a human-readable error for invalid input.
     *
     * @param freqInput raw frequency text (whole Hz)
     * @param label     optional label; blank falls back to the derived band name
     * @param mode      operating mode this dial belongs to (FT8Common.*_MODE)
     */
    public String addCustomBand(String freqInput, String label, int mode) {
        long hz = parseCustomFreq(freqInput);
        if (hz < 0) {
            return "Enter a whole number of Hz (e.g. 14090000).";
        }
        if (!isValidCustomFreq(hz)) {
            return "Frequency out of range (" + MIN_CUSTOM_FREQ + "–" + MAX_CUSTOM_FREQ + " Hz).";
        }
        // Labels share the ':' delimiter with the storage format, so strip it.
        String cleanLabel = label == null ? "" : label.trim().replace(':', ' ').trim();
        if (cleanLabel.isEmpty()) {
            cleanLabel = BaseRigOperation.getMeterFromFreq(hz);
        }
        ArrayList<Band> customs = loadCustomBands();
        Band existing = null;
        for (Band b : customs) {
            if (b.band == hz && b.mode == mode) {
                existing = b;
                break;
            }
        }
        if (existing != null) {
            existing.waveLength = cleanLabel;
        } else {
            Band nb = new Band(hz, cleanLabel);
            nb.mode = mode;
            nb.custom = true;
            customs.add(nb);
        }
        persistCustomBands(customs);
        getBandsFromFile();
        return null;
    }

    /**
     * Delete the custom dial at {@code freq}+{@code mode}, then rebuild
     * {@link #bandList}. Returns true if an entry was removed.
     */
    public boolean removeCustomBand(long freq, int mode) {
        ArrayList<Band> customs = loadCustomBands();
        boolean removed = false;
        for (int i = customs.size() - 1; i >= 0; i--) {
            Band b = customs.get(i);
            if (b.band == freq && b.mode == mode) {
                customs.remove(i);
                removed = true;
            }
        }
        if (removed) {
            persistCustomBands(customs);
            getBandsFromFile();
        }
        return removed;
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
                Log.e(TAG, "Skipping malformed band line \""+line+"\"", e);
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
        int mode = com.k1af.ft8af.GeneralVariables.operatingMode;
        for (int i = 0; i < bandList.size(); i++) {
            Band b = bandList.get(i);
            if (b.mode == mode && !com.k1af.ft8af.GeneralVariables.isBandExcluded(b.waveLength)) {
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
        public int mode = com.k1af.ft8af.FT8Common.FT8_MODE;//FT8 unless tagged otherwise in bands.txt
        //True for operator-defined dials (issue #470); built-in bands.txt entries are false.
        public boolean custom=false;

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
                for (com.k1af.ft8af.ModeProfile m : com.k1af.ft8af.ModeProfile.values()) {
                    if (m.displayName.equalsIgnoreCase(tag)) {
                        mode = m.id;
                        break;
                    }
                }
            }
        }
        @SuppressLint("DefaultLocale")
        public String getBandInfo(){
                String info = String.format("%s %.3f MHz (%s)"
                        ,marked?"*":" "
                        ,(float)(band/1000000f)
                        ,waveLength);
                // Mark operator-defined dials so they're distinguishable in the picker.
                return custom ? info + " ★" : info;
        }
    }


}
