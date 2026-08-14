package to.eyed.spettro.chat.ui.chat

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.composables.icons.lucide.Code
import com.composables.icons.lucide.File
import com.composables.icons.lucide.FileText
import com.composables.icons.lucide.Lucide
import com.composables.icons.lucide.Maximize2
import com.composables.icons.lucide.Share2
import com.composables.icons.lucide.X
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.data.artifacts.ArtifactRef
import to.eyed.spettro.chat.ui.components.GhostIconButton
import to.eyed.spettro.chat.ui.components.surfaceHigh
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii
import to.eyed.spettro.chat.vm.ToolRunUi

/**
 * Generated artifacts under an assistant message: HTML views render inline
 * in a sandboxed WebView card; files and PDFs render as chips that open or
 * share through the FileProvider. References are parsed back out of the
 * stored tool outputs, so history renders identically after a restart.
 */
@Composable
internal fun ArtifactStrip(tools: List<ToolRunUi>) {
    val refs = remember(tools) {
        tools.filter { !it.running && !it.failed }
            .flatMap { ArtifactRef.parseAll(it.output) }
            .distinct()
    }
    if (refs.isEmpty()) return
    val htmlRefs = refs.filter { it.kind == ArtifactRef.KIND_HTML }
    val fileRefs = refs.filter { it.kind != ArtifactRef.KIND_HTML }

    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        htmlRefs.forEach { HtmlArtifactCard(it) }
        fileRefs.forEach { FileArtifactChip(it) }
    }
}

@Composable
private fun FileArtifactChip(ref: ArtifactRef) {
    val context = LocalContext.current
    val store = remember(context) { AppContainer.get(context).artifacts }
    val exists = remember(ref) { store.file(ref).exists() }
    Row(
        Modifier
            .surfaceHigh(RoundedCornerShape(Radii.row))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = exists,
            ) { store.open(ref) }
            .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (ref.kind == ArtifactRef.KIND_PDF) Lucide.FileText else Lucide.File,
            contentDescription = null,
            Modifier.size(15.dp),
            tint = Ink.I500,
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(
                ref.fileName,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                if (exists) "Tap to open" else "File no longer on this device",
                fontSize = 10.sp,
                color = Ink.I500,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (exists) {
            GhostIconButton(
                Lucide.Share2, "Share file",
                onClick = { store.share(ref) },
                size = 32.dp, iconSize = 14.dp, tint = Ink.I500,
            )
        }
    }
}

@Composable
private fun HtmlArtifactCard(ref: ArtifactRef) {
    val context = LocalContext.current
    val store = remember(context) { AppContainer.get(context).artifacts }
    val html by produceState<String?>(initialValue = null, ref) {
        value = withContext(Dispatchers.IO) {
            runCatching { store.file(ref).takeIf { it.exists() }?.readText() }.getOrNull()
        }
    }
    var fullscreen by remember { mutableStateOf(false) }

    Column(
        Modifier
            .fillMaxWidth()
            .surfaceHigh(RoundedCornerShape(Radii.card)),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Lucide.Code, contentDescription = null, Modifier.size(14.dp), tint = Ink.I500)
            Spacer(Modifier.width(8.dp))
            Text(
                ref.fileName.removeSuffix(".html").removeSuffix(".htm"),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = Ink.I100,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            GhostIconButton(
                Lucide.Share2, "Share",
                onClick = { store.share(ref) },
                size = 32.dp, iconSize = 14.dp, tint = Ink.I500,
            )
            GhostIconButton(
                Lucide.Maximize2, "Expand",
                onClick = { fullscreen = true },
                size = 32.dp, iconSize = 14.dp, tint = Ink.I500,
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(320.dp)
                .clip(RoundedCornerShape(bottomStart = Radii.card, bottomEnd = Radii.card)),
        ) {
            when (val content = html) {
                null -> Text(
                    "This view is no longer on this device.",
                    fontSize = 12.sp, color = Ink.I500,
                    modifier = Modifier.align(Alignment.Center).padding(16.dp),
                )
                else -> SandboxedWebView(content)
            }
        }
    }

    if (fullscreen && html != null) {
        Dialog(
            onDismissRequest = { fullscreen = false },
            properties = DialogProperties(usePlatformDefaultWidth = false),
        ) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        ref.fileName.removeSuffix(".html").removeSuffix(".htm"),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = Ink.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 8.dp),
                    )
                    GhostIconButton(
                        Lucide.X, "Close",
                        onClick = { fullscreen = false },
                        size = 40.dp, iconSize = 18.dp, tint = Ink.I100,
                    )
                }
                Box(Modifier.fillMaxSize()) { SandboxedWebView(html!!) }
            }
        }
    }
}

/**
 * The rendering surface for model-written HTML. Deliberately locked down:
 * JavaScript runs (that's the point — charts, interactivity) but every
 * outbound path is closed: network loads blocked, file and content access
 * off, no JS bridges installed, navigation swallowed. The page can only
 * draw itself.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun SandboxedWebView(html: String) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                settings.javaScriptEnabled = true
                settings.blockNetworkLoads = true
                settings.blockNetworkImage = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                settings.javaScriptCanOpenWindowsAutomatically = false
                settings.setSupportMultipleWindows(false)
                settings.setGeolocationEnabled(false)
                isVerticalScrollBarEnabled = true
                webViewClient = object : WebViewClient() {
                    // The card is a rendering surface, not a browser.
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?) = true
                }
                setBackgroundColor(android.graphics.Color.TRANSPARENT)
            }
        },
        update = { webView ->
            if (webView.tag != html) {
                webView.tag = html
                webView.loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
            }
        },
    )
}
