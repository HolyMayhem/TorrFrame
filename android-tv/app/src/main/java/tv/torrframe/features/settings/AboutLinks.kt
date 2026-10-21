package tv.torrframe.features.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tv.torrframe.R
import tv.torrframe.phoneRemote.discovery.QrCodeGenerator
import tv.torrframe.ui.components.TvText
import tv.torrframe.ui.theme.Muted

@Composable internal fun AboutLinks() {
    AboutQr("https://pay.cloudtips.ru/p/8b994ba5", R.string.about_support, R.string.about_support_hint)
    AboutQr("https://t.me/holymayhem", R.string.about_contact, R.string.about_contact_hint)
}

@Composable private fun AboutQr(url: String, title: Int, hint: Int) {
    val image by produceState<android.graphics.Bitmap?>(null, url) {
        value = withContext(Dispatchers.Default) { QrCodeGenerator.bitmap(url) }
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(144.dp).background(androidx.compose.ui.graphics.Color.White, RoundedCornerShape(12.dp)).padding(4.dp),
            contentAlignment = Alignment.Center) {
            image?.let { Image(it.asImageBitmap(), contentDescription = stringResource(title), modifier = Modifier.fillMaxSize()) }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TvText(stringResource(title), style = MaterialTheme.typography.titleMedium, translate = false)
            TvText(stringResource(hint), color = Muted, style = MaterialTheme.typography.bodySmall, translate = false)
        }
    }
}
