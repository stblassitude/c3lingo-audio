package org.c3lingo.audio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/**
 * The frontend's help text, from the bridge's optional `help` message: what c3lingo is,
 * how to use the soundcheck, what the channels are, and whom to ask.
 */
data class Help(val title: String, val blocks: List<Block>) {
    /** Text shown one after another in the same paragraph. */
    data class Run(
        val text: String,
        /** A label as the page shows it, e.g. **Soundcheck**. */
        val strong: Boolean,
        /** Only `https` and `mailto` links, which open in the browser or mail app. */
        val link: String?,
    )

    sealed interface Block {
        data class Heading(val text: String) : Block
        data class Paragraph(val runs: List<Run>) : Block
        /** A bulleted list; each item is one paragraph. */
        data class Bullets(val items: List<List<Run>>) : Block
    }

    companion object {
        /** Parses the content of a `help` message, leaving out block types and run fields it doesn't know. */
        fun fromJson(json: JSONObject?): Help? {
            val title = json?.string("title") ?: return null
            val blocks = json.optJSONArray("blocks")?.objects()?.mapNotNull { block ->
                when (block.string("type")) {
                    "heading" -> block.string("text")?.let(Block::Heading)
                    "paragraph" -> runs(block.optJSONArray("text")).takeIf { it.isNotEmpty() }?.let(Block::Paragraph)
                    "list" -> {
                        val items = (0 until (block.optJSONArray("items")?.length() ?: 0))
                            .map { runs(block.optJSONArray("items")?.optJSONArray(it)) }
                            .filter { it.isNotEmpty() }
                        items.takeIf { it.isNotEmpty() }?.let(Block::Bullets)
                    }
                    else -> null
                }
            }.orEmpty()
            return if (blocks.isEmpty()) null else Help(title, blocks)
        }

        private fun runs(json: JSONArray?): List<Run> = json?.objects()?.mapNotNull { run ->
            val text = run.string("text") ?: return@mapNotNull null
            val link = run.string("href")?.takeIf { it.substringBefore(':').lowercase() in setOf("https", "mailto") }
            Run(text, run.opt("strong") as? Boolean ?: false, link)
        }.orEmpty()

        private fun JSONArray.objects(): List<JSONObject> = (0 until length()).mapNotNull(::optJSONObject)
    }
}

/** Shows the help, from the page's help button or by itself on the first launch. */
@Composable
fun HelpDialog(help: Help, onDismiss: () -> Unit) {
    val linkColor = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(help.title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                help.blocks.forEach { block ->
                    when (block) {
                        is Help.Block.Heading -> Text(
                            block.text,
                            style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(top = 8.dp).semantics { heading() },
                        )
                        is Help.Block.Paragraph -> Text(block.runs.text(linkColor))
                        is Help.Block.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            block.items.forEach { item ->
                                Row {
                                    Text("•", Modifier.padding(end = 8.dp).clearAndSetSemantics {})
                                    Text(item.text(linkColor))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.done)) }
        },
    )
}

private fun List<Help.Run>.text(linkColor: Color): AnnotatedString = buildAnnotatedString {
    val linkStyles = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    forEach { run ->
        val start = length
        append(run.text)
        if (run.strong) addStyle(SpanStyle(fontWeight = FontWeight.Bold), start, length)
        run.link?.let { addLink(LinkAnnotation.Url(it, linkStyles), start, length) }
    }
}
