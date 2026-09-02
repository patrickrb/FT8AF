package com.k1af.ft8af.bluetooth;

/**
 * Decides whether the TX audio path should override a "Default" output selection
 * with the paired Bluetooth A2DP device. Pure ints — no Android runtime — so the
 * whole decision is unit-testable.
 *
 * <p>Why this exists (issue #759 follow-up on Android 8.1): PR #772 makes the
 * SCO link come up reliably so the mic captures over Bluetooth. Once it does,
 * a tester reported that with both audio input and audio output set to
 * "Default", TX audio no longer reaches the transceiver — but manually picking
 * the paired device's A2DP profile as the output makes TX work again. Android's
 * routing keeps the {@code USAGE_MEDIA} stream on the SCO (narrowband
 * hands-free) path while SCO is up, and the paired transceiver only listens on
 * the A2DP profile for the FT8 tone, so TX goes into the void.
 *
 * <p>Rule: when the user chose "Default" output, <em>this app</em> currently holds
 * a SCO session, and the output list contains an A2DP endpoint belonging to the
 * <em>same</em> Bluetooth device as a SCO endpoint, steer TX to that A2DP
 * endpoint. Otherwise leave the OS routing alone.
 *
 * <p>All three conditions matter, and the first version of this class checked
 * only "an A2DP type and a SCO type are both enumerated" (Copilot review on
 * #790):
 * <ul>
 *   <li>{@code AudioManager} enumerates both profiles for any connected
 *       hands-free device whether or not a SCO link is actually up, so device
 *       types alone cannot tell a live SCO session from an idle car kit sitting
 *       in the list. {@link ScoPolicy} deliberately leaves SCO off outside
 *       Bluetooth audio modes, and a rig on USB or the network must not have its
 *       Default output yanked onto a Bluetooth sink because a car is paired
 *       nearby.</li>
 *   <li>The enumerated profiles need not belong to the same device — a phone can
 *       have a car kit on SCO and a speaker on A2DP at once — and steering TX to
 *       an unrelated A2DP sink sends the FT8 tone somewhere the rig cannot hear
 *       it, which is the failure being fixed, not a cure for it.</li>
 * </ul>
 *
 * <p>The SCO state must come from the app's own {@code ScoLinkTracker} (via
 * {@code ScoLinkCoordinator.isLinkUpOrPending()}), not from
 * {@code AudioManager.isBluetoothScoOn()} — see the note on {@link ScoPolicy}
 * for why that getter is not trusted anywhere in this codebase.
 */
public final class AudioOutputRoutingPolicy {

    private AudioOutputRoutingPolicy() {
    }

    /**
     * Mirror of {@code android.media.AudioDeviceInfo.TYPE_BLUETOOTH_A2DP}
     * (stable framework constant = 8), kept as a literal so this class stays
     * Android-free.
     */
    public static final int TYPE_BLUETOOTH_A2DP = 8;

    /**
     * Mirror of {@code android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO}
     * (stable framework constant = 7).
     */
    public static final int TYPE_BLUETOOTH_SCO = 7;

    /**
     * Sentinel from {@link #pickDefaultOutputIndex} for "let the OS pick".
     */
    public static final int LEAVE_TO_OS = -1;

