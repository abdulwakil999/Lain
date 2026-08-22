package com.lain.assistant.ui.common

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lain.assistant.data.ChatMessage
import com.lain.assistant.data.Sender
import com.lain.assistant.ui.theme.LainCream
import com.lain.assistant.ui.theme.LainInk
import com.lain.assistant.ui.theme.LainMuted
import com.lain.assistant.ui.theme.LainNavy
import com.lain.assistant.ui.theme.LainSalmon
import com.lain.assistant.ui.theme.LainSalmonDeep

/**
 * One message, with its own actions.
 *
 * Shared by the full chat screen and the mini surface. The two had separate copies
 * of this bubble, which is how they drifted: adding the action menu to one would
 * have left the other a read-only transcript for no reason a user could see.
 *
 * Long-press opens the menu, which is the gesture people already expect from every
 * other chat app. Long-press alone is not enough here, though — it is awkward with
 * a screen reader and impossible for someone driving the phone through a switch,
 * and those are the users Lain is most for. So the same actions are also published
 * as accessibility actions, which TalkBack lists in its own menu with no gesture
 * required.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    maxWidth: Dp,
    /** True while a turn is in flight; resending is held back until it finishes. */
    busy: Boolean,
    onCopyToInput: () -> Unit,
    onResend: () -> Unit,
    onDelete: () -> Unit,
    /** The mini surface is smaller and sits over another app, so it reads quieter. */
    compact: Boolean = false
) {
    val isUser = message.sender == Sender.USER
    val clipboard = LocalClipboardManager.current
    val haptics = LocalHapticFeedback.current
    var menuOpen by remember { mutableStateOf(false) }

    // Two taps for delete, and only for delete. There is no undo, and a mis-tap in
    // a menu is exactly how a message someone wanted to keep disappears.
    var confirmingDelete by remember { mutableStateOf(false) }

    fun closeMenu() {
        menuOpen = false
        confirmingDelete = false
    }

    fun copyToClipboard() {
        clipboard.setText(AnnotatedString(message.text))
        closeMenu()
    }

    val alpha = if (compact) 0.85f else 0.92f
    val textStyle =
        if (compact) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodyLarge

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Box {
            Box(
                modifier = Modifier
                    // Derived from the window rather than a fixed 300dp, which was a
                    // third of a tablet and most of a small phone.
                    .widthIn(max = maxWidth)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        if (isUser) LainCream.copy(alpha = alpha) else LainSalmonDeep.copy(alpha = alpha)
                    )
                    .combinedClickable(
                        onClick = { },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            confirmingDelete = false
                            menuOpen = true
                        }
                    )
                    .padding(
                        horizontal = if (compact) 12.dp else 14.dp,
                        vertical = if (compact) 8.dp else 10.dp
                    )
                    .semantics {
                        contentDescription =
                            "${if (isUser) "You" else "Lain"}: ${message.text}"
                        customActions = buildList {
                            add(CustomAccessibilityAction("Copy message") { copyToClipboard(); true })
                            add(CustomAccessibilityAction("Put in the message box") { onCopyToInput(); true })
                            if (!busy) add(CustomAccessibilityAction("Send again") { onResend(); true })
                            add(CustomAccessibilityAction("Delete message") { onDelete(); true })
                        }
                    }
            ) {
                // Selectable, so part of a long reply can be picked out with the normal
                // Android handles rather than only copied whole.
                SelectionContainer {
                    Text(
                        text = message.text,
                        color = if (isUser) LainInk else LainCream,
                        style = textStyle
                    )
                }
            }

            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { closeMenu() },
                modifier = Modifier.background(LainNavy)
            ) {
                MessageAction("Copy") { copyToClipboard() }

                MessageAction(if (isUser) "Edit and resend" else "Put in the box") {
                    onCopyToInput()
                    closeMenu()
                }

                MessageAction(
                    text = if (isUser) "Send again" else "Ask again",
                    // Queuing a second turn while one is running would interleave two
                    // tasks. The item stays visible but disabled rather than vanishing,
                    // so it doesn't look like the menu changed shape at random.
                    enabled = !busy
                ) {
                    onResend()
                    closeMenu()
                }

                MessageAction(
                    text = if (confirmingDelete) "Tap again to delete" else "Delete",
                    destructive = true
                ) {
                    if (confirmingDelete) {
                        onDelete()
                        closeMenu()
                    } else {
                        confirmingDelete = true
                    }
                }
            }
        }
    }
}

@Composable
private fun MessageAction(
    text: String,
    enabled: Boolean = true,
    destructive: Boolean = false,
    onClick: () -> Unit
) {
    DropdownMenuItem(
        text = {
            Text(
                text,
                style = MaterialTheme.typography.bodyLarge,
                color = when {
                    !enabled -> LainMuted
                    destructive -> LainSalmon
                    else -> LainCream
                }
            )
        },
        enabled = enabled,
        onClick = onClick
    )
}
