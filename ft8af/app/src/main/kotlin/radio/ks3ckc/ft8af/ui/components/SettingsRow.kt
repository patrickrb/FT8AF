package radio.ks3ckc.ft8af.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8af.theme.*

/**
 * Settings list row with label, optional description, optional value text,
 * optional toggle, and optional chevron.
 */
@Composable
fun SettingsRow(
    label: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    value: String? = null,
    toggle: Boolean? = null,
    onToggleChange: ((Boolean) -> Unit)? = null,
    showChevron: Boolean = false,
    onClick: (() -> Unit)? = null,
    enabled: Boolean = true,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = label,
                color = TextPrimary,
                fontSize = 14.sp,
            )
            if (description != null) {
                Text(
                    text = description,
                    color = TextMuted,
                    fontSize = 12.sp,
                )
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (value != null) {
                Text(
                    text = value,
                    color = TextMuted,
                    fontSize = 13.sp,
                )
            }
            if (toggle != null && onToggleChange != null) {
                Toggle(
                    checked = toggle,
                    onCheckedChange = onToggleChange,
                    enabled = enabled,
                )
            }
            if (showChevron) {
                FT8AFIcons.Chevron(
                    color = TextFaint,
                    size = 16.dp,
                )
            }
        }
    }
}
