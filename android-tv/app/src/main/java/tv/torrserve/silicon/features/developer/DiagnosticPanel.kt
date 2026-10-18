package tv.torrserve.silicon.features.developer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import tv.torrserve.silicon.R
import tv.torrserve.silicon.core.network.*
import tv.torrserve.silicon.core.performance.*
import tv.torrserve.silicon.ui.components.TvText
import tv.torrserve.silicon.ui.theme.*
import java.text.DateFormat
import java.util.Date

@Composable fun DiagnosticPanel(diagnostics: NetworkDiagnostics, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val issues by diagnostics.issues.collectAsStateWithLifecycle()
    val metrics by produceState<ProcessMetrics?>(null, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            // New baseline on each resume; never average CPU over the time spent in the background.
            val sampler = ProcessMetricsSampler()
            while (isActive) {
                value = withContext(Dispatchers.Default) { sampler.sample() }
                delay(2000)
            }
        }
    }
    val unavailable = stringResource(R.string.diagnostic_unavailable)
    val style = MaterialTheme.typography.bodySmall.copy(fontSize = 9.sp, lineHeight = 12.sp)
    Column(modifier.fillMaxWidth().testTag("developer:diagnostics")
        .background(SurfaceSecondary, RoundedCornerShape(8.dp)).border(1.dp, Outline, RoundedCornerShape(8.dp))
        .padding(horizontal = 10.dp, vertical = 5.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        val current = metrics
        val memory = current?.pssKb?.let { stringResource(R.string.diagnostic_memory, it / 1024) } ?: "RAM PSS: $unavailable"
        val heap = current?.let { stringResource(R.string.diagnostic_heap, it.javaUsedBytes / 1_048_576, it.javaMaxBytes / 1_048_576) }.orEmpty()
        val cpu = current?.cpuPercent?.let { stringResource(R.string.diagnostic_cpu, it,
            pluralStringResource(R.plurals.diagnostic_cores, current.cores, current.cores)) } ?: "CPU: $unavailable"
        val graphics = current?.graphicsKb?.let { stringResource(R.string.diagnostic_graphics, it / 1024) }
            ?: stringResource(R.string.diagnostic_graphics_missing)
        TvText("DEV · $memory · $heap · $cpu · $graphics", color = Muted, style = style,
            maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false, modifier = Modifier.testTag("developer:metrics"))
        val issue = issues.firstOrNull()
        if (issue == null) TvText(stringResource(R.string.diagnostic_no_errors), color = Muted, style = style,
            modifier = Modifier.testTag("developer:network"), translate = false)
        else {
            val description = when (issue.kind) {
                NetworkFailureKind.Dns -> stringResource(R.string.diagnostic_dns)
                NetworkFailureKind.Tls -> stringResource(R.string.diagnostic_tls)
                NetworkFailureKind.Timeout -> stringResource(R.string.diagnostic_timeout)
                NetworkFailureKind.Connection -> stringResource(R.string.diagnostic_connection)
                NetworkFailureKind.Response -> stringResource(R.string.diagnostic_response)
                NetworkFailureKind.Http -> when (issue.status) {
                    401 -> stringResource(R.string.diagnostic_auth)
                    403 -> stringResource(R.string.diagnostic_denied)
                    429 -> stringResource(R.string.diagnostic_rate)
                    else -> stringResource(R.string.diagnostic_http)
                }
            }
            val time = remember(issue.time) { DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(issue.time)) }
            val code = if (issue.status > 0) "HTTP ${issue.status}" else issue.kind.name.uppercase()
            val simulated = if (issue.simulated) stringResource(R.string.diagnostic_simulated) else ""
            TvText("$time · ${issue.host} · $code$simulated · $description", color = TextPrimary, style = style,
                maxLines = 2, overflow = TextOverflow.Ellipsis, translate = false, modifier = Modifier.testTag("developer:network"))
        }
    }
}
