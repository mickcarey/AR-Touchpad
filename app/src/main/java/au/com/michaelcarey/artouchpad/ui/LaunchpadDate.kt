package au.com.michaelcarey.artouchpad.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Column
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.core.content.ContextCompat
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
internal fun LaunchpadDate() {
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0]
    var now by remember { mutableStateOf(ZonedDateTime.now()) }
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) { now = ZonedDateTime.now() }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val use24Hours = DateFormat.is24HourFormat(context)
    val (time, date) = remember(now, locale, use24Hours) { launchpadTimeAndDate(now, locale, use24Hours) }
    Column {
        Text(time, Modifier.testTag("launchpad-time"), style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Medium)
        Text(date, Modifier.testTag("launchpad-date"), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

internal fun launchpadTimeAndDate(now: ZonedDateTime, locale: Locale, use24Hours: Boolean): Pair<String, String> {
    val english = locale.language == "en"
    val datePattern = if (english) "EEE, d MMM yyyy" else DateFormat.getBestDateTimePattern(locale, "yMMMEEEd")
    val timePattern = if (english && !use24Hours) "h:mma"
        else DateFormat.getBestDateTimePattern(locale, if (use24Hours) "Hm" else "hma")
    val date = now.format(DateTimeFormatter.ofPattern(datePattern, locale))
    val time = now.format(DateTimeFormatter.ofPattern(timePattern, locale))
    return (if (english) time.lowercase(locale) else time) to date
}
