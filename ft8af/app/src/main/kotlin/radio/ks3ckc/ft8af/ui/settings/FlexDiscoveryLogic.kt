package radio.ks3ckc.ft8af.ui.settings

/**
 * Pure decision for whether the Flex picker should auto-connect without the user
 * doing anything. Extracted so the rule is unit-tested independently of the
 * dialog/discovery plumbing.
 *
 * Auto-connect only when ALL hold:
 *  - discovery has found exactly one radio (the common single-rig case),
 *  - the user hasn't started entering an IP manually (don't hijack manual entry),
 *  - we haven't already fired (one-shot, can't loop or fight a later event),
 *  - the picker opened with an EMPTY list ([startedEmpty]) — so we only auto-connect
 *    to a freshly discovered radio, never to a stale entry cached in the
 *    FlexRadioFactory singleton from earlier this session (that bug made the dialog
 *    appear to "close the instant you open it"),
 *  - no rig is already connected ([rigAlreadyConnected]) — reconnecting a live
 *    session would tear it down and can leave it broken.
 */
internal fun shouldAutoConnectFlex(
    discoveredCount: Int,
    userTypedIp: Boolean,
    alreadyTriggered: Boolean,
    startedEmpty: Boolean,
    rigAlreadyConnected: Boolean,
): Boolean =
    discoveredCount == 1 &&
        !userTypedIp &&
        !alreadyTriggered &&
        startedEmpty &&
        !rigAlreadyConnected
