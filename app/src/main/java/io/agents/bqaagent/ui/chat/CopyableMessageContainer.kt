// Copyright 2026 BQAAgent (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.bqaagent.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.bqaagent.R

/**
 * Shared "copy / select text" affordance, extracted from the chat bubbles so the batch task-detail
 * screen can offer exactly the same two ways of getting text out (long-press menu with Copy and
 * Select, plus a selectable dialog with Copy-all). `internal` top-level members are visible to
 * every file in this module, so ChatScreen and BatchScreens share one implementation.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun CopyableMessageContainer(
    text: String,
    colors: PokeclawColors,
    modifier: Modifier = Modifier,
    content: @Composable (Modifier) -> Unit,
) {
    val context = LocalContext.current
    var menuExpanded by remember { mutableStateOf(false) }
    var showSelectableText by remember { mutableStateOf(false) }
    val copyable = text.isNotBlank() && text != "..."

    Box(modifier = modifier) {
        content(
            if (copyable) {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = { menuExpanded = true },
                )
            } else {
                Modifier
            }
        )

        DropdownMenu(
            expanded = menuExpanded,
            onDismissRequest = { menuExpanded = false },
            containerColor = colors.surface,
        ) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_action_copy), color = colors.textPrimary) },
                leadingIcon = {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, tint = colors.textSecondary)
                },
                onClick = {
                    menuExpanded = false
                    copyTextToClipboard(context, text)
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.chat_select_text), color = colors.textPrimary) },
                leadingIcon = {
                    Icon(Icons.Default.Edit, contentDescription = null, tint = colors.textSecondary)
                },
                onClick = {
                    menuExpanded = false
                    showSelectableText = true
                },
            )
        }
    }

    if (showSelectableText) {
        SelectableMessageDialog(
            text = text,
            colors = colors,
            onCopyAll = { copyTextToClipboard(context, text) },
            onDismiss = { showSelectableText = false },
        )
    }
}

@Composable
internal fun SelectableMessageDialog(
    text: String,
    colors: PokeclawColors,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = colors.surface,
        title = {
            Text(stringResource(R.string.chat_select_text), color = colors.textPrimary)
        },
        text = {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
                    .background(colors.background, RoundedCornerShape(0.dp))
                    .padding(12.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = text,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        lineHeight = 21.sp,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onCopyAll()
                    onDismiss()
                },
            ) {
                Text(stringResource(R.string.chat_copy_all), color = colors.textSecondary)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_done), color = colors.accent)
            }
        },
    )
}

internal fun copyTextToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("BQAAgent message", text))
    Toast.makeText(context, context.getString(R.string.chat_copied), Toast.LENGTH_SHORT).show()
}