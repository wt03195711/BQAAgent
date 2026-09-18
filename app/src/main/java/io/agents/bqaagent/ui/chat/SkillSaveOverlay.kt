
package io.agents.bqaagent.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.agents.bqaagent.agent.skill.RecordedSkillStep
import io.agents.bqaagent.agent.skill.SkillDefinition
import io.agents.bqaagent.agent.skill.SkillReplayer
import io.agents.bqaagent.agent.skill.SkillSaveData
import io.agents.bqaagent.agent.skill.ExecutionTemplate

sealed class SkillSaveOverlayState {
    object Hidden : SkillSaveOverlayState()
    data class OfferSave(val saveData: SkillSaveData) : SkillSaveOverlayState()
    /** Full match found — ask the user to review the template before replay starts. */
    data class OfferReplay(
        val skill: SkillDefinition,
        val template: ExecutionTemplate,
        val extractedParams: Map<String, String>
    ) : SkillSaveOverlayState()
    data class ShowDetail(
        val saveData: SkillSaveData,
        val editableTitle: String
    ) : SkillSaveOverlayState()
    data class Saving(val isTemplateOnly: Boolean = false) : SkillSaveOverlayState()
    data class Success(val title: String, val isTemplateOnly: Boolean = false) : SkillSaveOverlayState()
    data class Fail(val error: String, val saveData: SkillSaveData) : SkillSaveOverlayState()
    /** NEW-skill save blocked because the saved-skill cap is reached; offers Cancel + jump to Skill Management. */
    data class SkillLimitReached(
        val currentCount: Int,
        val maxCount: Int,
        val saveData: SkillSaveData
    ) : SkillSaveOverlayState()
}

private data class StepDisplayRow(
    val step: RecordedSkillStep,
    val isSameIntentAsPrevious: Boolean
)

private val EXCLUDED_STEP_PARAM_KEYS = setOf("wait_after")

/** Recorded values replaced by this run's values are drawn in red inside step rows. */
private val SUBSTITUTION_HIGHLIGHT_COLOR = Color(0xFFE53935)

@Composable
fun SkillSaveOverlay(
    state: SkillSaveOverlayState,
    colors: PokeclawColors,
    onOpenDetail: () -> Unit,
    onDismiss: () -> Unit,
    onTitleChanged: (String) -> Unit,
    onConfirmSave: () -> Unit,
    onRetry: () -> Unit,
    onOpenSkillManagement: () -> Unit,
) {
    // Idle: no dialog should be shown, so don't compose the full-screen scrim at all.
    // Otherwise the scrim's tap-consuming clickable would block the whole UI.
    if (state is SkillSaveOverlayState.Hidden) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background.copy(alpha = 0.75f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { /* consume taps so they never pass through to the content behind the scrim */ },
    ) {
        if (state is SkillSaveOverlayState.ShowDetail) {
            ShowDetailCard(
                state = state,
                colors = colors,
                onTitleChanged = onTitleChanged,
                onConfirm = onConfirmSave,
                onCancel = onDismiss
            )
        } else if (state is SkillSaveOverlayState.OfferReplay) {
            ReplayConfirmCard(
                state = state,
                colors = colors,
                onConfirm = onConfirmSave,
                onCancel = onDismiss
            )
        } else {
            Column(
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxWidth(0.88f)
                    .background(colors.surface, RoundedCornerShape(16.dp))
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                when (state) {
                    is SkillSaveOverlayState.OfferSave -> OfferSaveContent(
                        saveData = state.saveData,
                        colors = colors,
                        onSave = onOpenDetail,
                        onDismiss = onDismiss
                    )
                    is SkillSaveOverlayState.Saving -> SavingContent(state.isTemplateOnly, colors)
                    is SkillSaveOverlayState.Success -> SuccessContent(state.title, state.isTemplateOnly, colors)
                    is SkillSaveOverlayState.Fail -> FailContent(
                        error = state.error,
                        colors = colors,
                        onRetry = onRetry,
                        onCancel = onDismiss
                    )
                    is SkillSaveOverlayState.SkillLimitReached -> SkillLimitReachedContent(
                        currentCount = state.currentCount,
                        maxCount = state.maxCount,
                        colors = colors,
                        onOpenSkillManagement = onOpenSkillManagement,
                        onCancel = onDismiss
                    )
                    is SkillSaveOverlayState.Hidden -> Unit
                    is SkillSaveOverlayState.ShowDetail -> Unit
                    is SkillSaveOverlayState.OfferReplay -> Unit
                }
            }
        }
    }
}

