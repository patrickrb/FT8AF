package com.k1af.ft8af;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

/**
 * Regression coverage for the shared-mutable pooled-{@link Runnable} race in
 * {@code MainViewModel.afterDecode()} (and the TX {@code onTransmit*} path).
 *
 * <p>{@code afterDecode} is entered <em>concurrently</em> — slot N's late/deep pass and
 * slot N+1's early pass overlap (#398), which is why {@code ft8Messages} and
 * {@code decodeCycleState} are synchronized there. The QTH-lookup dispatch, however, used
 * to reuse one {@code GetQTHRunnable} instance: each call did
 * {@code getQTHRunnable.messages = messages} on the shared object and re-submitted it to a
 * cached thread pool. When two passes overlapped, the second field write clobbered the
 * first, so one pass's list never got its country/grid flags resolved
 * ({@code getMessagesLocation}) and never fired its Needed-DX alerts
 * ({@code dxAlertNotifier.processDecodes}) — the alerts were silently dropped, and two pool
 * workers raced on the one non-volatile field. The fix binds the message list to a fresh
 * task per dispatch ({@code new GetQTHRunnable(this, messages)}); the TX
 * {@code SendWaveDataRunnable} got the same treatment.
 *
 * <p>Robolectric is only needed to construct real {@link Ft8Message} rows (their ctor
 * touches {@code android.util.Log}); the binding under test touches no Android types.
 */
@RunWith(RobolectricTestRunner.class)
public class MainViewModelPooledTaskTest {

    @Test
    public void getQthRunnable_bindsItsOwnMessageList_perDispatch() {
        // Two overlapping afterDecode passes, each with its own decoded-message list.
        ArrayList<Ft8Message> passA = new ArrayList<>();
        ArrayList<Ft8Message> passB = new ArrayList<>();

        MainViewModel.GetQTHRunnable a = new MainViewModel.GetQTHRunnable(null, passA);
        // Constructing the second dispatch must NOT disturb the first. With the old shared
        // instance, the second `getQTHRunnable.messages = passB` would have rebound both.
        MainViewModel.GetQTHRunnable b = new MainViewModel.GetQTHRunnable(null, passB);

        assertThat(a.messages()).isSameInstanceAs(passA);
        assertThat(b.messages()).isSameInstanceAs(passB);
        assertThat(a.messages()).isNotSameInstanceAs(b.messages());
    }

    @Test
    public void sendWaveDataRunnable_bindsItsOwnMessage_perDispatch() {
        Ft8Message first = new Ft8Message(FT8Common.FT8_MODE);
        Ft8Message second = new Ft8Message(FT8Common.FT8_MODE);

        MainViewModel.SendWaveDataRunnable a =
                new MainViewModel.SendWaveDataRunnable(null, first);
        MainViewModel.SendWaveDataRunnable b =
                new MainViewModel.SendWaveDataRunnable(null, second);

        assertThat(a.message()).isSameInstanceAs(first);
        assertThat(b.message()).isSameInstanceAs(second);
    }

    @Test
    public void sendWaveDataRunnable_nullRigOrMessage_isNoOp() {
        // The guard survives the immutable rewrite: a task with no rig/message just returns.
        new MainViewModel.SendWaveDataRunnable(null, null).run();
        new MainViewModel.SendWaveDataRunnable(null, new Ft8Message(FT8Common.FT8_MODE)).run();
    }

    /**
     * Makes the dropped-pass hazard deterministic: a single shared payload holder overwritten
     * before either "worker" runs loses the first pass's list, whereas a fresh task per
     * dispatch preserves both. This is the exact shape of the production bug the fix removes.
     */
    @Test
    public void sharedInstance_dropsAPass_whilePerDispatchDoesNot() {
        List<Object> processed = new ArrayList<>();

        Object listA = new Object();
        Object listB = new Object();

        // OLD wiring: one shared holder whose payload is overwritten by the 2nd dispatch,
        // then both submitted Runnables read the (now single) shared payload.
        MutableHolder shared = new MutableHolder();
        shared.payload = listA;                                   // pass A dispatched
        Runnable sharedDispatchA = () -> processed.add(shared.payload);
        shared.payload = listB;                                   // pass B clobbers A
        Runnable sharedDispatchB = () -> processed.add(shared.payload);
        sharedDispatchA.run();
        sharedDispatchB.run();
        assertThat(processed).containsExactly(listB, listB);      // pass A's list was lost

        // NEW wiring: a fresh task bound to each pass's payload.
        processed.clear();
        Object freshA = new Object();
        Object freshB = new Object();
        Runnable boundA = boundTask(processed, freshA);
        Runnable boundB = boundTask(processed, freshB);
        boundA.run();
        boundB.run();
        assertThat(processed).containsExactly(freshA, freshB);    // both passes processed
    }

    private static Runnable boundTask(List<Object> sink, Object payload) {
        return () -> sink.add(payload);
    }

    private static final class MutableHolder {
        Object payload;
    }
}
