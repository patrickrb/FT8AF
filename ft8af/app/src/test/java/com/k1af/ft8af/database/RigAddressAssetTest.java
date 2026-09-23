package com.k1af.ft8af.database;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.ArrayList;

/**
 * Coverage for the shipped {@code rigaddress.txt} asset as loaded by
 * {@link RigNameList} (Robolectric serves the real file, so this also
 * exercises getRigNamesFromFile / getRigNameByIndex / getIndexByAddress,
 * which {@link RigNameListTest} can't reach without a Context).
 *
 * <p>Two invariants matter here beyond the IC-7300MK2 entry itself:
 * <ul>
 *   <li><b>New rigs must be appended, existing lines must not move</b>: the
 *       selected model is persisted as a raw index into this list
 *       ({@code writeConfig("model", index)}), so an insertion, removal, or
 *       reorder anywhere but the end silently changes every existing user's
 *       selected rig on upgrade. {@link #rigList_matchesGoldenIndexOrder} pins
 *       every entry to its index; when you add a rig, append it to both the
 *       asset file and the end of {@code GOLDEN_ORDER}.</li>
 *   <li>Its CI-V address (0xB6) is unique in the list, so
 *       {@link RigNameList#getIndexByAddress(int)} maps it unambiguously.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class RigAddressAssetTest {

    /**
     * Every model name in {@code rigaddress.txt}, in file order. Index in the
     * loaded {@code rigList} is this position + 1 (the loader prepends an empty
     * "none" sentinel at index 0). Append-only — see the class javadoc.
     */
    private static final String[] GOLDEN_ORDER = {
        "ICOM IC-7000",
        "ICOM IC-705",
        "ICOM IC-7100",
        "ICOM IC-7200",
        "ICOM IC-7300",
        "ICOM IC-7400",
        "ICOM IC-7410",
        "ICOM IC-746",
        "ICOM IC-746PRO",
        "ICOM IC-756PRO",
        "ICOM IC-756PRO2",
        "ICOM IC-756PRO3",
        "ICOM IC-7600",
        "ICOM IC-7610",
        "ICOM IC-7700",
        "ICOM IC-7800",
        "ICOM IC-7850",
        "ICOM IC-7851",
        "ICOM IC-9100",
        "ICOM IC-9700",
        "ICOM IC-R8600",
        "ICOM ID-52A",
        "ICOM IC-706MKIIG",
        "ICOM IC-706MKII",
        "ICOM IC-703",
        "ICOM IC-707(725A)",
        "ICOM IC-718",
        "ICOM IC-725",
        "ICOM IC-746PRO",
        "ICOM IC-756PRO3",
        "ICOM IC-775",
        "ICOM IC-910H",
        "ICOM IC-78",
        "#XIEGU X6100(FT8CNS)",
        "XIEGU X6100(U-DIG)",
        "XIEGU X6200(U-DIG)",
        "XIEGU G90S(U-DIG)",
        "XIEGU G90S(USB)",
        "XIEGU G106C(U-DIG)",
        "XIEGU G106C(USB)",
        "XIEGU X5105",
        "XIEGU X108",
        "GUOHE Q900",
        "GUOHE PMR-171",
        "YAESU FT-450(D)",
        "YAESU FT-817",
        "YAESU FT-818",
        "YAESU FT-847",
        "YAESU FT-857",
        "YAESU FT-891/991(USB)",
        "YAESU FT-891/991(DATA-USB)",
        "YAESU FT-897(D)",
        "YAESU FT-950",
        "YAESU FT-2000(D)",
        "YAESU FT-710",
        "YAESU FT-DX10",
        "YAESU FT-DX101",
        "YAESU FT-DX Other series",
        "KENWOOD TK-90",
        "KENWOOD TS-440",
        "KENWOOD TS-480",
        "KENWOOD TS-570",
        "KENWOOD TS-590",
        "KENWOOD TS-2000",
        "Discovery TX-500",
        "KN990",
        "Elecraft K3S\\K3\\KX3\\KX2",
        "mcHF-QRP sdr",
        "FlexRadio 6000 series",
        "FX-4CR",
        "Qrp Labs QDX",
        "UA3REO Wolf SDR(DIGU)",
        "UA3REO Wolf SDR(USB)",
        "(tr)uSDX (audio over cat)",
        "(tr)uSDX (TS-480)",
        "Yaesu FT-891 (Hamlib)",
        "ICOM IC-7300MK2",
    };

    private RigNameList load() {
        return new RigNameList(RuntimeEnvironment.getApplication());
    }

    /** The first entry whose modelName equals {@code name}, or null. */
    private static RigNameList.RigName findByName(RigNameList list, String name) {
        for (RigNameList.RigName r : list.rigList) {
            if (r.modelName.equals(name)) {
                return r;
            }
        }
        return null;
    }

    @Test
    public void ic7300mk2_isListedWithIcomDefaults() {
        // Factory-default CI-V address of the IC-7300MK2 is 0xB6 (Icom CI-V
        // reference guide); same 115200 baud and Icom command set as the mk1.
        RigNameList.RigName mk2 = findByName(load(), "ICOM IC-7300MK2");
        assertThat(mk2).isNotNull();
        assertThat(mk2.address).isEqualTo(0xB6);
        assertThat(mk2.bauRate).isEqualTo(115200);
        assertThat(mk2.instructionSet).isEqualTo(0);
    }

    @Test
    public void rigList_matchesGoldenIndexOrder() {
        // Positional stability: a saved "model" config value is a raw index
        // into this list, so any mid-file insertion, removal, or reorder — not
        // just around the MK2 entry — must fail here, not ship.
        RigNameList list = load();
        ArrayList<String> names = new ArrayList<>();
        for (RigNameList.RigName r : list.rigList) {
            names.add(r.modelName);
        }
        assertThat(names.get(0)).isEmpty(); // the injected "none" sentinel
        assertThat(names.subList(1, names.size()))
                .containsExactlyElementsIn(GOLDEN_ORDER)
                .inOrder();
    }

    @Test
    public void ic7300mk2_isTheLastEntry() {
        // New rigs are append-only (class javadoc); MK2 closed the list.
        RigNameList list = load();
        assertThat(list.rigList.get(list.rigList.size() - 1).modelName)
                .isEqualTo("ICOM IC-7300MK2");
    }

    @Test
    public void ic7300mk2_addressIsUniqueAndRoundTripsThroughIndexLookup() {
        RigNameList list = load();
        ArrayList<Integer> holders = new ArrayList<>();
        for (int i = 0; i < list.rigList.size(); i++) {
            if (list.rigList.get(i).address == 0xB6) holders.add(i);
        }
        assertThat(holders).hasSize(1);
        int index = list.getIndexByAddress(0xB6);
        assertThat(index).isEqualTo(holders.get(0));
        assertThat(list.getRigNameByIndex(index).modelName).isEqualTo("ICOM IC-7300MK2");
    }

    @Test
    public void mk1AndMk2_areDistinctEntries() {
        // The mk1 keeps 0x94 — a user with either radio at factory defaults
        // picks their model and gets working CAT with no radio-menu changes.
        RigNameList.RigName mk1 = findByName(load(), "ICOM IC-7300");
        assertThat(mk1).isNotNull();
        assertThat(mk1.address).isEqualTo(0x94);
    }
}
