package to.eyed.spettro.chat.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import to.eyed.spettro.chat.data.tools.ScheduledTask
import to.eyed.spettro.chat.data.tools.ScheduledTasks
import to.eyed.spettro.chat.ui.components.GlassButton
import to.eyed.spettro.chat.ui.components.Hairline
import to.eyed.spettro.chat.ui.theme.Ink
import to.eyed.spettro.chat.ui.theme.Radii

/**
 * Settings → Scheduled tasks: every scheduled agent run the model (or a
 * previous conversation) set up, with when it fires next and a way out.
 * Creation stays conversational — you ask the assistant to schedule things.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduledTasksSheet(
    tasks: List<ScheduledTask>,
    onCancel: (String) -> Unit,
    onDismiss: () -> Unit,
) {
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
                Text("Scheduled tasks", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = Ink.White)
            }
            Hairline()

            if (tasks.isEmpty()) {
                Text(
                    "Nothing scheduled. Ask Spettro to run something later — " +
                        "\"every morning at 8, check the weather and brief me\" — and it appears here.",
                    fontSize = 13.sp,
                    lineHeight = 18.sp,
                    color = Ink.I500,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 24.dp),
                )
            } else {
                LazyColumn(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    items(tasks.size, key = { tasks[it].id }) { i ->
                        val task = tasks[i]
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    task.title,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = Ink.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "Next run ${ScheduledTasks.display(task.nextRunAtMillis)}" +
                                        if (task.repeat == "none") " · one-time" else " · repeats ${task.repeat}",
                                    fontSize = 11.sp,
                                    color = Ink.I500,
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    task.prompt,
                                    fontSize = 11.sp,
                                    lineHeight = 15.sp,
                                    color = Ink.I500,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            Spacer(Modifier.width(12.dp))
                            GlassButton("Cancel", onClick = { onCancel(task.id) }, textColor = Ink.Danger)
                        }
                        if (i < tasks.size - 1) Hairline()
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
