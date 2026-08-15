package app.gamenative.ui.component

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.gamenative.NetworkMonitor
import app.gamenative.R
import androidx.compose.runtime.collectAsState

/**
 * Passive console chrome for screens that hide the Android status bar. It is
 * deliberately not focusable: directional input always stays in the library.
 */
@Composable
fun ConsoleStatusIndicators(modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val hasInternet by NetworkMonitor.hasInternet.collectAsState()
    var batteryPercent by remember { mutableIntStateOf(-1) }
    var isCharging by remember { mutableStateOf(false) }

    DisposableEffect(context) {
        fun update(intent: Intent?) {
            if (intent == null) return
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            batteryPercent = if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
            isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = update(intent)
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        update(context.registerReceiver(receiver, filter))
        onDispose { context.unregisterReceiver(receiver) }
    }

    val batteryTint = when {
        batteryPercent in 0..15 -> MaterialTheme.colorScheme.error
        isCharging -> MaterialTheme.colorScheme.tertiary
        else -> Color.White
    }
    val batteryIcon = when {
        batteryPercent in 0..15 -> Icons.Default.BatteryAlert
        isCharging -> Icons.Default.BatteryChargingFull
        else -> Icons.Default.BatteryFull
    }

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .background(Color.Black.copy(alpha = 0.36f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            imageVector = if (hasInternet) Icons.Default.Wifi else Icons.Default.WifiOff,
            contentDescription = stringResource(
                if (hasInternet) R.string.status_network_online else R.string.status_network_offline,
            ),
            tint = if (hasInternet) Color.White else MaterialTheme.colorScheme.error,
            modifier = Modifier.size(20.dp),
        )
        Icon(
            imageVector = batteryIcon,
            contentDescription = stringResource(R.string.status_battery, batteryPercent.coerceAtLeast(0)),
            tint = batteryTint,
            modifier = Modifier.size(22.dp),
        )
        if (batteryPercent >= 0) {
            Text(
                text = "$batteryPercent%",
                style = MaterialTheme.typography.labelLarge,
                color = batteryTint,
            )
        }
    }
}
