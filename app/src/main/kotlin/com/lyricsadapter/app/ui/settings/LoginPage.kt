package com.lyricsadapter.app.ui.settings

import android.content.ContentValues
import android.content.Context
import android.graphics.BitmapFactory
import android.provider.MediaStore
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyricsadapter.app.AppContainer
import com.lyricsadapter.app.R
import com.lyricsadapter.app.ui.LocalAppColors
import com.lyricsadapter.app.ui.PillButton
import com.lyricsadapter.app.ui.TextAction
import com.lyricsadapter.webdav.CoverBytes
import com.lyricsadapter.webdav.decodeDataUrl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.add

private const val POLL_MS = 2_000L

/** Where a QR login stands. */
private sealed interface QrState {
    data object Loading : QrState
    data class Showing(val image: CoverBytes, val confirming: Boolean) : QrState
    data object Expired : QrState
    data object Done : QrState
    /** [afterScan]: the code was shown, so it is the login that failed, not loading the code. */
    data class Failed(val message: String, val afterScan: Boolean = false) : QrState
}

/**
 * Desktop's QR login for the QQ Music and NetEase plugins, through the same
 * plugin actions (`qq-login-qr-*`, `netease-qr-*`). On a phone the code
 * cannot be scanned from its own screen, so it can be saved to the gallery
 * and scanned from there in the music app.
 */
@Composable
internal fun LoginPage(container: AppContainer, id: String, onDone: () -> Unit) {
    val context = LocalContext.current
    val colors = LocalAppColors.current
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<QrState>(QrState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        state = QrState.Loading
        saved = false
        state = try {
            if (id == "qq") qqLogin(container, onShow = { state = it }) else neteaseLogin(container, onShow = { state = it })
        } catch (error: Exception) {
            QrState.Failed(error.message ?: error.javaClass.simpleName, afterScan = state is QrState.Showing)
        }
        if (state == QrState.Done) onDone()
    }

    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(240.dp).clip(RoundedCornerShape(16.dp)).background(Color.White), contentAlignment = Alignment.Center) {
            when (val current = state) {
                is QrState.Showing -> {
                    val bitmap = remember(current.image) { BitmapFactory.decodeByteArray(current.image.data, 0, current.image.data.size)?.asImageBitmap() }
                    if (bitmap != null) Image(bitmap, contentDescription = stringResource(R.string.qr_title), modifier = Modifier.size(220.dp))
                }
                else -> Unit
            }
        }
        Spacer(Modifier.height(16.dp))
        val message = when (val current = state) {
            QrState.Loading -> stringResource(R.string.qr_loading)
            is QrState.Showing -> stringResource(if (current.confirming) R.string.qr_confirming else R.string.qr_waiting)
            QrState.Expired -> stringResource(R.string.qr_expired)
            QrState.Done -> stringResource(R.string.qr_logged_in)
            is QrState.Failed -> stringResource(if (current.afterScan) R.string.qr_login_failed else R.string.qr_error) + "（${current.message}）"
        }
        BasicText(message, style = TextStyle(color = colors.text, fontSize = 16.sp, textAlign = TextAlign.Center))
        Spacer(Modifier.height(16.dp))
        when (val current = state) {
            is QrState.Showing -> {
                PillButton(stringResource(if (saved) R.string.qr_saved_to_gallery else R.string.qr_save_to_gallery), enabled = !saved, onClick = {
                    scope.launch { saved = withContext(Dispatchers.IO) { saveToGallery(context, current.image) } }
                })
                Spacer(Modifier.height(12.dp))
                BasicText(
                    stringResource(R.string.qr_same_phone_hint, stringResource(if (id == "qq") R.string.source_qq_app else R.string.source_netease_app)),
                    style = TextStyle(color = colors.secondaryText, fontSize = 13.sp, lineHeight = 19.sp, textAlign = TextAlign.Center),
                )
            }
            QrState.Expired, is QrState.Failed -> TextAction(stringResource(R.string.qr_refresh), onClick = { attempt++ })
            else -> Unit
        }
    }
}

/** QQ Music: start a session, then poll its token until the phone confirms. */
private suspend fun qqLogin(container: AppContainer, onShow: (QrState) -> Unit): QrState {
    val plugin = container.plugins.runtime("qq")
    val start = plugin.invoke("qq-login-qr-start", JsonArray(emptyList())) as? JsonObject ?: return QrState.Failed("no answer")
    val token = start.text("token") ?: return QrState.Failed(start.text("error") ?: "no token")
    val image = start.text("qrcode")?.let(::decodeDataUrl) ?: return QrState.Failed("no QR code")
    onShow(QrState.Showing(image, confirming = false))
    while (true) {
        delay(POLL_MS)
        val poll = plugin.invoke("qq-login-qr-poll", buildJsonArray { add(token) }) as? JsonObject ?: continue
        when (poll.text("status")) {
            "waiting" -> Unit
            "confirming" -> onShow(QrState.Showing(image, confirming = true))
            "expired" -> return QrState.Expired
            "done" -> {
                val cookie = poll.text("cookie") ?: return QrState.Failed("no cookie", afterScan = true)
                val credential = poll["credential"]?.takeIf { it is JsonObject }?.toString()
                container.plugins.saveLogin("qq", cookie, credential?.let { mapOf("credential" to it) }.orEmpty())
                return QrState.Done
            }
            else -> return QrState.Failed(poll.text("msg") ?: poll.text("error") ?: "login failed", afterScan = true)
        }
    }
}

/** NetEase: get a key, show its code, poll it (800 expired, 801 waiting, 802 confirming, 803 signed in). */
private suspend fun neteaseLogin(container: AppContainer, onShow: (QrState) -> Unit): QrState {
    val plugin = container.plugins.runtime("netease")
    val keyResult = plugin.invoke("netease-qr-key", JsonArray(emptyList())) as? JsonObject ?: return QrState.Failed("no answer")
    val key = keyResult.text("unikey") ?: return QrState.Failed(keyResult.text("error") ?: "no key")
    val created = plugin.invoke("netease-qr-create", buildJsonArray { add(key) }) as? JsonObject ?: return QrState.Failed("no answer")
    val image = created.text("qrcode")?.let(::decodeDataUrl) ?: return QrState.Failed(created.text("error") ?: "no QR code")
    onShow(QrState.Showing(image, confirming = false))
    while (true) {
        delay(POLL_MS)
        val check = plugin.invoke("netease-qr-check", buildJsonArray { add(key) }) as? JsonObject ?: continue
        when ((check["code"] as? JsonPrimitive)?.intOrNull) {
            801 -> Unit
            802 -> onShow(QrState.Showing(image, confirming = true))
            800 -> return QrState.Expired
            803 -> {
                val cookie = check.text("cookie") ?: return QrState.Failed("no cookie", afterScan = true)
                container.plugins.saveLogin("netease", cookie)
                return QrState.Done
            }
            else -> if (check.text("error") != null) return QrState.Failed(check.text("error").orEmpty(), afterScan = true)
        }
    }
}

/** Saves the QR code to Pictures/LyricsAdapter; no permission is needed for the app's own images. */
private fun saveToGallery(context: Context, image: CoverBytes): Boolean {
    val values = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "lyricsadapter-login-${System.currentTimeMillis()}.png")
        put(MediaStore.Images.Media.MIME_TYPE, image.mimeType)
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/LyricsAdapter")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return false
    return resolver.openOutputStream(uri)?.use { it.write(image.data); true } ?: false
}

private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotEmpty)
