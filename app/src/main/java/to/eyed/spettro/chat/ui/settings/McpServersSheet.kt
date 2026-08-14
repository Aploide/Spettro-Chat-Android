package to.eyed.spettro.chat.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.mcp.McpServerConfig
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.GlassToggle
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.components.PrimaryButton
import to.eyed.spettro.chat.ui.components.surfaceCard
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/**
 * Manage remote MCP servers: list with enable toggles, per-server tool count
 * or error, refresh/edit/delete, and an inline add/edit form.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersSheet(
    servers: List<McpServerConfig>,
    toolCounts: Map<String, Int>,
    errors: Map<String, String>,
    onSave: (McpServerConfig) -> Unit,
    onRemove: (String) -> Unit,
    onSetEnabled: (String, Boolean) -> Unit,
    onRefresh: (String) -> Unit,
    newId: () -> String,
    onDismiss: () -> Unit,
) {
    // null = list view; a config = the add/edit form (blank id fields = new).
    var editing by remember { mutableStateOf<McpServerConfig?>(null) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Ink.SurfaceLow,
        contentColor = Ink.I100,
        dragHandle = null,
        shape = RoundedCornerShape(topStart = Radii.sheet, topEnd = Radii.sheet),
    ) {
        Column(Modifier.fillMaxWidth()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    if (editing == null) "MCP servers" else "Configure server",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink.White,
                    modifier = Modifier.weight(1f),
                )
                if (editing == null) {
                    GlassButton("Add server", onClick = {
                        editing = McpServerConfig(id = newId(), name = "", url = "")
                    })
                }
            }
            Hairline()

            Column(
                Modifier
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 12.dp),
            ) {
                val current = editing
                if (current != null) {
                    ServerForm(
                        initial = current,
                        onCancel = { editing = null },
                        onSave = {
                            onSave(it)
                            editing = null
                        },
                    )
                } else if (servers.isEmpty()) {
                    Text(
                        "No servers yet. MCP servers add their tools to every chat — " +
                            "the assistant can call them once you approve.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = Ink.I500,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    servers.forEachIndexed { i, server ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        ServerRow(
                            server = server,
                            toolCount = toolCounts[server.id],
                            error = errors[server.id],
                            onSetEnabled = { onSetEnabled(server.id, it) },
                            onRefresh = { onRefresh(server.id) },
                            onEdit = { editing = server },
                            onRemove = { onRemove(server.id) },
                        )
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun ServerRow(
    server: McpServerConfig,
    toolCount: Int?,
    error: String?,
    onSetEnabled: (Boolean) -> Unit,
    onRefresh: () -> Unit,
    onEdit: () -> Unit,
    onRemove: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .surfaceCard(RoundedCornerShape(Radii.card), fill = Ink.Surface)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(server.name.ifBlank { "(unnamed)" }, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Ink.White)
                Spacer(Modifier.height(2.dp))
                Text(server.url, fontSize = 11.sp, color = Ink.I500, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            GlassToggle(server.enabled, onSetEnabled)
        }
        Spacer(Modifier.height(6.dp))
        Text(
            when {
                error != null -> "Error: $error"
                toolCount != null -> "$toolCount ${if (toolCount == 1) "tool" else "tools"} available"
                else -> "Not connected yet — tools are listed on the next message or via Refresh."
            },
            fontSize = 11.sp,
            lineHeight = 15.sp,
            color = if (error != null) Ink.Danger else Ink.I500,
        )
        Spacer(Modifier.height(10.dp))
        Row {
            GlassButton("Refresh", onClick = onRefresh)
            Spacer(Modifier.width(8.dp))
            GlassButton("Edit", onClick = onEdit)
            Spacer(Modifier.width(8.dp))
            var confirming by remember { mutableStateOf(false) }
            GlassButton(
                if (confirming) "Confirm" else "Remove",
                onClick = { if (confirming) onRemove() else confirming = true },
                textColor = Ink.Danger,
            )
        }
    }
}

@Composable
private fun ServerForm(
    initial: McpServerConfig,
    onCancel: () -> Unit,
    onSave: (McpServerConfig) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var bearer by remember { mutableStateOf(initial.bearerToken) }
    var headerName by remember { mutableStateOf(initial.headerName) }
    var headerValue by remember { mutableStateOf(initial.headerValue) }

    Column(Modifier.fillMaxWidth()) {
        FormField("Name", name, { name = it }, placeholder = "My server")
        FormField("URL", url, { url = it }, placeholder = "https://mcp.example.com/mcp", keyboard = KeyboardType.Uri)
        FormField("Bearer token (optional)", bearer, { bearer = it })
        FormField("Custom header name (optional)", headerName, { headerName = it }, placeholder = "X-Api-Key")
        FormField("Custom header value", headerValue, { headerValue = it })
        Spacer(Modifier.height(14.dp))
        val valid = name.isNotBlank() && (url.startsWith("http://") || url.startsWith("https://"))
        Row {
            PrimaryButton(
                text = "Save",
                enabled = valid,
                onClick = {
                    onSave(
                        initial.copy(
                            name = name.trim(),
                            url = url.trim().trimEnd('/'),
                            bearerToken = bearer.trim(),
                            headerName = headerName.trim(),
                            headerValue = headerValue.trim(),
                        ),
                    )
                },
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            GlassButton("Cancel", onClick = onCancel, modifier = Modifier.align(Alignment.CenterVertically))
        }
    }
}

@Composable
private fun FormField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String = "",
    keyboard: KeyboardType = KeyboardType.Text,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = Ink.I500)
        Spacer(Modifier.height(6.dp))
        Column(
            Modifier
                .fillMaxWidth()
                .background(Ink.SurfaceHigh, RoundedCornerShape(Radii.control))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = Ink.I100, fontSize = 13.sp),
                cursorBrush = SolidColor(Ink.White),
                keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                decorationBox = { inner ->
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, fontSize = 13.sp, color = Ink.I500)
                    }
                    inner()
                },
            )
        }
    }
}
