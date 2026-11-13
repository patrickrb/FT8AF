package com.k1af.ft8af.bluetooth;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

/**
 * Tests for the Default-output override that steers TX to A2DP when this app is
 * holding a Bluetooth SCO link (issue #759 follow-up). Pure JUnit — the policy
 * takes plain ints/Strings so it never touches the Android runtime.
 *
 * <p>The second half of these pin the conditions added after the Copilot review
 * on #790: enumerating both profiles proves neither that a SCO session is ours
 * nor that the two endpoints are the same physical device, and steering on
 * either mistaken belief pushes TX audio somewhere the rig cannot hear it.
 */
public class AudioOutputRoutingPolicyTest {

    // Framework AudioDeviceInfo type constants used to build test inputs.
    private static final int TYPE_BUILTIN_SPEAKER = 2;
    private static final int TYPE_WIRED_HEADSET = 3;
    private static final int TYPE_BLUETOOTH_SCO = 7;
    private static final int TYPE_BLUETOOTH_A2DP = 8;
    private static final int TYPE_USB_DEVICE = 11;

    @Test
    public void constantsMatchFrameworkValues() throws Exception {
        int frameworkA2dp = android.media.AudioDeviceInfo.class
                .getField("TYPE_BLUETOOTH_A2DP").getInt(null);
        int frameworkSco = android.media.AudioDeviceInfo.class
                .getField("TYPE_BLUETOOTH_SCO").getInt(null);
        assertThat(AudioOutputRoutingPolicy.TYPE_BLUETOOTH_A2DP).isEqualTo(frameworkA2dp);
        assertThat(AudioOutputRoutingPolicy.TYPE_BLUETOOTH_SCO).isEqualTo(frameworkSco);
    }

    /** The tester's headset: one paired device exposing both profiles. */
    private static final String HEADSET = "AA:BB:CC:DD:EE:FF";
    /** A second, unrelated Bluetooth device - a car kit or a speaker. */
    private static final String OTHER = "11:22:33:44:55:66";
    /** What AudioManager reports for a non-Bluetooth endpoint. */
    private static final String NONE = "";

    /** The platform withheld which device our SCO link is on. */
    private static int pick(int[] types, String[] addresses, boolean scoActive) {
        return AudioOutputRoutingPolicy.pickDefaultOutputIndex(types, addresses, scoActive, null);
    }

    /** The mic's routed capture device told us which device our SCO link is on. */
    private static int pickOn(int[] types, String[] addresses, String activeSco) {
        return AudioOutputRoutingPolicy.pickDefaultOutputIndex(types, addresses, true, activeSco);
    }

    // -- two hands-free devices: only the one carrying our link may win -------

    @Test
    public void twoDualProfileDevices_withOurLinkIdentified_picksThatDevicesA2dp() {
        // A car kit enumerated first and the rig second, both exposing SCO and
        // A2DP. The first matching pair is the car's; only the routed capture
        // device tells us the rig is the one holding our link.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, OTHER, HEADSET, HEADSET};
        assertThat(pickOn(types, addrs, HEADSET)).isEqualTo(2);
        assertThat(pickOn(types, addrs, OTHER)).isEqualTo(0);
    }