@Composable
private fun OfferSaveContent(
    saveData: SkillSaveData,
    colors: PokeclawColors,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
) {
    val matchedSkill = saveData.matchedSkill
    if (matchedSkill != null) {
        val isTemplateUpdate = saveData.degradedTemplateId != null
        Text(
            text = if (isTemplateUpdate) "Update template?" else "Learn new template?",
            color = colors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isTemplateUpdate) {
                "This task re-ran skill \"${matchedSkill.title}\" with the AI agent. " +
                        "Review the recorded steps and save them to update its execution template."
            } else {
                "This task matched the existing skill \"${matchedSkill.title}\". " +
                        "Review the recorded steps and save them as a new execution template for it."
            },
            color = colors.textSecondary,
            fontSize = 14.sp
        )
    } else {
        Text(
            text = "Save as Skill?",
            color = colors.textPrimary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "This task was completed by the AI agent. Save it as a reusable Skill so it can run faster next time.",
            color = colors.textSecondary,
            fontSize = 14.sp
        )
    }
    Spacer(Modifier.height(8.dp))
    Text(
        text = "Task: ${saveData.originalTaskText}",
        color = colors.textTertiary,
        fontSize = 13.sp
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        OutlinedButton(onClick = onDismiss) {
            Text("Not now")
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onSave,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
        ) {
            Text(if (matchedSkill != null) "Review Template" else "Save as Skill")
        }
    }
}

@Composable
private fun ShowDetailCard(
    state: SkillSaveOverlayState.ShowDetail,
    colors: PokeclawColors,
    onTitleChanged: (String) -> Unit,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val matchedSkill = state.saveData.matchedSkill
    val isTemplateMode = matchedSkill != null
    val steps = state.saveData.recordingSession.steps
    val rows = remember(state.saveData) {
        steps.mapIndexed { index, step ->
            val intent = step.intent.orEmpty().trim()
            val prevIntent = if (index > 0) steps[index - 1].intent.orEmpty().trim() else ""
            StepDisplayRow(step, intent.isNotBlank() && intent == prevIntent)
        }
    }

    val configuration = LocalConfiguration.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = configuration.screenHeightDp.dp * 0.85f)
                .background(colors.surface, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ScenarioChip(isTemplateMode, colors)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = if (isTemplateMode) "Confirm Template" else "Confirm Skill",
                    color = colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))

            if (matchedSkill != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text = "🔒", fontSize = 14.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = matchedSkill.title,
                        color = colors.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            } else {
                OutlinedTextField(
                    value = state.editableTitle,
                    onValueChange = { newTitle ->
                        if (newTitle.length <= SkillDefinition.SKILL_TITLE_MAX_LENGTH) {
                            onTitleChanged(newTitle)
                        }
                    },
                    label = { Text("Skill title (editable)") },
                    singleLine = true,
                    supportingText = {
                        Text("${state.editableTitle.length}/${SkillDefinition.SKILL_TITLE_MAX_LENGTH}")
                    },
                    isError = state.editableTitle.isBlank(),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = colors.textPrimary,
                        unfocusedTextColor = colors.textPrimary,
                        focusedBorderColor = colors.accent,
                        unfocusedBorderColor = colors.inputBorder,
                        focusedLabelColor = colors.accent,
                        unfocusedLabelColor = colors.textTertiary,
                        cursorColor = colors.accent
                    )
                )
            }
            Spacer(Modifier.height(12.dp))

            Text(
                text = "Steps (${steps.size}) · review before save",
                color = colors.textTertiary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(rows) { index, row ->
                    StepTimelineItem(row = row, isLast = index == rows.lastIndex, colors = colors)
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text("Cancel")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onConfirm,
                    enabled = isTemplateMode || state.editableTitle.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text(if (isTemplateMode) "Confirm & Save Template" else "Confirm & Save Skill")
                }
            }
        }
    }
}

/**
 * Inline (non-modal) skill-save prompt rendered as a footer card inside the chat
 * message list. ONLY the initial "Save as Skill?" prompt (OfferSave) renders here so
 * the user can read the task result and decide whether to save without a full-screen
 * overlay. Every later phase (ShowDetail/Saving/Success/Fail) and OfferReplay stay
 * full-screen modals handled by [SkillSaveOverlay].
 */
