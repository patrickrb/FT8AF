package radio.ks3ckc.ft8us.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import radio.ks3ckc.ft8us.theme.*

enum class QsoStatus(
    val label: String,
    val color: Color,
    val bgColor: Color,
    val borderColor: Color,
) {
    NEW(
        "NEW DXCC",
        StatusNew,
        Color(0x1FC084FC),  // rgba(192,132,252,0.12)
        Color(0x47C084FC),  // rgba(192,132,252,0.28)
    ),
    NEW_GRID(
        "NEW GRID",
        StatusWarn,
        Color(0x1FFACC15),  // rgba(250,204,21,0.12)
        Color(0x47FACC15),  // rgba(250,204,21,0.28)
    ),
    NEW_BAND(
        "NEW BAND",
        Signal,
        Color(0x1F5CD6E8),  // rgba(92,214,232,0.12)
        Color(0x475CD6E8),  // rgba(92,214,232,0.28)
    ),
    POTA(
        "POTA",
        StatusConfirmed,
        Color(0x1F4ADE80),  // rgba(74,222,128,0.12)
        Color(0x474ADE80),  // rgba(74,222,128,0.28)
    ),
    NEW_POTA(
        "NEW POTA",
        StatusNeeded,
        Color(0x1FFFAF5E),  // amber — a park you haven't hunted yet
        Color(0x47FFAF5E),
    ),
    SOTA(
        "SOTA",
        StatusConfirmed,
        Color(0x1F4ADE80),
        Color(0x474ADE80),
    ),
    NEEDED(
        "NEEDED",
        StatusNeeded,
        Color(0x1FFFAF5E),
        Color(0x47FFAF5E),
    ),
    WORKED(
        "WORKED",
        StatusWorked,
        Color(0x1A5CD6E8),  // rgba(92,214,232,0.10)
        Color(0x385CD6E8),  // rgba(92,214,232,0.22)
    ),
    CONFIRMED(
        "CONFIRMED",
        StatusConfirmed,
        Color(0x1A4ADE80),
        Color(0x384ADE80),
    ),
    CQ(
        "CQ",
        StatusCq,
        Color(0x1FFFAF5E),
        Color(0x47FFAF5E),
    ),
    PENDING(
        "PENDING",
        TextMuted,
        Color(0x1A8A96B1),
        Color(0x388A96B1),
    );
}

@Composable
fun StatusPill(
    status: QsoStatus,
    compact: Boolean = false,
    label: String? = null,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(999.dp)
    val displayLabel = label ?: status.label

    Row(
        modifier = modifier
            .height(if (compact) 18.dp else 22.dp)
            .background(status.bgColor, shape)
            .border(1.dp, status.borderColor, shape)
            .padding(horizontal = if (compact) 6.dp else 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        // Glowing dot
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(status.color)
        )
        Text(
            text = displayLabel,
            color = status.color,
            fontSize = if (compact) 9.5.sp else 11.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.04.sp,
        )
    }
}
