package studio.npu.x200t.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import studio.npu.x200t.BuildConfig

/** The Help tab: a list of menus; each opens its sub-topics, which expand when tapped. */
@Composable
fun HelpScreen(modifier: Modifier) {
    var open by rememberSaveable { mutableStateOf(-1) }
    BackHandler(enabled = open >= 0) { open = -1 }
    if (open < 0) HelpHome(modifier, onOpen = { open = it })
    else HelpSectionPage(HELP[open], modifier, onBack = { open = -1 })
}

@Composable
private fun HelpHome(modifier: Modifier, onOpen: (Int) -> Unit) {
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Column(Modifier.padding(top = 16.dp, bottom = 4.dp)) {
                Text("Help", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "How the app works, what each setting does, and what to expect on your phone. Tap a topic.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        itemsIndexed(HELP) { i, section ->
            Card(Modifier.fillMaxWidth().clickable { onOpen(i) }) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(section.title, style = MaterialTheme.typography.titleMedium)
                        Text(section.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text(
                "NPU Image Studio ${BuildConfig.VERSION_NAME}",
                Modifier.padding(vertical = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Suppress("DEPRECATION")
@Composable
private fun HelpSectionPage(section: HelpSection, modifier: Modifier, onBack: () -> Unit) {
    // Which topics are open; the first one starts open so the page isn't just a list of titles.
    var expanded by remember(section) { mutableStateOf(setOf(0)) }
    LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back to Help") }
                Text(section.title, style = MaterialTheme.typography.titleLarge)
            }
        }
        itemsIndexed(section.topics) { i, topic ->
            val isOpen = i in expanded
            Card(Modifier.fillMaxWidth()) {
                Row(
                    Modifier.fillMaxWidth()
                        .clickable { expanded = if (isOpen) expanded - i else expanded + i }
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(topic.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(if (isOpen) "−" else "+", style = MaterialTheme.typography.titleMedium)
                }
                if (isOpen) {
                    Column(
                        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        topic.blocks.forEach { HelpBlockView(it) }
                    }
                }
            }
        }
        item { Text("", Modifier.padding(bottom = 16.dp)) }
    }
}

@Composable
private fun HelpBlockView(block: HelpBlock) {
    when (block) {
        is HelpBlock.Text -> Text(bold(block.text), style = MaterialTheme.typography.bodyMedium)
        is HelpBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            block.items.forEach { item ->
                Row {
                    Text("•  ", style = MaterialTheme.typography.bodyMedium)
                    Text(bold(item), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        is HelpBlock.Tip -> Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        ) {
            Text(bold("**Tip:** " + block.text), Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
        }
        is HelpBlock.Table -> HelpTable(block)
    }
}

@Composable
private fun HelpTable(table: HelpBlock.Table) {
    val line = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth().background(line).padding(1.dp)) {
        Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant)) {
            table.header.forEach { h ->
                Text(h, Modifier.weight(1f).padding(6.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            }
        }
        table.rows.forEach { row ->
            Row(Modifier.padding(top = 1.dp).background(MaterialTheme.colorScheme.surface)) {
                row.forEach { cell ->
                    Text(bold(cell), Modifier.weight(1f).padding(6.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** `**text**` → bold. */
private fun bold(s: String): AnnotatedString = buildAnnotatedString {
    var rest = s
    while (true) {
        val start = rest.indexOf("**")
        val end = if (start >= 0) rest.indexOf("**", start + 2) else -1
        if (start < 0 || end < 0) {
            append(rest)
            break
        }
        append(rest.substring(0, start))
        pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
        append(rest.substring(start + 2, end))
        pop()
        rest = rest.substring(end + 2)
    }
}
