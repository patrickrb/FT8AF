package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8af.theme.TextMuted

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
    // Overrides the default Text(title) rendering below, for a screen whose
    // title is itself an interactive control (e.g. Decode's tappable
    // band/mode label) rather than plain static text. [title] itself goes
    // unused in that case -- it's the plain-text fallback for every OTHER
    // caller, not a hidden accessibility backstop for this one.
    titleContent: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        // weight(1f), not an unconstrained Column: a wide titleContent (e.g.
        // ClickableTopBarTitle's band/mode label, which can run to "14.074 MHz
        // · 20m · FT8") would otherwise push `actions` past the row's own
        // width instead of yielding space to it -- confirmed on a real 1080px
        // phone, where the trailing action icons were pushed fully off-screen.
        // Harmless for every other caller's short plain title, which never
        // comes close to filling the space this leaves it.
        Column(modifier = Modifier.weight(1f)) {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineLarge.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = (-0.01).sp,
                    ),
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            if (subtitle != null) {
                subtitle()
            }
        }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions()
            }
        }
    }
}

/**
 * A [TopBar] title that IS the band/mode control, not a page label that sits
 * above one -- for a screen the operator reaches for constantly (Decode)
 * where an extra tap into Settings/the controls drawer just to retune was the
 * actual complaint this exists to fix. Tapping it should open the same
 * Band & Mode sheet the always-visible [radio.ks3ckc.ft8af.ui.components.BandModeRow]
 * strip opens elsewhere, so there's exactly one picker for this in the app,
 * not two.
 */
@Composable
fun ClickableTopBarTitle(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Rendered after the chevron, OUTSIDE the clickable band/mode button --
    // for a caller that needs something else sharing this same row (e.g.
    // Decode's UTC clock in landscape, where there's no separate line
    // underneath to put it on) without that something becoming part of the
    // button: real-device testing found that nesting it inside the clickable
    // row made tapping the clock open the Band & Mode sheet, and made
    // TalkBack read the clock text as part of the button's own label. Null
    // (the default) renders exactly as before.
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            modifier = Modifier
                .weight(1f, fill = false)
                .clickable(onClick = onClick, role = Role.Button),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                // Let the chevron keep its own space; the label is the part
                // that should give way first if the two still don't both fit
                // after TopBar's weight(1f) has already constrained this
                // whole row.
                modifier = Modifier.weight(1f, fill = false),
            )
            FT8AFIcons.ChevronDown(size = 16.dp, color = TextMuted, strokeWidth = 2f)
        }
        if (trailing != null) {
            trailing()
        }
    }
}

@Composable
fun TopBarSubtitle(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(top = 2.dp),
        color = TextMuted,
        fontSize = 12.sp,
        // The title column now has a fixed weight(1f) width (shared with
        // actions, see TopBar), not an unconstrained one -- a subtitle that
        // used to always fit on one line can now wrap to two, pushing
        // whatever sits below the top bar (e.g. Decode's filter chips) down
        // unpredictably depending on live content (UTC time, decode count).
        // Ellipsizing instead keeps the top bar's height fixed.
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}
