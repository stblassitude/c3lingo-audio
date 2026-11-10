package org.c3lingo.audio

import android.os.Build
import android.os.LocaleList
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.outlined.ClosedCaption
import androidx.compose.material.icons.outlined.ClosedCaptionDisabled
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

/**
 * The subtitles, over the whole page, so that as much text as possible fits; the player
 * bar stays below. Before the subtitles are turned on for the first time, it explains
 * them instead, and while a speech model downloads, it shows the progress. Where the
 * system or the device can't make subtitles, it says so.
 */
@Composable
fun SubtitlesView(subtitles: Subtitles, modifier: Modifier = Modifier) {
    val state by subtitles.state.collectAsStateWithLifecycle()
    val language by subtitles.language.collectAsStateWithLifecycle()
    val name = language?.name.orEmpty()
    // A surface, for the text colour as well as the background.
    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
        when (val current = state) {
            Subtitles.State.Intro -> Intro(subtitles)
            Subtitles.State.Listening -> Transcript(subtitles)
            Subtitles.State.Preparing -> Status(Icons.Outlined.ClosedCaption, stringResource(R.string.subtitles_preparing), progress = Progress.Indeterminate)
            is Subtitles.State.Downloading -> Status(
                Icons.Outlined.Download,
                stringResource(R.string.subtitles_downloading, name),
                stringResource(R.string.subtitles_downloading_message),
                current.percent?.let { Progress.Determinate(it / 100f) } ?: Progress.Indeterminate,
            )
            Subtitles.State.UnknownLanguage -> Status(
                Icons.Outlined.ClosedCaptionDisabled,
                stringResource(R.string.subtitles_unknown_language),
                stringResource(R.string.subtitles_unknown_language_message),
            )
            Subtitles.State.Unsupported -> Status(
                Icons.Outlined.ClosedCaptionDisabled,
                stringResource(R.string.subtitles_unsupported, name),
                stringResource(R.string.subtitles_unsupported_message, name),
            )
            Subtitles.State.DeviceUnsupported -> Status(
                Icons.Outlined.ClosedCaptionDisabled,
                stringResource(R.string.subtitles_device_unsupported),
                stringResource(
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) R.string.subtitles_android_too_old
                    else R.string.subtitles_device_unsupported_message),
            )
            is Subtitles.State.Failed -> Status(Icons.Outlined.ErrorOutline, stringResource(R.string.subtitles_failed), current.reason)
        }
    }
}

/** What the subtitles are, shown once before they are turned on for the first time. */
@Composable
private fun Intro(subtitles: Subtitles) {
    val linkStyles = TextLinkStyles(SpanStyle(color = MaterialTheme.colorScheme.primary, textDecoration = TextDecoration.Underline))
    val email = "hello@c3lingo.org"
    val text = stringResource(R.string.subtitles_intro, PLACEHOLDER)
    val intro = buildAnnotatedString {
        append(text.substringBefore(PLACEHOLDER))
        if (PLACEHOLDER in text) {
            val start = length
            append(email)
            addLink(LinkAnnotation.Url("mailto:$email", linkStyles), start, length)
            append(text.substringAfter(PLACEHOLDER))
        }
    }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        Icon(
            Icons.Outlined.ClosedCaption,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp).clearAndSetSemantics {},
        )
        Text(
            stringResource(R.string.subtitles_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        Text(intro, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        Spacer(Modifier.height(8.dp))
        Button(onClick = subtitles::turnOn) { Text(stringResource(R.string.subtitles_turn_on)) }
        TextButton(onClick = subtitles::hide) { Text(stringResource(R.string.subtitles_not_now)) }
    }
}

private const val PLACEHOLDER = "\u0000"

private sealed interface Progress {
    data object Indeterminate : Progress
    data class Determinate(val fraction: Float) : Progress
}

/** Why there are no subtitles yet, or none at all. */
@Composable
private fun Status(icon: ImageVector, title: String, message: String? = null, progress: Progress? = null) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(44.dp).clearAndSetSemantics {},
        )
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        message?.let {
            Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 560.dp))
        }
        when (progress) {
            is Progress.Determinate -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
                LinearProgressIndicator(progress = { progress.fraction }, modifier = Modifier.widthIn(max = 320.dp).fillMaxWidth().padding(top = 8.dp))
                Text(
                    "${(progress.fraction * 100).toInt()} %",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Progress.Indeterminate -> CircularProgressIndicator(Modifier.padding(top = 8.dp).size(24.dp), strokeWidth = 2.dp)
            null -> Unit
        }
    }
}