    @Test
    public void twoDualProfileDevices_withOurLinkUnknown_leavesToOs() {
        // Same phone, but the platform could not say which device the mic is
        // captured from. Guessing the first pair could send TX into the car.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, OTHER, HEADSET, HEADSET};
        assertThat(pick(types, addrs, true)).isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void ourLinkOnADeviceWithoutA2dp_leavesToOs_evenIfAnotherPairExists() {
        // Our SCO link is on a headset that has no A2DP endpoint; the car kit
        // next to it pairs perfectly well but is not where the rig listens.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, OTHER, HEADSET};
        assertThat(pickOn(types, addrs, HEADSET)).isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void identifiedLinkMatchesCaseInsensitively() {
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {HEADSET, HEADSET};
        assertThat(pickOn(types, addrs, "aa:bb:cc:dd:ee:ff")).isEqualTo(0);
    }

    @Test
    public void identifiedLink_withAddressesWithheld_stillUsesTheSinglePairFallback() {
        // The capture side knew the address but the output enumeration blanked
        // them all: one A2DP + one SCO is still an unambiguous pairing.
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, NONE, NONE};
        assertThat(pickOn(types, addrs, HEADSET)).isEqualTo(1);
    }

    @Test
    public void knownScoNextToABlankSco_isAmbiguous_leavesToOs() {
        // The car kit reports its address, the rig's SCO endpoint comes back
        // blank (some builds withhold it). Only one address is known, but that
        // is one device out of two — the rig may be the blank one, and steering
        // to the car's A2DP is exactly the failure being fixed.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, OTHER, NONE};
        assertThat(pick(types, addrs, true)).isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void knownScoNextToABlankSco_withOurLinkIdentified_stillPicksIt() {
        // Same enumeration, but the capture side named the car as our link.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, OTHER, NONE};
        assertThat(pickOn(types, addrs, OTHER)).isEqualTo(0);
    }

    @Test
    public void identifiedLink_contradictedByTheOnlyNamedSco_leavesToOs() {
        // Partial redaction: the A2DP endpoint's address is withheld but the one
        // SCO endpoint names a DIFFERENT device than the one the mic is captured
        // from. The single-pair fallback must not hand TX to that blank A2DP.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, OTHER};
        assertThat(pickOn(types, addrs, HEADSET)).isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void identifiedLink_confirmedByTheNamedSco_stillUsesTheSinglePairFallback() {
        // Same shape, but the SCO endpoint agrees with the capture side: the
        // blank A2DP is the only candidate and the pairing is unambiguous.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, HEADSET};
        assertThat(pickOn(types, addrs, HEADSET)).isEqualTo(0);
    }

    @Test
    public void sameDeviceEnumeratedTwiceOnSco_isNotAmbiguous() {
        // Some builds list a hands-free device's SCO endpoint more than once;
        // identical addresses are one device, not two.
        int[] types = {TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {HEADSET, HEADSET, "aa:bb:cc:dd:ee:ff"};
        assertThat(pick(types, addrs, true)).isEqualTo(1);
    }

    // -- the case the override exists for -------------------------------------

    // Issue #759 follow-up: mic SCO up on the paired transceiver, which is also
    // reachable via A2DP. "Default" output must be steered to A2DP or TX goes
    // into the SCO channel instead of on the air.
    @Test
    public void ourScoSessionOnTheSameDevice_picksThatDevicesA2dp() {
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, HEADSET, HEADSET};
        assertThat(pick(types, addrs, true)).isEqualTo(1);
    }

    @Test
    public void matchingIsCaseInsensitive() {
        // AudioDeviceInfo.getAddress() casing is not contractual across profiles.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {"aa:bb:cc:dd:ee:ff", HEADSET};
        assertThat(pick(types, addrs, true)).isEqualTo(0);
    }

    @Test
    public void picksTheMatchingA2dp_notMerelyTheFirstOne() {
        // A speaker enumerated ahead of the headset must not win: the rig is
        // listening on the headset's A2DP, not the speaker's.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, HEADSET, HEADSET};
        assertThat(pick(types, addrs, true)).isEqualTo(1);
    }

    // -- no SCO session of ours -----------------------------------------------

    @Test
    public void noAppScoSession_leavesToOs() {
        // The regression this guards: AudioManager enumerates both profiles for
        // any connected hands-free device, up link or not. A rig on USB or the
        // network must not have Default output yanked onto a Bluetooth sink just
        // because a car kit is paired nearby - ScoPolicy deliberately left SCO
        // off in that case.
        int[] types = {TYPE_USB_DEVICE, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, HEADSET, HEADSET};
        assertThat(pick(types, addrs, false))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    // -- different devices ----------------------------------------------------

    @Test
    public void a2dpOnADifferentDeviceThanTheScoLink_leavesToOs() {
        // A car kit holding SCO plus an unrelated A2DP speaker. Steering TX to
        // the speaker is the failure being fixed, not a cure for it.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, HEADSET};
        assertThat(pick(types, addrs, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void multipleCandidatesWithNoMatch_leavesToOs() {
        // Two A2DP sinks, neither on the SCO device: nothing here is known to be
        // the rig, and the ambiguity fallback must not kick in.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {OTHER, OTHER, HEADSET};
        assertThat(pick(types, addrs, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    // -- platforms that withhold addresses ------------------------------------

    @Test
    public void blankAddressesWithASingleCandidatePair_stillSteers() {
        // Some builds report blank addresses even for BT endpoints. With exactly
        // one A2DP and one SCO endpoint the pairing is unambiguous, so the
        // Android 8.1 fix still works there.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, NONE};
        assertThat(pick(types, addrs, true)).isEqualTo(0);
    }

    @Test
    public void nullAddressArray_stillSteersForASingleCandidatePair() {
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        assertThat(pick(types, null, true)).isEqualTo(0);
    }

    @Test
    public void blankAddressesWithTwoA2dpCandidates_leavesToOs() {
        // Without addresses there is no way to tell which sink is the rig, so
        // guessing is not allowed.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, NONE, NONE};
        assertThat(pick(types, addrs, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void blankIsNeverTreatedAsMatchingAnotherBlank() {
        // An equality match on "" must never fire: with a blank A2DP, a known
        // mismatched A2DP and a known SCO, there is nothing legitimate to pick.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        String[] addrs = {NONE, OTHER, HEADSET};
        assertThat(pick(types, addrs, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    // -- nothing to steer to --------------------------------------------------

    @Test
    public void onlyA2dpPresent_leavesToOs() {
        // Paired for music only - this has never been broken, and overriding
        // speakers would be surprising.
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_BLUETOOTH_A2DP};
        String[] addrs = {NONE, HEADSET};
        assertThat(pick(types, addrs, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void onlyScoPresent_leavesToOs() {
        int[] types = {TYPE_BLUETOOTH_SCO, TYPE_WIRED_HEADSET};
        String[] addrs = {HEADSET, NONE};
        assertThat(pick(types, addrs, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void noBluetoothDevices_leavesToOs() {
        int[] types = {TYPE_BUILTIN_SPEAKER, TYPE_USB_DEVICE, TYPE_WIRED_HEADSET};
        String[] addrs = {NONE, NONE, NONE};
        assertThat(pick(types, addrs, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void emptyList_leavesToOs() {
        assertThat(pick(new int[0], new String[0], true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void nullList_leavesToOs() {
        assertThat(pick(null, null, true))
                .isEqualTo(AudioOutputRoutingPolicy.LEAVE_TO_OS);
    }

    @Test
    public void shortAddressArray_doesNotThrow() {
        // Defensive: the arrays are built in lockstep by the caller, but an
        // index-out-of-bounds inside the TX path would kill a transmission.
        int[] types = {TYPE_BLUETOOTH_A2DP, TYPE_BLUETOOTH_SCO};
        assertThat(pick(types, new String[]{HEADSET}, true)).isEqualTo(0);
    }
}
