package com.k1af.ft8af.ft8transmit;

import static com.google.common.truth.Truth.assertThat;

import com.k1af.ft8af.Ft8Message;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * Coverage for the {@link FunctionOfTransmit} QSO-step holder: the constructor
 * (which seeds functionMessage from the message's text), the simple
 * getters/setters, and the order-matching logic in
 * {@link FunctionOfTransmit#setCurrentOrder(int)}.
 *
 * Runs under Robolectric because constructing an {@link Ft8Message} pulls in the
 * GMS {@code LatLng} type referenced by Ft8Message; the message used here keeps
 * i3/n3 at their defaults so getMessageText() takes only the pure free-text path.
 */
@RunWith(RobolectricTestRunner.class)
public class FunctionOfTransmitTest {

    private static FunctionOfTransmit make(int order, boolean completed) {
        // i3/n3 default to 0 -> getMessageText() uses the free-text branch.
        Ft8Message msg = new Ft8Message("CQ", "K1ABC", "test");
        return new FunctionOfTransmit(order, msg, completed);
    }

    @Test
    public void constructor_seedsMessageTextFromFreeTextBranch() {
        // Free-text getMessageText() upper-cases and left-pads to 13 chars.
        FunctionOfTransmit f = make(0, false);
        assertThat(f.getFunctionMessage()).isEqualTo("TEST         ");
        assertThat(f.getFunctionOrder()).isEqualTo(0);
        assertThat(f.isCompleted()).isFalse();
    }

    @Test
    public void settersRoundTripScalarFields() {
        FunctionOfTransmit f = make(1, false);
        f.setFunctionOrder(4);
        f.setCompleted(true);
        f.setFunctionMessage("OVERRIDE");
        assertThat(f.getFunctionOrder()).isEqualTo(4);
        assertThat(f.isCompleted()).isTrue();
        assertThat(f.getFunctionMessage()).isEqualTo("OVERRIDE");
    }

    @Test
    public void setCurrentOrder_trueOnlyWhenOrdersMatch() {
        FunctionOfTransmit f = make(3, false);
        f.setCurrentOrder(3);
        assertThat(f.isCurrentOrder()).isTrue();
        f.setCurrentOrder(2);
        assertThat(f.isCurrentOrder()).isFalse();
    }
}
