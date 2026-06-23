package radio.ks3ckc.ft8af.ui.settings

/**
 * Pure decision for whether the Flex picker should auto-connect without the user
 * doing anything. Extracted so the rule is unit-tested independently of the
 * dialog/discovery plumbing.
 *
 * We auto-connect only when discovery has found exactly one radio (the common
 * single-rig case — connect with zero typing), the user hasn't started entering
 * an IP manually (don't hijack a deliberate manual entry), and we haven't already
 * fired (one-shot, so it can't loop or fight a second discovery event).
 */
internal fun shouldAutoConnectFlex(
    discoveredCount: Int,
    userTypedIp: Boolean,
    alreadyTriggered: Boolean,
): Boolean = discoveredCount == 1 && !userTypedIp && !alreadyTriggered
