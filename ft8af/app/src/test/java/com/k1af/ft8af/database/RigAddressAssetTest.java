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
 *   <li><b>New rigs must be appended</b>: the selected model is persisted as a
 *       raw index into this list ({@code writeConfig("model", index)}), so an
 *       insertion anywhere but the end silently changes every existing user's
 *       selected rig on upgrade. The MK2 entry is asserted to sit after the
 *       previous last entry.</li>
 *   <li>Its CI-V address (0xB6) is unique in the list, so
 *       {@link RigNameList#getIndexByAddress(int)} maps it unambiguously.</li>
 * </ul>
 */
@RunWith(RobolectricTestRunner.class)
public class RigAddressAssetTest {

    private RigNameList load() {
        return new RigNameList(RuntimeEnvironment.getApplication());
    }

    @Test
    public void ic7300mk2_isListedWithIcomDefaults() {
        // Factory-default CI-V address of the IC-7300MK2 is 0xB6 (Icom CI-V
        // reference guide); same 115200 baud and Icom command set as the mk1.
        RigNameList list = load();
        RigNameList.RigName mk2 = null;
        for (RigNameList.RigName r : list.rigList) {
            if (r.modelName.equals("ICOM IC-7300MK2")) {
                mk2 = r;
                break;
            }
        }
        assertThat(mk2).isNotNull();
        assertThat(mk2.address).isEqualTo(0xB6);
        assertThat(mk2.bauRate).isEqualTo(115200);
        assertThat(mk2.instructionSet).isEqualTo(0);
    }

    @Test
    public void ic7300mk2_appendedAfterPreExistingEntries() {
        RigNameList list = load();
        int mk2 = -1;
        int previousLast = -1; // "Yaesu FT-891 (Hamlib)" closed the list before the MK2.
        for (int i = 0; i < list.rigList.size(); i++) {
            String name = list.rigList.get(i).modelName;
            if (name.equals("ICOM IC-7300MK2")) mk2 = i;
            if (name.equals("Yaesu FT-891 (Hamlib)")) previousLast = i;
        }
        assertThat(previousLast).isGreaterThan(0);
        assertThat(mk2).isGreaterThan(previousLast);
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
        RigNameList list = load();
        int mk1Addr = -1;
        for (RigNameList.RigName r : list.rigList) {
            if (r.modelName.equals("ICOM IC-7300")) {
                mk1Addr = r.address;
                break;
            }
        }
        assertThat(mk1Addr).isEqualTo(0x94);
    }
}
