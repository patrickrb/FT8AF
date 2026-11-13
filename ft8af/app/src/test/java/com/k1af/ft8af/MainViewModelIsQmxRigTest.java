package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.rigs.KenwoodTS590Rig;
import com.k1af.ft8af.rigs.QrpLabsQmxRig;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * {@link MainViewModel#isQmxRig} for the rig-list entries that need a main
 * Looper to construct (the Kenwood-family rigs post their first query on it).
 * The hamlib cases are in {@link MainViewModelRxTxUsbConflictTest}.
 */
@RunWith(RobolectricTestRunner.class)
public class MainViewModelIsQmxRigTest {

    @Test
    public void nativeQmxEntry_isAQmx() {
        assertThat(MainViewModel.isQmxRig(new QrpLabsQmxRig())).isTrue();
    }

    @Test
    public void itsTs590Parent_isNot() {
        // The QDX and other TS-590-protocol rigs share the parent class.
        assertThat(MainViewModel.isQmxRig(new KenwoodTS590Rig())).isFalse();
    }
}
