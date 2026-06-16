package radio.ks3ckc.ft8af.ui.decode

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import radio.ks3ckc.ft8af.qrz.QrzWebClient
import radio.ks3ckc.ft8af.qrz.QrzXmlClient
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgElev
import radio.ks3ckc.ft8af.theme.GeistMonoFamily

/**
 * Circular avatar for a callsign. Resolves the profile photo via the
 * public QRZ profile page (works for free QRZ accounts); falls back to
 * the XML API when configured, and finally to a two-letter initials
 * chip when no image is available.
 */
@Composable
fun QrzAvatar(
    callsign: String,
    size: Dp,
    fallbackText: String,
) {
    var imageUrl by remember(callsign) { mutableStateOf<String?>(null) }
    val context = LocalContext.current

    LaunchedEffect(callsign) {
        // Public profile page works for any account (no subscription
        // required). If it doesn't return anything, try the XML API as a
        // secondary path for users with a QRZ XML subscription configured.
        imageUrl = QrzWebClient.fetchProfileImage(callsign)
            ?: QrzXmlClient.lookup(callsign)?.imageUrl
    }

    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(BgElev),
        contentAlignment = Alignment.Center,
    ) {
        val url = imageUrl
        if (!url.isNullOrEmpty()) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(url)
                    .crossfade(true)
                    .build(),
                contentDescription = "$callsign profile photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(size)
                    .clip(CircleShape),
            )
        } else {
            Text(
                text = fallbackText,
                color = Accent,
                fontFamily = GeistMonoFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
            )
        }
    }
}
