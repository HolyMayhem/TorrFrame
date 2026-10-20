package tv.torrframe.features.player

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** MIME mapping follows Just Player's SubtitleUtils (Unlicense). Only explicit user attachments are read. */
object ExternalSubtitles {
    fun mime(name: String): String? = when (name.substringBefore('?').substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "srt" -> MimeTypes.APPLICATION_SUBRIP
        "ssa", "ass" -> MimeTypes.TEXT_SSA
        "vtt" -> MimeTypes.TEXT_VTT
        "ttml", "xml", "dfxp" -> MimeTypes.APPLICATION_TTML
        else -> null
    }
    @androidx.annotation.OptIn(UnstableApi::class)
    suspend fun load(context: Context, uri: Uri, russian: Boolean): MediaItem.SubtitleConfiguration = withContext(Dispatchers.IO) {
        require(uri.scheme in setOf("content", "http", "https"))
        var name = uri.lastPathSegment.orEmpty()
        if (uri.scheme == "content") context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) name = it.getString(0).orEmpty()
        }
        val type = requireNotNull(mime(name)) { "Unsupported subtitle file" }
        val source = if (uri.scheme == "content") {
            val bytes = requireNotNull(context.contentResolver.openInputStream(uri)).use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) { val n = input.read(buffer); if (n < 0) break; require(output.size() + n <= 8 * 1024 * 1024); output.write(buffer, 0, n) }
                output.toByteArray()
            }
            val text = decode(bytes, russian)
            val folder = File(context.cacheDir, "player-subtitles").apply { mkdirs() }
            folder.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 7 * 86_400_000L }?.forEach(File::delete)
            Uri.fromFile(File.createTempFile("subtitle-", "." + name.substringAfterLast('.'), folder).apply { writeText(text) })
        } else uri
        MediaItem.SubtitleConfiguration.Builder(source).setMimeType(type).setLabel(name)
            .setLanguage("und").setRoleFlags(C.ROLE_FLAG_SUBTITLE).setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()
    }
    fun decode(bytes: ByteArray, russian: Boolean): String {
        if (bytes.size >= 2 && (bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() || bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte()))
            return bytes.toString(Charsets.UTF_16).removePrefix("\uFEFF")
        return runCatching { Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString() }
            .getOrElse { bytes.toString(charset(if (russian) "windows-1251" else "windows-1252")) }.removePrefix("\uFEFF")
    }
}