    /**
     * When the user picked "Default" output, decide which enumerated device (by
     * index into {@code deviceTypes}) to hand to {@code AudioTrack.setPreferredDevice}.
     *
     * @param deviceTypes        the {@code AudioDeviceInfo.getType()} of every
     *                           currently connected output device, in enumeration
     *                           order
     * @param deviceAddresses    the matching {@code AudioDeviceInfo.getAddress()}
     *                           values — the Bluetooth MAC for a BT endpoint, and
     *                           blank for everything else. May be {@code null},
     *                           and individual entries may be null or blank on
     *                           platforms that withhold them.
     * @param appScoSessionActive whether <em>this app</em> held a SCO session for
     *                           the rig when this over was keyed, per its own
     *                           {@code ScoLinkTracker} (see {@code TxScoLatch}).
     *                           False short-circuits: with no SCO of ours
     *                           stealing the media route there is nothing to
     *                           steer around.
     * @param activeScoAddress   the Bluetooth address of the device that SCO link
     *                           is on (the mic's routed capture device at keying
     *                           time), or null when the platform withheld it.
     *                           With it, only that device's A2DP endpoint is
     *                           ever chosen. Without it, the SCO endpoints must
     *                           all agree on one device: an idle car kit can
     *                           enumerate a SCO endpoint next to the rig's, and
     *                           guessing the first matching pair could send TX
     *                           to the car (Copilot review on #790).
     * @return the index of the A2DP device to prefer, or {@link #LEAVE_TO_OS}
     *         when the OS's own default routing is fine
     */
    public static int pickDefaultOutputIndex(int[] deviceTypes,
                                             String[] deviceAddresses,
                                             boolean appScoSessionActive,
                                             String activeScoAddress) {
        if (!appScoSessionActive || deviceTypes == null) return LEAVE_TO_OS;

        int a2dpCount = 0;
        int scoCount = 0;
        int scoUnknownCount = 0;
        int firstA2dpIdx = LEAVE_TO_OS;
        boolean anyScoAddressKnown = false;
        String soleScoAddress = null;
        boolean scoDevicesDiffer = false;
        boolean activeSeenOnSco = false;
        for (int i = 0; i < deviceTypes.length; i++) {
            if (deviceTypes[i] == TYPE_BLUETOOTH_A2DP) {
                a2dpCount++;
                if (firstA2dpIdx == LEAVE_TO_OS) firstA2dpIdx = i;
            } else if (deviceTypes[i] == TYPE_BLUETOOTH_SCO) {
                scoCount++;
                String scoAddr = addressAt(deviceAddresses, i);
                if (isKnownAddress(scoAddr)) {
                    anyScoAddressKnown = true;
                    if (isKnownAddress(activeScoAddress) && activeScoAddress.equalsIgnoreCase(scoAddr)) {
                        activeSeenOnSco = true;
                    }
                    if (soleScoAddress == null) {
                        soleScoAddress = scoAddr;
                    } else if (!soleScoAddress.equalsIgnoreCase(scoAddr)) {
                        scoDevicesDiffer = true;
                    }
                } else {
                    scoUnknownCount++;
                }
            }
        }
        if (a2dpCount == 0 || scoCount == 0) return LEAVE_TO_OS;

        // Which SCO endpoint is ours. The routed capture device is authoritative;
        // without it, the identity is only trustworthy when every SCO endpoint
        // can be identified as the same device. Two known addresses that differ
        // are two devices — and so is one known address next to a blank one: the
        // blank endpoint may well be the rig, and treating the named one as "the"
        // device would route TX to a car kit (Copilot review on #790).
        String ourSco;
        if (isKnownAddress(activeScoAddress)) {
            ourSco = activeScoAddress;
        } else if (scoDevicesDiffer || (anyScoAddressKnown && scoUnknownCount > 0)) {
            // Two or more hands-free devices (or endpoints we cannot tell apart)
            // and no word on which carries our link. Steering to whichever pair
            // enumerates first could put the FT8 tone into a car kit; leave the
            // routing to the OS instead.
            return LEAVE_TO_OS;
        } else {
            ourSco = soleScoAddress;
        }

        // Preferred path: the A2DP endpoint on the same Bluetooth device as our
        // SCO link. That is the device whose hands-free link is holding the media
        // route hostage, and the only sink the rig is listening on.
        if (ourSco != null) {
            for (int i = 0; i < deviceTypes.length; i++) {
                if (deviceTypes[i] != TYPE_BLUETOOTH_A2DP) continue;
                if (ourSco.equalsIgnoreCase(addressAt(deviceAddresses, i))) {
                    return i;
                }
            }
        }

        // The capture side named our device but the output side names only
        // OTHER devices on SCO: the enumeration contradicts the routed capture,
        // and the withheld-address fallback below would hand TX to a blank A2DP
        // endpoint that belongs to one of those other devices. Not ours to guess.
        if (isKnownAddress(activeScoAddress) && anyScoAddressKnown && !activeSeenOnSco) {
            return LEAVE_TO_OS;
        }

        // Fallback for platforms that withhold endpoint addresses: with exactly
        // one A2DP and exactly one SCO endpoint the pairing is unambiguous, so
        // steering is still safe. Only when the addresses genuinely told us
        // nothing — if both are known and simply differ, they are different
        // devices and we must not touch the routing.
        boolean addressesUninformative =
                !isKnownAddress(addressAt(deviceAddresses, firstA2dpIdx)) || !anyScoAddressKnown;
        if (a2dpCount == 1 && scoCount == 1 && addressesUninformative) {
            return firstA2dpIdx;
        }
        return LEAVE_TO_OS;
    }

    /** {@code deviceAddresses[i]}, tolerating a null array or a short one. */
    private static String addressAt(String[] deviceAddresses, int i) {
        if (deviceAddresses == null || i < 0 || i >= deviceAddresses.length) return null;
        return deviceAddresses[i];
    }

    /**
     * Whether an address actually identifies a device. Non-Bluetooth endpoints
     * report {@code ""}, and some builds report blanks even for BT endpoints, so
     * a blank must never be treated as "matches another blank".
     */
    private static boolean isKnownAddress(String address) {
        return address != null && !address.trim().isEmpty();
    }
}
