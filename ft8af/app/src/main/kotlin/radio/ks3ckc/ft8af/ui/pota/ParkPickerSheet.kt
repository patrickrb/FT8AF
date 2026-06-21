package radio.ks3ckc.ft8af.ui.pota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.k1af.ft8af.R
import com.k1af.ft8af.maidenhead.MaidenheadGrid
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import radio.ks3ckc.ft8af.pota.PotaParkRepository
import radio.ks3ckc.ft8af.pota.model.PotaPark
import radio.ks3ckc.ft8af.pota.model.PotaParkWithDistance
import radio.ks3ckc.ft8af.theme.Accent
import radio.ks3ckc.ft8af.theme.BgSurface
import radio.ks3ckc.ft8af.theme.BgSurface2
import radio.ks3ckc.ft8af.theme.Border
import radio.ks3ckc.ft8af.theme.StatusConfirmed
import radio.ks3ckc.ft8af.theme.TextMuted
import radio.ks3ckc.ft8af.theme.TextPrimary
import radio.ks3ckc.ft8af.ui.components.FT8AFBottomSheet

/**
 * Bottom sheet for discovering and selecting POTA park references.
 * Shows recent activations and nearby parks with a text filter.
 */
@Composable
fun ParkPickerSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onParkSelected: (String) -> Unit,
) {
    FT8AFBottomSheet(visible = visible, onDismiss = onDismiss) {
        ParkPickerContent(
            onParkSelected = { ref ->
                onParkSelected(ref)
                onDismiss()
            },
        )
    }
}

@Composable
private fun ParkPickerContent(
    onParkSelected: (String) -> Unit,
) {
    val context = LocalContext.current
    var filter by remember { mutableStateOf("") }

    // Recent parks
    var recentParks by remember { mutableStateOf<List<PotaPark>>(emptyList()) }
    var recentLoading by remember { mutableStateOf(true) }

    // Nearby parks
    var nearbyParks by remember { mutableStateOf<List<PotaParkWithDistance>>(emptyList()) }
    var nearbyLoading by remember { mutableStateOf(true) }
    var locationAvailable by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        // Load recent parks
        recentParks = withContext(Dispatchers.IO) {
            PotaParkRepository.recentParksWithDetails()
        }
        recentLoading = false

        // Load nearby parks
        val location = withContext(Dispatchers.IO) {
            PotaParkRepository.getUserLocation(context)
        }
        if (location != null) {
            nearbyParks = PotaParkRepository.nearbyParks(location.latitude, location.longitude)
        } else {
            locationAvailable = false
        }
        nearbyLoading = false
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
    ) {
        // Search field
        OutlinedTextField(
            value = filter,
            onValueChange = { filter = it },
            placeholder = { Text(stringResource(R.string.pota_search_parks_hint), fontSize = 13.sp) },
            leadingIcon = {
                Icon(
                    Icons.Filled.Search,
                    contentDescription = stringResource(R.string.pota_search_parks),
                    tint = TextMuted,
                    modifier = Modifier.size(18.dp),
                )
            },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            colors = TextFieldDefaults.colors(
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = BgSurface,
                unfocusedContainerColor = BgSurface,
                focusedLabelColor = Accent,
                unfocusedLabelColor = TextMuted,
                focusedIndicatorColor = Accent,
                unfocusedIndicatorColor = Border,
                cursorColor = Accent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        // Recent parks section
        SectionHeader(stringResource(R.string.pota_recent_parks))
        Spacer(Modifier.height(6.dp))

        if (recentLoading) {
            LoadingRow()
        } else {
            val filteredRecent = filterParks(recentParks, filter)
            if (filteredRecent.isEmpty()) {
                EmptyRow(stringResource(R.string.pota_no_recent_parks))
            } else {
                for (park in filteredRecent) {
                    ParkRow(
                        reference = park.reference,
                        name = park.name,
                        subtitle = park.locationDesc,
                        onClick = { onParkSelected(park.reference) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        // Nearby parks section
        SectionHeader(stringResource(R.string.pota_nearby_parks))
        Spacer(Modifier.height(6.dp))

        if (nearbyLoading) {
            LoadingRow()
        } else if (!locationAvailable) {
            EmptyRow(stringResource(R.string.pota_location_unavailable))
        } else {
            val filteredNearby = filterNearbyParks(nearbyParks, filter)
            if (filteredNearby.isEmpty()) {
                EmptyRow(stringResource(R.string.pota_no_nearby_parks))
            } else {
                for (item in filteredNearby) {
                    ParkRow(
                        reference = item.park.reference,
                        name = item.park.name,
                        subtitle = MaidenheadGrid.formatDist(item.distanceKm),
                        onClick = { onParkSelected(item.park.reference) },
                    )
                    Spacer(Modifier.height(4.dp))
                }
            }
        }

        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        color = TextMuted,
        fontSize = 11.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.8.sp,
        modifier = Modifier.padding(start = 4.dp),
    )
}

@Composable
private fun LoadingRow() {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(16.dp),
            color = Accent,
            strokeWidth = 2.dp,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            stringResource(R.string.pota_loading_nearby),
            color = TextMuted,
            fontSize = 12.sp,
        )
    }
}

@Composable
private fun EmptyRow(message: String) {
    Text(
        message,
        color = TextMuted,
        fontSize = 12.sp,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
    )
}

@Composable
private fun ParkRow(
    reference: String,
    name: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(BgSurface, RoundedCornerShape(10.dp))
            .border(1.dp, Border, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Park pill (ref badge)
        Text(
            reference,
            color = StatusConfirmed,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .background(BgSurface2, RoundedCornerShape(6.dp))
                .border(1.dp, Border, RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            fontFamily = FontFamily.Monospace,
        )
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (name.isNotBlank()) {
                Text(
                    name,
                    color = TextPrimary,
                    fontSize = 13.sp,
                    maxLines = 1,
                )
            }
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = TextMuted,
                    fontSize = 11.sp,
                    maxLines = 1,
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Filter logic — extracted for testability
// ---------------------------------------------------------------------------

/** Filter parks by name or reference (case-insensitive substring match). */
internal fun filterParks(parks: List<PotaPark>, query: String): List<PotaPark> {
    if (query.isBlank()) return parks
    val q = query.trim().lowercase()
    return parks.filter { park ->
        park.reference.lowercase().contains(q) || park.name.lowercase().contains(q)
    }
}

/** Filter nearby parks by name or reference (case-insensitive substring match). */
internal fun filterNearbyParks(
    parks: List<PotaParkWithDistance>,
    query: String,
): List<PotaParkWithDistance> {
    if (query.isBlank()) return parks
    val q = query.trim().lowercase()
    return parks.filter { item ->
        item.park.reference.lowercase().contains(q) || item.park.name.lowercase().contains(q)
    }
}
