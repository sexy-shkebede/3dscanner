package com.ghidroid.ui.workspace

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearWavyProgressIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ghidroid.R
import com.ghidroid.analysis.AnalysisStage
import com.ghidroid.analysis.FunctionKind
import com.ghidroid.loader.Addr
import com.ghidroid.loader.Section
import com.ghidroid.ui.components.Pill
import com.ghidroid.ui.components.ShapeIcon
import com.ghidroid.ui.components.formatSize
import com.ghidroid.ui.theme.CodeFont

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier.fillMaxWidth(),
    ) { Column(Modifier.padding(18.dp)) { content() } }
}

@Composable
private fun CardTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun KV(label: String, value: String, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 5.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(128.dp))
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) CodeFont else null,
            color = if (onClick != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OverviewPane(ws: Workspace, onChangeLanguage: () -> Unit) {
    val program = ws.program
    val b = program.binary
    val analysis by ws.analyzer.state.collectAsStateWithLifecycle()
    val functions by program.functions.collectAsStateWithLifecycle()
    val strings by program.strings.collectAsStateWithLifecycle()
    val digits = if (b.blocks.any { Addr.cmp(it.end, 0xFFFFFFFFL) > 0 }) 16 else 8

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item {
            Surface(shape = MaterialTheme.shapes.extraLarge, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ShapeIcon(
                            Icons.Rounded.Memory,
                            MaterialShapes.Cookie9Sided,
                            container = MaterialTheme.colorScheme.primary,
                            content = MaterialTheme.colorScheme.onPrimary,
                            size = 56.dp,
                        )
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                program.displayName,
                                style = MaterialTheme.typography.titleLargeEmphasized,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                "${b.fileType} · ${formatSize(program.bin.size)}",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Pill(b.formatName)
                        Pill(b.machine.takeIf { it != "—" } ?: program.language.processor)
                        Pill(if (program.language.bigEndian) "Big endian" else "Little endian")
                        Pill("${program.pointerSize * 8}-bit")
                        if ("stripped" in b.notes) Pill(stringResource(R.string.stripped))
                        if ("dotnet" in b.notes) Pill(".NET")
                    }
                    Spacer(Modifier.height(16.dp))
                    Row {
                        Stat(functions.size.toString(), stringResource(R.string.stat_functions), Modifier.weight(1f))
                        Stat(functions.count { it.kind == FunctionKind.IMPORT }.toString(), stringResource(R.string.stat_imports), Modifier.weight(1f))
                        Stat(strings.size.toString(), stringResource(R.string.stat_strings), Modifier.weight(1f))
                    }
                    if (analysis.stage != AnalysisStage.DONE) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            when (analysis.stage) {
                                AnalysisStage.STRINGS -> stringResource(R.string.analysis_strings)
                                AnalysisStage.FUNCTIONS -> stringResource(R.string.analysis_functions, analysis.discovered)
                                else -> stringResource(R.string.analysis_waiting)
                            },
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearWavyProgressIndicator(progress = { analysis.progress }, modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
        if ("dotnet" in b.notes) item {
            Card { Text(stringResource(R.string.note_dotnet), style = MaterialTheme.typography.bodyMedium) }
        }
        item {
            Card {
                CardTitle(stringResource(R.string.processor))
                KV(stringResource(R.string.language), program.language.id, mono = true)
                KV(stringResource(R.string.description), program.languageDescription.ifEmpty { program.language.description })
                KV(stringResource(R.string.compiler_spec), "${program.compiler.name} (${program.compiler.id})")
                KV(stringResource(R.string.instruction_align), program.alignment.toString())
                Spacer(Modifier.height(8.dp))
                FilledTonalButton(onClick = onChangeLanguage) {
                    Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.change_language))
                }
            }
        }
        item {
            Card {
                CardTitle(stringResource(R.string.header))
                KV(stringResource(R.string.format), "${b.formatName} · ${b.fileType}")
                KV(stringResource(R.string.image_base), "0x" + Addr.hex(b.imageBase, digits), mono = true)
                b.entryPoints.forEach { e ->
                    KV(stringResource(R.string.entry_point), "0x" + Addr.hex(e, digits), mono = true) { ws.goTo(e) }
                }
                b.info.forEach { (k, v) -> KV(k, v, mono = v.startsWith("0x") || k.contains("ID", ignoreCase = false)) }
            }
        }
        if (b.libraries.isNotEmpty()) item {
            Card {
                CardTitle(stringResource(R.string.libraries, b.libraries.size))
                b.libraries.forEach { lib ->
                    Text(lib, style = MaterialTheme.typography.bodyMedium, fontFamily = CodeFont, modifier = Modifier.padding(vertical = 3.dp))
                }
            }
        }
        item {
            Text(
                stringResource(R.string.sections, b.sections.size),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp),
            )
        }
        items(b.sections) { s ->
            SectionRow(s, digits) {
                if (s.alloc && program.isMapped(s.address)) ws.goTo(s.address)
                else if (s.fileSize > 0) {
                    ws.hexTarget = s.fileOffset to 16
                    ws.tab = WsTab.HEX
                }
            }
        }
    }
}

@Composable
private fun Stat(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(value, style = MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f))
    }
}

@Composable
private fun SectionRow(s: Section, digits: Int, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(s.name, style = MaterialTheme.typography.titleSmall, fontFamily = CodeFont, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (if (s.alloc) "0x" + Addr.hex(s.address, digits) + " · " else "") + formatSize(s.size) + (if (s.kind.isNotEmpty()) " · ${s.kind}" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = CodeFont,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val flags = buildString {
                append(if (s.alloc) 'r' else '-')
                append(if (s.write) 'w' else '-')
                append(if (s.exec) 'x' else '-')
            }
            Pill(
                flags,
                mono = true,
                container = if (s.exec) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
                content = if (s.exec) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