@Composable
fun InlineSkillSaveCard(
    state: SkillSaveOverlayState,
    colors: PokeclawColors,
    onOpenDetail: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (state !is SkillSaveOverlayState.OfferSave) return

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .background(colors.surface, RoundedCornerShape(16.dp))
            .border(0.5.dp, colors.inputBorder, RoundedCornerShape(16.dp))
            .padding(16.dp)
    ) {
        OfferSaveContent(
            saveData = state.saveData,
            colors = colors,
            onSave = onOpenDetail,
            onDismiss = onDismiss
        )
    }
}

@Composable
private fun StepTimelineItem(
    row: StepDisplayRow,
    isLast: Boolean,
    colors: PokeclawColors,
    substitutions: List<Pair<String, String>> = emptyList()
) {
    val step = row.step
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .padding(vertical = 4.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .padding(end = 10.dp)
                .fillMaxHeight()
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .background(colors.accent, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "${step.stepIndex + 1}",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(1.5.dp)
                        .weight(1f)
                        .background(colors.inputBorder)
                )
            }
        }
        Column(modifier = Modifier.padding(bottom = 8.dp)) {
            val intent = step.intent.orEmpty().trim()
            val intentLine = when {
                row.isSameIntentAsPrevious -> "(same as above)"
                intent.isNotBlank() -> intent
                else -> {
                    val target = step.targetText.trim()
                    if (target.isNotBlank()) {
                        "${step.displayName.ifBlank { step.toolName }}: $target"
                    } else ""
                }
            }
            if (intentLine.isNotBlank()) {
                Text(
                    text = highlightSubstitutions(
                        source = intentLine,
                        substitutions = substitutions,
                        baseColor = if (row.isSameIntentAsPrevious) colors.textTertiary else colors.textPrimary
                    ),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(3.dp))
            }
            Box(
                modifier = Modifier
                    .border(0.5.dp, colors.inputBorder, RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${step.displayName.ifBlank { step.toolName }} (${step.toolName})",
                    color = colors.textSecondary,
                    fontSize = 11.sp
                )
            }
            val paramsText = formatStepParams(step)
            if (paramsText.isNotBlank()) {
                Spacer(Modifier.height(3.dp))
                Text(
                    text = highlightSubstitutions(
                        source = paramsText,
                        substitutions = substitutions,
                        baseColor = colors.textTertiary
                    ),
                    fontSize = 12.sp
                )
            }
        }
    }
}

/**
* Render text with every occurrence of a recorded value replaced by the value
* substituted for this run; replaced fragments are drawn in red. Longer old
* values match first so overlapping values (e.g. "100" vs "1000") don't clash.
*/
private fun highlightSubstitutions(
    source: String,
    substitutions: List<Pair<String, String>>,
    baseColor: Color
): AnnotatedString {
    val active = substitutions.filter { it.first.isNotEmpty() }
    if (source.isEmpty() || active.isEmpty()) {
        return buildAnnotatedString {
            withStyle(SpanStyle(color = baseColor)) { append(source) }
        }
    }
    data class MatchRange(val start: Int, val end: Int, val replacement: String)
    val matches = mutableListOf<MatchRange>()
    for ((old, new) in active.sortedByDescending { it.first.length }) {
        var idx = source.indexOf(old)
        while (idx >= 0) {
            val overlaps = matches.any { idx < it.end && idx + old.length > it.start }
            if (!overlaps) matches.add(MatchRange(idx, idx + old.length, new))
            idx = source.indexOf(old, idx + old.length)
        }
    }
    if (matches.isEmpty()) {
        return buildAnnotatedString {
            withStyle(SpanStyle(color = baseColor)) { append(source) }
        }
    }
    matches.sortBy { it.start }
    return buildAnnotatedString {
        var cursor = 0
        for (m in matches) {
            if (m.start > cursor) {
                withStyle(SpanStyle(color = baseColor)) { append(source.substring(cursor, m.start)) }
            }
            withStyle(SpanStyle(color = SUBSTITUTION_HIGHLIGHT_COLOR)) { append(m.replacement) }
            cursor = m.end
        }
        if (cursor < source.length) {
            withStyle(SpanStyle(color = baseColor)) { append(source.substring(cursor)) }
        }
    }
}

private fun formatStepParams(step: RecordedSkillStep): String {
    return step.params.entries
        .filter { it.key !in EXCLUDED_STEP_PARAM_KEYS }
        .joinToString(" · ") { (key, value) ->
            val cleaned = value.toString().replace("\n", " ").trim()
            val display = if (cleaned.length > 60) cleaned.take(60) + "…" else cleaned
            "$key = \"$display\""
        }
}