/** The text as it comes in, kept at the latest unless scrolled back. */
@Composable
private fun Transcript(subtitles: Subtitles) {
    val paragraphs by subtitles.paragraphs.collectAsStateWithLifecycle()
    val pendingText by subtitles.pendingText.collectAsStateWithLifecycle()
    val language by subtitles.language.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // Follows the text until scrolled back, and again once scrolled to the end.
    var isFollowing by rememberSaveable { mutableStateOf(true) }
    val locale = language?.tag?.let { runCatching { LocaleList.forLanguageTags(it) }.getOrNull() }
    val pendingColor = MaterialTheme.colorScheme.onSurfaceVariant
    // The header, the paragraphs, and the pending text or a hint below them.
    val itemCount = 1 + paragraphs.size + 1

    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) -> if (scrolling) isFollowing = !canScrollForward }
    }
    LaunchedEffect(paragraphs, pendingText, isFollowing) {
        if (isFollowing) listState.scrollToItem(itemCount - 1, Int.MAX_VALUE / 2)
    }

    Box(Modifier.fillMaxSize()) {
        SelectionContainer {
            LazyColumn(
                state = listState,
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                item(key = "header") {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Icon(
                            Icons.Outlined.ClosedCaption,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp).clearAndSetSemantics {},
                        )
                        Text(
                            language?.let { stringResource(R.string.subtitles_header, listOfNotNull(it.flag, it.name).joinToString(" ")) }
                                ?: stringResource(R.string.subtitles_header_no_language),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(paragraphs, key = { it.id }) { paragraph ->
                    when (paragraph.kind) {
                        Subtitles.Paragraph.Kind.TEXT -> {
                            val isLast = paragraph.id == paragraphs.lastOrNull()?.id
                            Text(
                                buildAnnotatedString {
                                    append(paragraph.text)
                                    if (isLast && pendingText.isNotEmpty()) {
                                        val start = length
                                        append(" $pendingText")
                                        addStyle(SpanStyle(color = pendingColor), start, length)
                                    }
                                    if (locale != null) addStyle(SpanStyle(localeList = androidx.compose.ui.text.intl.LocaleList(locale.toLanguageTags())), 0, length)
                                },
                                style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            )
                        }
                        Subtitles.Paragraph.Kind.GAP -> Marker(Icons.Outlined.MoreHoriz, stringResource(R.string.subtitles_gap))
                        Subtitles.Paragraph.Kind.LANGUAGE ->
                            Marker(Icons.Outlined.Language, stringResource(R.string.subtitles_language_changed, paragraph.text))
                    }
                }
                item(key = "end") {
                    when {
                        pendingText.isNotEmpty() && paragraphs.lastOrNull()?.kind != Subtitles.Paragraph.Kind.TEXT -> Text(
                            pendingText,
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = pendingColor,
                        )
                        paragraphs.isEmpty() && pendingText.isEmpty() -> Text(
                            stringResource(R.string.subtitles_waiting),
                            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Normal),
                            color = pendingColor,
                        )
                        // Something to scroll to at the end.
                        else -> Spacer(Modifier.height(1.dp))
                    }
                }
            }
        }
        AnimatedVisibility(!isFollowing, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp)) {
            ExtendedFloatingActionButton(
                onClick = {
                    scope.launch {
                        listState.animateScrollToItem(itemCount - 1, Int.MAX_VALUE / 2)
                        isFollowing = true
                    }
                },
                icon = { Icon(Icons.Filled.ArrowDownward, contentDescription = null) },
                text = { Text(stringResource(R.string.subtitles_latest)) },
            )
        }
    }
}

/** Marks where text is missing or the language changed. */
@Composable
private fun Marker(icon: ImageVector, text: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
    ) {
        HorizontalDivider(Modifier.weight(1f))
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(4f, fill = false),
        )
        HorizontalDivider(Modifier.weight(1f))
    }
}
