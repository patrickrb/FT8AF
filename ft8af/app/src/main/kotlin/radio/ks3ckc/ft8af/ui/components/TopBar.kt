package radio.ks3ckc.ft8af.ui.components

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8af.theme.TextMuted

@Composable
fun TopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: @Composable (() -> Unit)? = null,
    actionSpacing: Dp = 8.dp,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top,
    ) {
        // Weighted so the title/subtitle column is measured with whatever is left
        // *after* the actions, instead of the other way round: a long subtitle
        // (e.g. the pt-BR "… decodificados neste ciclo") would otherwise eat the
        // full width and push the action icons off the right edge.
        Column(modifier = Modifier.weight(1f, fill = false)) {
            Text(
                text = title,
                style = MaterialTheme.typography.headlineLarge.copy(
                    fontSize = 22.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = (-0.01).sp,
                ),
                color = MaterialTheme.colorScheme.onBackground,
            )
            if (subtitle != null) {
                subtitle()
            }
        }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(actionSpacing)) {
                actions()
            }
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
    )
}