@Composable
private fun ScenarioChip(isTemplateMode: Boolean, colors: PokeclawColors) {
    Box(
        modifier = Modifier
            .background(
                if (isTemplateMode) colors.divider else colors.accent,
                RoundedCornerShape(4.dp)
            )
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = if (isTemplateMode) "New Template" else "New Skill",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun SavingContent(isTemplateOnly: Boolean, colors: PokeclawColors) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(32.dp),
            color = colors.accent
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = if (isTemplateOnly) "Saving Template..." else "Saving Skill...",
            color = colors.textSecondary,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun SuccessContent(title: String, isTemplateOnly: Boolean, colors: PokeclawColors) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = if (isTemplateOnly) "✅ Template saved" else "✅ Skill saved",
            color = colors.textPrimary,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = if (isTemplateOnly) "New template added to \"$title\"" else "Saved as: $title",
            color = colors.textSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun FailContent(
    error: String,
    colors: PokeclawColors,
    onRetry: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = "❌ Save failed",
        color = colors.textPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = error,
        color = colors.textSecondary,
        fontSize = 14.sp
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
        ) {
            Text("Retry")
        }
    }
}

@Composable
private fun SkillLimitReachedContent(
    currentCount: Int,
    maxCount: Int,
    colors: PokeclawColors,
    onOpenSkillManagement: () -> Unit,
    onCancel: () -> Unit,
) {
    Text(
        text = "🗂️ Skill limit reached",
        color = colors.textPrimary,
        fontSize = 16.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "You've saved $currentCount of $maxCount Skills — the limit is reached, " +
                "so this task can't be saved as a new Skill yet.\n\n" +
                "Delete a Skill you no longer need in Skill Management, then come back and tap Save again. ",
        color = colors.textSecondary,
        fontSize = 14.sp
    )
    Spacer(Modifier.height(16.dp))
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End
    ) {
        OutlinedButton(onClick = onCancel) {
            Text("Cancel")
        }
        Spacer(Modifier.width(12.dp))
        Button(
            onClick = onOpenSkillManagement,
            colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
        ) {
            Text("Open Skill Management")
        }
    }
}

@Composable
private fun ReplayConfirmCard(
    state: SkillSaveOverlayState.OfferReplay,
    colors: PokeclawColors,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val template = state.template
    val rows = remember(state.template) {
        template.steps.mapIndexed { index, step ->
            val intent = step.intent.orEmpty().trim()
            val prevIntent = if (index > 0) template.steps[index - 1].intent.orEmpty().trim() else ""
            StepDisplayRow(step, intent.isNotBlank() && intent == prevIntent)
        }
    }
    // Anchored exactly like SkillReplayer does: only steps whose recorded values
    // replay will actually rewrite carry a substitution, other steps stay verbatim.
    val substitutionsByStep = remember(state.template, state.extractedParams) {
        SkillReplayer.buildDisplaySubstitutionPlan(template, state.extractedParams)
    }
    val configuration = LocalConfiguration.current
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .heightIn(max = configuration.screenHeightDp.dp * 0.85f)
                .background(colors.surface, RoundedCornerShape(16.dp))
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ReplayChip(colors)
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Confirm Replay",
                    color = colors.textPrimary,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = "⚡", fontSize = 14.sp)
                Spacer(Modifier.width(6.dp))
                Text(
                    text = state.skill.title,
                    color = colors.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))

            Text(
                text = if (substitutionsByStep.isEmpty()) {
                    "Steps (${template.steps.size}) · recorded template"
                } else {
                    "Steps (${template.steps.size}) · values in red are replaced for this run"
                },
                color = colors.textTertiary,
                fontSize = 12.sp
            )
            Spacer(Modifier.height(6.dp))
            LazyColumn(modifier = Modifier.weight(1f, fill = false)) {
                itemsIndexed(rows) { index, row ->
                    StepTimelineItem(
                        row = row,
                        isLast = index == rows.lastIndex,
                        colors = colors,
                        substitutions = substitutionsByStep[row.step.stepIndex] ?: emptyList()
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                OutlinedButton(onClick = onCancel) {
                    Text("Use AI Instead")
                }
                Spacer(Modifier.width(12.dp))
                Button(
                    onClick = onConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = colors.accent)
                ) {
                    Text("Confirm & Replay")
                }
            }
        }
    }
}

@Composable
private fun ReplayChip(colors: PokeclawColors) {
    Box(
        modifier = Modifier
            .background(colors.accent, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
    ) {
        Text(
            text = "Replay",
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}