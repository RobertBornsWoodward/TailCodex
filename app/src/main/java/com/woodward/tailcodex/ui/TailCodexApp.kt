package com.woodward.tailcodex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ForkRight
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Code
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.net.Uri
import com.woodward.tailcodex.domain.ApprovalDecision
import com.woodward.tailcodex.domain.ConnectionState
import com.woodward.tailcodex.domain.ConversationItem
import com.woodward.tailcodex.domain.MessageRole
import com.woodward.tailcodex.domain.ServerRequest
import com.woodward.tailcodex.domain.SessionState
import com.woodward.tailcodex.domain.ThreadListState
import com.woodward.tailcodex.domain.ThreadState
import com.woodward.tailcodex.domain.TailcodexThread
import com.woodward.tailcodex.domain.TurnState
import com.woodward.tailcodex.domain.ReviewTarget
import com.woodward.tailcodex.domain.RpcId
import com.woodward.tailcodex.presentation.TailCodexViewModel
import coil3.compose.AsyncImage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TailCodexApp(viewModel: TailCodexViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        if (state.config.token.isNotBlank() && state.connection is ConnectionState.Disconnected) {
            viewModel.connect(state.config.endpoint, state.config.token, state.config.defaultCwd)
        }
    }
    when {
        state.currentThread != null -> ChatScreen(state, viewModel)
        state.connection is ConnectionState.Ready || state.connection is ConnectionState.Reconciling ->
            ThreadListScreen(state, viewModel)
        else -> SetupScreen(state, viewModel)
    }
    state.serverRequests.firstOrNull()?.let {
        RequestDialog(it, state.connection is ConnectionState.Ready && !state.stale, viewModel)
    }
}

@Composable
private fun SetupScreen(state: SessionState, viewModel: TailCodexViewModel) {
    var endpoint by remember(state.config.endpoint) { mutableStateOf(state.config.endpoint) }
    var token by remember(state.config.token) { mutableStateOf(state.config.token) }
    var cwd by remember(state.config.defaultCwd) { mutableStateOf(state.config.defaultCwd) }
    var hostName by remember(state.config.hostName) { mutableStateOf(state.config.hostName) }
    val profiles = remember(state.config.hostId) { viewModel.hostProfiles() }
    val busy = state.connection is ConnectionState.Connecting || state.connection is ConnectionState.Initializing
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("TailCodex", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text("通过 Tailnet 安全连接 Codex app-server", color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (profiles.isNotEmpty()) {
                Text("已保存主机", style = MaterialTheme.typography.labelMedium)
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    profiles.forEach { profile -> OutlinedButton({ viewModel.connectProfile(profile) }) { Text(profile.name) } }
                }
            }
            OutlinedTextField(hostName, { hostName = it }, Modifier.fillMaxWidth(), label = { Text("主机名称") }, singleLine = true)
            OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("WSS 端点") }, singleLine = true)
            OutlinedTextField(
                token,
                { token = it },
                Modifier.fillMaxWidth(),
                label = { Text("访问令牌") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
            )
            OutlinedTextField(cwd, { cwd = it }, Modifier.fillMaxWidth(), label = { Text("默认工作目录") }, singleLine = true)
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            state.notice?.let { NoticeStrip(it, viewModel::clearNotice) }
            Button(
                onClick = { viewModel.connect(endpoint, token, cwd, hostName) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(connectionLabel(state.connection))
                } else Text("连接")
            }
            Text("访问令牌由 Android Keystore 加密；拒绝 ws:// 明文连接。", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadListScreen(state: SessionState, viewModel: TailCodexViewModel) {
    val loaded = state.threadList as? ThreadListState.Loaded
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Row(verticalAlignment = Alignment.CenterVertically) {
                    ConnectionDot(state.connection); Spacer(Modifier.width(10.dp)); Text("会话")
                } },
                actions = {
                    IconButton(viewModel::loadThreads) { Icon(Icons.Default.Refresh, "刷新") }
                    IconButton({ viewModel.disconnect() }) { Icon(Icons.Default.Close, "断开") }
                },
            )
        },
        floatingActionButton = {
            FilledIconButton(viewModel::startThread, Modifier.size(56.dp)) { Icon(Icons.Default.Add, "新会话") }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                state.search,
                viewModel::updateSearch,
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索会话") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.loadThreads() }),
            )
            state.error?.let { ErrorStrip(it, viewModel::reconnect) }
            state.notice?.let { NoticeStrip(it, viewModel::clearNotice) }
            if (state.threadList is ThreadListState.Loading) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else if (loaded?.threads.isNullOrEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("没有找到会话") }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(loaded.threads, key = TailcodexThread::id) { thread ->
                        ThreadRow(thread) { viewModel.openThread(thread) }
                        HorizontalDivider(Modifier.padding(start = 20.dp))
                    }
                    loaded.nextCursor?.let { cursor ->
                        item { TextButton({ viewModel.loadMoreThreads(cursor) }, Modifier.fillMaxWidth()) { Text("加载更多") } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: TailcodexThread, onClick: () -> Unit) {
    Card(onClick, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth()) {
                if (thread.pinned) Icon(Icons.Default.PushPin, null, Modifier.size(16.dp))
                Text(thread.title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(formatTime(thread.updatedAt), style = MaterialTheme.typography.labelSmall)
            }
            if (thread.preview.isNotBlank() && thread.preview != thread.title) Text(thread.preview, maxLines = 2)
            Text(thread.cwd, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: SessionState, viewModel: TailCodexViewModel) {
    val thread = requireNotNull(state.currentThread)
    val listState = rememberLazyListState()
    var composer by rememberSaveable(thread.id) { mutableStateOf(viewModel.loadDraft(thread.id)) }
    var selectedImages by remember(thread.id) { mutableStateOf(emptyList<Uri>()) }
    var selectedFiles by remember(thread.id) { mutableStateOf(emptyList<Uri>()) }
    var cameraImage by remember(thread.id) { mutableStateOf<Bitmap?>(null) }
    var pasteDetected by remember(thread.id) { mutableStateOf(false) }
    val galleryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) {
        selectedImages = it
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) {
        cameraImage = it
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {
        selectedFiles = it
    }
    var menu by remember { mutableStateOf(false) }
    var reviewDialog by remember { mutableStateOf(false) }
    val writableNetwork = state.connection is ConnectionState.Ready && !state.stale
    LaunchedEffect(state.items.size, (state.items.lastOrNull() as? ConversationItem.Message)?.markdown?.length) {
        if (state.items.isNotEmpty()) listState.animateScrollToItem(state.items.lastIndex)
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Column {
                    Text(thread.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                    Text(turnLabel(state.turn), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(leaseLabel(state.thread), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } },
                navigationIcon = { IconButton(viewModel::closeThread) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回") } },
                actions = {
                    IconButton({ menu = true }) { Icon(Icons.Default.MoreVert, "会话操作") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem({ Text(if (thread.pinned) "取消置顶" else "置顶") }, {
                            viewModel.pinThread(!thread.pinned); menu = false
                        }, leadingIcon = { Icon(Icons.Default.PushPin, null) })
                        DropdownMenuItem(
                            { Text("分叉会话") },
                            { viewModel.forkThread(); menu = false },
                            leadingIcon = { Icon(Icons.Default.ForkRight, null) },
                            enabled = writableNetwork,
                        )
                        DropdownMenuItem(
                            { Text("开始代码审查") },
                            { reviewDialog = true; menu = false },
                            leadingIcon = { Icon(Icons.Default.RateReview, null) },
                            enabled = writableNetwork,
                        )
                        DropdownMenuItem(
                            { Text("归档") },
                            { viewModel.archiveThread(); menu = false },
                            leadingIcon = { Icon(Icons.Default.Archive, null) },
                            enabled = writableNetwork,
                        )
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                value = composer,
                running = state.turn is TurnState.Running,
                enabled = writableNetwork,
                attachmentCount = selectedImages.size + selectedFiles.size + if (cameraImage == null) 0 else 1,
                pasteDetected = pasteDetected,
                onValueChange = {
                    pasteDetected = it.length - composer.length > 40 && it.contains('\n')
                    composer = it
                    viewModel.saveDraft(thread.id, it)
                },
                onSend = { if (composer.isNotBlank() || selectedImages.isNotEmpty() || selectedFiles.isNotEmpty() || cameraImage != null) {
                    viewModel.sendMessage(composer, selectedImages, cameraImage, selectedFiles)
                    composer = ""; selectedImages = emptyList(); selectedFiles = emptyList(); cameraImage = null
                    pasteDetected = false
                    viewModel.saveDraft(thread.id, "")
                } },
                onStop = viewModel::interrupt,
                onGallery = { galleryLauncher.launch("image/*") },
                onCamera = { cameraLauncher.launch(null) },
                onFile = { fileLauncher.launch(arrayOf("text/*", "application/json", "application/xml", "application/yaml")) },
                onCode = {
                    if (composer.isNotBlank() && !composer.trimStart().startsWith("```")) {
                        composer = "```\n$composer\n```"
                        pasteDetected = false
                        viewModel.saveDraft(thread.id, composer)
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            NetworkBanner(state, viewModel)
            state.error?.let { ErrorStrip(it, viewModel::reconnect) }
            state.notice?.let { NoticeStrip(it, viewModel::clearNotice) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) { items(state.items, key = ConversationItem::id) { ConversationCard(it) } }
        }
    }
    if (reviewDialog) ReviewTargetDialog(
        dismiss = { reviewDialog = false },
        start = { viewModel.startReview(it); reviewDialog = false },
    )
}

@Composable
private fun ConversationCard(item: ConversationItem) {
    when (item) {
        is ConversationItem.Message -> MessageCard(item)
        is ConversationItem.CommandExecution -> ExpandableEventCard("命令 · ${item.status}", item.command) {
            item.cwd?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
            if (item.output.isNotBlank()) SourcePanel(item.output, "输出")
        }
        is ConversationItem.FileChange -> ExpandableEventCard("文件变更 · ${item.status}", item.files.joinToString()) {
            item.unifiedDiff?.let { FileDiffViewer(it, item.files) }
        }
        is ConversationItem.McpCall -> ExpandableEventCard("MCP · ${item.server}/${item.tool}", item.status) {
            item.output?.let { SourcePanel(it, "结果") }
        }
        is ConversationItem.Review -> ExpandableEventCard(item.title, item.status) { item.body?.let { RichText(it) } }
        is ConversationItem.Status -> ExpandableEventCard(item.label, item.detail.orEmpty()) {}
    }
}

@Composable
private fun FileDiffViewer(diff: String, fallbackPaths: List<String>) {
    val parsed = remember(diff) { UnifiedDiffParser.parse(diff) }
    val files = if (parsed.isEmpty()) {
        fallbackPaths.map { FileDiff(it, diff, 0, 0) }
    } else parsed
    var selectedPath by remember(diff) { mutableStateOf(files.firstOrNull()?.path) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        files.forEach { file ->
            OutlinedButton(
                onClick = { selectedPath = file.path },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(file.path, Modifier.weight(1f), maxLines = 1)
                Text("+${file.additions} -${file.deletions}")
            }
        }
        files.firstOrNull { it.path == selectedPath }?.let { DiffPanel(it.unifiedDiff) }
    }
}

@Composable
private fun MessageCard(message: ConversationItem.Message) {
    val user = message.role == MessageRole.USER
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (user) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (user) .9f else 1f),
            shape = RoundedCornerShape(16.dp),
            color = if (user) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            tonalElevation = if (user) 0.dp else 1.dp,
        ) { RichText(message.markdown, Modifier.padding(14.dp)) }
    }
}

@Composable
private fun RichText(source: String, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        RichTextParser.parse(source).forEach { block -> when (block) {
            is RichTextBlock.Markdown -> MarkdownContent(block.source)
            is RichTextBlock.Code -> SourcePanel(block.source, block.language ?: "代码", block.language ?: "plain")
            is RichTextBlock.InlineMath -> FormulaPanel(block.latex, false)
            is RichTextBlock.DisplayMath -> FormulaPanel(block.latex, true)
        } }
    }
}

@Composable
private fun MarkdownContent(source: String) {
    val imagePattern = Regex("!\\[([^]]*)]\\(([^)]+)\\)")
    var cursor = 0
    imagePattern.findAll(source).forEach { match ->
        if (match.range.first > cursor) MarkdownLines(source.substring(cursor, match.range.first))
        MarkdownImage(match.groupValues[2], match.groupValues[1])
        cursor = match.range.last + 1
    }
    if (cursor < source.length) MarkdownLines(source.substring(cursor))
}

@Composable
private fun MarkdownImage(url: String, alt: String) {
    var failed by remember(url) { mutableStateOf(false) }
    if (failed) {
        Text("[图片加载失败: ${alt.ifBlank { "无替代文本" }}] $url", color = MaterialTheme.colorScheme.error)
    } else {
        AsyncImage(
            model = url,
            contentDescription = alt.ifBlank { "Markdown 图片" },
            modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp, max = 360.dp),
            contentScale = ContentScale.Fit,
            onError = { failed = true },
        )
    }
}

@Composable
private fun MarkdownLines(source: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        val lines = source.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (line.count { it == '|' } >= 2) {
                val table = mutableListOf<String>()
                while (index < lines.size && lines[index].count { it == '|' } >= 2) {
                    if (!lines[index].replace("|", "").replace("-", "").replace(":", "").isBlank()) {
                        table += lines[index]
                    }
                    index++
                }
                MarkdownTable(table)
                continue
            }
            val heading = line.takeWhile { it == '#' }.length.takeIf { it in 1..6 && line.getOrNull(it) == ' ' }
            when {
                heading != null -> MarkdownInline(line.drop(heading + 1), style = when (heading) {
                    1 -> MaterialTheme.typography.headlineSmall
                    2 -> MaterialTheme.typography.titleLarge
                    else -> MaterialTheme.typography.titleMedium
                }, fontWeight = FontWeight.SemiBold)
                line.startsWith("> ") -> MarkdownInline(line.drop(2), Modifier.background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp))
                line.startsWith("- ") || line.startsWith("* ") -> MarkdownInline("• ${line.drop(2)}")
                line.matches(Regex("\\d+\\. .*")) -> MarkdownInline(line)
                line.isNotBlank() -> MarkdownInline(line)
                else -> Spacer(Modifier.height(2.dp))
            }
            index++
        }
    }
}

@Composable
private fun MarkdownInline(
    value: String,
    modifier: Modifier = Modifier,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.bodyLarge,
    fontWeight: FontWeight? = null,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = buildAnnotatedString {
        var cursor = 0
        val matches = Regex("(!?)\\[([^]]*)]\\(([^)]+)\\)|`([^`]+)`").findAll(value)
        matches.forEach { match ->
            append(value.substring(cursor, match.range.first).cleanMarkdownMarkers())
            if (match.groupValues[4].isNotEmpty()) {
                withStyle(
                    SpanStyle(
                        background = MaterialTheme.colorScheme.surfaceVariant,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    ),
                ) { append(match.groupValues[4]) }
            } else {
                val image = match.groupValues[1] == "!"
                val label = match.groupValues[2]
                val url = match.groupValues[3]
                if (image) {
                    append("[图片: ${label.ifBlank { "无替代文本" }}] ($url)")
                } else {
                    withLink(LinkAnnotation.Url(url)) {
                        withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)) {
                            append(label)
                        }
                    }
                }
            }
            cursor = match.range.last + 1
        }
        append(value.substring(cursor).cleanMarkdownMarkers())
    }
    Text(annotated, modifier, style = style, fontWeight = fontWeight)
}

private fun String.cleanMarkdownMarkers(): String = replace("**", "").replace("__", "").replace("`", "")

@Composable
private fun MarkdownTable(rows: List<String>) {
    Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).background(MaterialTheme.colorScheme.surfaceVariant)) {
        rows.forEachIndexed { rowIndex, row ->
            Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp)) {
                row.trim('|').split('|').forEach { cell ->
                    MarkdownInline(
                        cell.trim(),
                        Modifier.width(150.dp).padding(end = 8.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (rowIndex == 0) FontWeight.SemiBold else null,
                    )
                }
            }
            if (rowIndex < rows.lastIndex) HorizontalDivider()
        }
    }
}

@Composable
private fun ExpandableEventCard(title: String, summary: String, content: @Composable () -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            if (summary.isNotBlank()) Text(summary, maxLines = if (expanded) Int.MAX_VALUE else 2, fontFamily = FontFamily.Monospace)
            if (expanded) content() else Text("点按展开", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun SourcePanel(source: String, label: String, language: String? = null) {
    val clipboard = LocalClipboardManager.current
    var fullscreen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton({ clipboard.setText(AnnotatedString(source)) }) { Icon(Icons.Default.ContentCopy, "复制") }
            IconButton({ fullscreen = true }) { Icon(Icons.Default.Fullscreen, "全屏") }
        }
        SelectionContainer {
            Text(
                highlightedSource(source, language),
                Modifier.horizontalScroll(rememberScrollState()).padding(10.dp),
                fontFamily = FontFamily.Monospace,
                softWrap = false,
            )
        }
    }
    if (fullscreen) FullscreenSource(label, source, language) { fullscreen = false }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FormulaPanel(latex: String, display: Boolean, renderer: MathRenderer = RaTeXMathRenderer) {
    val text = renderer.normalize(latex)
    val clipboard = LocalClipboardManager.current
    var fullscreen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(if (display) "公式" else "行内公式", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton({ clipboard.setText(AnnotatedString(text)) }) { Icon(Icons.Default.ContentCopy, "复制 LaTeX") }
            IconButton({ fullscreen = true }) { Icon(Icons.Default.Fullscreen, "全屏公式") }
        }
        Box(
            Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = { fullscreen = true },
                    onLongClick = { clipboard.setText(AnnotatedString(text)) },
                )
                .horizontalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            renderer.Render(text, display, if (display) 24f else 18f)
        }
    }
    if (fullscreen) Dialog({ fullscreen = false }) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text("公式", Modifier.padding(16.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton({ fullscreen = false }) { Icon(Icons.Default.Close, "关闭") }
                }
                Box(Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).padding(20.dp)) {
                    renderer.Render(text, true, 32f)
                }
            }
        }
    }
}

@Composable
private fun DiffPanel(diff: String) {
    val clipboard = LocalClipboardManager.current
    var fullscreen by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Unified diff", Modifier.padding(start = 10.dp).weight(1f), style = MaterialTheme.typography.labelMedium)
            IconButton({ clipboard.setText(AnnotatedString(diff)) }) { Icon(Icons.Default.ContentCopy, "复制 diff") }
            IconButton({ fullscreen = true }) { Icon(Icons.Default.Fullscreen, "全屏 diff") }
        }
        SelectionContainer { Column(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(8.dp)) {
            diff.lines().forEach { line ->
                val color = when {
                    line.startsWith("+") && !line.startsWith("+++") -> Color(0xFF196F3D)
                    line.startsWith("-") && !line.startsWith("---") -> Color(0xFF922B21)
                    line.startsWith("@@") -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.onSurface
                }
                Text(line, color = color, fontFamily = FontFamily.Monospace, softWrap = false)
            }
        } }
    }
    if (fullscreen) FullscreenSource("Unified diff", diff, dismiss = { fullscreen = false })
}

@Composable
private fun FullscreenSource(title: String, source: String, language: String? = null, dismiss: () -> Unit) {
    Dialog(dismiss) {
        Surface(Modifier.fillMaxSize(), shape = RoundedCornerShape(12.dp)) {
            Column {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(title, Modifier.padding(16.dp).weight(1f), style = MaterialTheme.typography.titleMedium)
                    IconButton(dismiss) { Icon(Icons.Default.Close, "关闭") }
                }
                SelectionContainer {
                    Text(
                        highlightedSource(source, language),
                        Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).verticalScroll(rememberScrollState()).padding(16.dp),
                        fontFamily = FontFamily.Monospace,
                        softWrap = false,
                    )
                }
            }
        }
    }
}

@Composable
private fun highlightedSource(source: String, language: String?): AnnotatedString {
    if (language == null) return AnnotatedString(source)
    return SyntaxHighlighter.highlight(
        source,
        language,
        SyntaxPalette(
            keyword = MaterialTheme.colorScheme.primary,
            string = MaterialTheme.colorScheme.tertiary,
            number = MaterialTheme.colorScheme.secondary,
            comment = MaterialTheme.colorScheme.onSurfaceVariant,
            plain = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun RequestDialog(request: ServerRequest, enabled: Boolean, viewModel: TailCodexViewModel) {
    when (request) {
        is ServerRequest.UserInput -> UserInputDialog(request, enabled, viewModel)
        is ServerRequest.McpElicitation -> McpDialog(request, enabled, viewModel)
        is ServerRequest.CommandApproval,
        is ServerRequest.FileApproval,
        is ServerRequest.PermissionsApproval,
        -> ApprovalDialog(request, enabled) { decision -> viewModel.resolveApproval(request, decision) }
        is ServerRequest.DynamicToolCall, is ServerRequest.Unknown -> Unit
    }
}

@Composable
private fun ApprovalDialog(
    request: ServerRequest,
    enabled: Boolean,
    onDecision: (ApprovalDecision) -> Unit,
) {
    val detail = when (request) {
        is ServerRequest.CommandApproval -> listOfNotNull(request.command, request.cwd, request.reason, request.networkHost).joinToString("\n")
        is ServerRequest.FileApproval -> listOfNotNull(request.grantRoot, request.reason).joinToString("\n")
        is ServerRequest.PermissionsApproval -> listOf(request.cwd, request.permissionsJson, request.reason.orEmpty()).joinToString("\n")
        else -> ""
    }
    val decisions = (request as? ServerRequest.CommandApproval)?.availableDecisions ?: ApprovalDecision.entries.toSet()
    AlertDialog(
        onDismissRequest = {},
        title = { Text("需要授权") },
        text = { Column { Text(detail); Text("连接断开后，此请求会被标记为陈旧，不能提交。", style = MaterialTheme.typography.labelSmall) } },
        confirmButton = { Row {
            if (ApprovalDecision.ACCEPT_FOR_SESSION in decisions) TextButton({ onDecision(ApprovalDecision.ACCEPT_FOR_SESSION) }, enabled = enabled) { Text("本会话允许") }
            if (ApprovalDecision.ACCEPT in decisions) TextButton({ onDecision(ApprovalDecision.ACCEPT) }, enabled = enabled) { Text("本次允许") }
        } },
        dismissButton = { Row {
            if (ApprovalDecision.DECLINE in decisions) TextButton({ onDecision(ApprovalDecision.DECLINE) }, enabled = enabled) { Text("拒绝") }
            if (ApprovalDecision.CANCEL in decisions) TextButton({ onDecision(ApprovalDecision.CANCEL) }, enabled = enabled) { Text("拒绝并停止") }
        } },
    )
}

@Composable
private fun UserInputDialog(request: ServerRequest.UserInput, enabled: Boolean, viewModel: TailCodexViewModel) {
    val answers = remember(request.requestId.toString()) { mutableStateMapOf<String, String>() }
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Codex 需要更多信息") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            request.questions.forEach { question ->
                Text(question.header, fontWeight = FontWeight.SemiBold)
                Text(question.question)
                question.options.forEach { option -> OutlinedButton({ answers[question.id] = option.label }, Modifier.fillMaxWidth()) {
                    Column(Modifier.fillMaxWidth()) { Text(option.label); if (option.description.isNotBlank()) Text(option.description, style = MaterialTheme.typography.labelSmall) }
                } }
                if (question.options.isEmpty() || question.allowsOther) OutlinedTextField(
                    answers[question.id].orEmpty(),
                    { answers[question.id] = it },
                    label = { Text("回答") },
                    visualTransformation = if (question.secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                )
            }
        } },
        confirmButton = { TextButton({
            viewModel.answerUserInput(request, request.questions.associate { it.id to listOf(answers[it.id].orEmpty()) })
        }, enabled = enabled && request.questions.all { !answers[it.id].isNullOrBlank() }) { Text("提交") } },
    )
}

@Composable
private fun McpDialog(request: ServerRequest.McpElicitation, enabled: Boolean, viewModel: TailCodexViewModel) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("MCP 请求 · ${request.serverName}") },
        text = { Column { Text(request.message); request.url?.let { Text(it, style = MaterialTheme.typography.labelSmall) } } },
        confirmButton = { TextButton({ viewModel.answerMcp(request, "accept", emptyMap()) }, enabled = enabled) { Text("允许") } },
        dismissButton = { Row {
            TextButton({ viewModel.answerMcp(request, "decline") }, enabled = enabled) { Text("拒绝") }
            TextButton({ viewModel.answerMcp(request, "cancel") }, enabled = enabled) { Text("取消") }
        } },
    )
}

@Composable
private fun ReviewTargetDialog(dismiss: () -> Unit, start: (ReviewTarget) -> Unit) {
    var branch by remember { mutableStateOf("") }
    var commit by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = dismiss,
        title = { Text("选择审查范围") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Button({ start(ReviewTarget.UncommittedChanges) }, Modifier.fillMaxWidth()) { Text("审查未提交变更") }
            OutlinedTextField(branch, { branch = it }, label = { Text("基准分支") }, trailingIcon = {
                TextButton({ start(ReviewTarget.BaseBranch(branch)) }, enabled = branch.isNotBlank()) { Text("开始") }
            })
            OutlinedTextField(commit, { commit = it }, label = { Text("Commit SHA") }, trailingIcon = {
                TextButton({ start(ReviewTarget.Commit(commit)) }, enabled = commit.isNotBlank()) { Text("开始") }
            })
            OutlinedTextField(custom, { custom = it }, label = { Text("自定义审查要求") }, minLines = 3)
            OutlinedButton({ start(ReviewTarget.Custom(custom)) }, Modifier.fillMaxWidth(), enabled = custom.isNotBlank()) { Text("按自定义要求审查") }
        } },
        confirmButton = {},
        dismissButton = { TextButton(dismiss) { Text("取消") } },
    )
}

@Composable
private fun Composer(
    value: String,
    running: Boolean,
    enabled: Boolean,
    attachmentCount: Int,
    pasteDetected: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    onGallery: () -> Unit,
    onCamera: () -> Unit,
    onFile: () -> Unit,
    onCode: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).imePadding().padding(10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            if (attachmentCount > 0) Text("已选择 $attachmentCount 个附件", style = MaterialTheme.typography.labelSmall)
            if (pasteDetected) Text("检测到多行粘贴；可点代码按钮保留格式", style = MaterialTheme.typography.labelSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onGallery, enabled = enabled) { Icon(Icons.Default.PhotoLibrary, "图库或截图") }
                IconButton(onCamera, enabled = enabled) { Icon(Icons.Default.CameraAlt, "相机") }
                IconButton(onFile, enabled = enabled) { Icon(Icons.Default.AttachFile, "文本或代码文件") }
                IconButton(onCode, enabled = enabled && value.isNotBlank()) { Icon(Icons.Default.Code, "格式化为代码块") }
            }
            OutlinedTextField(
                value,
                onValueChange,
                Modifier.fillMaxWidth(),
                enabled = enabled,
                placeholder = { Text(if (!enabled) "等待连接与对账…" else if (running) "追加指令…" else "发送指令…") },
                minLines = 1,
                maxLines = 6,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
            )
        }
        if (running) FilledIconButton(onStop, enabled = enabled) { Icon(Icons.Default.Stop, "停止") }
        else FilledIconButton(onSend, enabled = enabled && (value.isNotBlank() || attachmentCount > 0)) { Icon(Icons.AutoMirrored.Filled.Send, "发送") }
    }
}

@Composable
private fun NetworkBanner(state: SessionState, viewModel: TailCodexViewModel) {
    if (state.connection is ConnectionState.Ready && !state.stale) return
    val text = when (val connection = state.connection) {
        is ConnectionState.Reconciling -> "正在与服务器对账；暂时禁止写入"
        is ConnectionState.Connecting -> "正在重连（第 ${connection.reconnectAttempt} 次）"
        is ConnectionState.Initializing -> "已连接，正在初始化协议"
        is ConnectionState.Disconnected -> if (state.stale) {
            "连接已断开；当前内容是陈旧快照。最后已知：${turnLabel(state.turn)}"
        } else "连接已断开"
        ConnectionState.Ready -> ""
    }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f)); TextButton(viewModel::reconnect) { Text("立即重试") }
    }
}

@Composable
private fun NoticeStrip(message: String, dismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSecondaryContainer)
        TextButton(dismiss) { Text("知道了") }
    }
}

@Composable
private fun ErrorStrip(message: String, retry: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(message, Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
        OutlinedButton(retry) { Text("重试") }
    }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
    val color = when (state) {
        ConnectionState.Ready -> Color(0xFF27A269)
        is ConnectionState.Connecting, is ConnectionState.Initializing, is ConnectionState.Reconciling -> Color(0xFFE5A50A)
        is ConnectionState.Disconnected -> Color(0xFFC01C28)
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private fun connectionLabel(state: ConnectionState): String = when (state) {
    is ConnectionState.Connecting -> "正在连接…"
    is ConnectionState.Initializing -> "正在初始化…"
    is ConnectionState.Reconciling -> "正在对账…"
    ConnectionState.Ready -> "已连接"
    is ConnectionState.Disconnected -> "连接"
}

private fun turnLabel(state: TurnState): String = when (state) {
    TurnState.Idle, is TurnState.Completed, is TurnState.Interrupted -> "空闲"
    is TurnState.Failed -> "失败"
    is TurnState.Running -> when (state.phase) {
        TurnState.Phase.EXECUTING -> "正在运行"
        TurnState.Phase.WAITING_FOR_APPROVAL -> "等待授权"
        TurnState.Phase.WAITING_FOR_USER_INPUT -> "等待回答"
        TurnState.Phase.WAITING_FOR_MCP_ELICITATION -> "等待 MCP 确认"
    }
}

private fun leaseLabel(state: ThreadState): String = when (state) {
    ThreadState.NoThread -> "未选择会话"
    is ThreadState.Resuming -> "正在升级为手机端控制"
    is ThreadState.ReadOnly -> when (state.lease) {
        com.woodward.tailcodex.domain.ThreadLease.NONE -> "当前只读"
        com.woodward.tailcodex.domain.ThreadLease.LOCAL_PHONE -> "手机端 soft lease"
        com.woodward.tailcodex.domain.ThreadLease.OTHER_CLIENT -> "桌面端或其他客户端可能正在使用"
        com.woodward.tailcodex.domain.ThreadLease.UNKNOWN -> "控制权未知，写入前将重新确认"
    }
    is ThreadState.Active -> when (state.lease) {
        com.woodward.tailcodex.domain.ThreadLease.LOCAL_PHONE -> "手机端正在控制（soft lease）"
        else -> "控制权需要重新确认"
    }
}

private val dateFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private fun formatTime(epochSeconds: Long): String = runCatching {
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(dateFormatter)
}.getOrDefault("")

internal enum class RendererScenario { MARKDOWN_MATH, CODE_COMMAND, DIFF, APPROVAL, COMPOSER }

@Composable
internal fun MobileRendererShowcase(
    scenario: RendererScenario,
    darkTheme: Boolean = false,
) {
    TailCodexTheme(darkTheme = darkTheme) {
        Surface(Modifier.fillMaxSize()) {
            when (scenario) {
                RendererScenario.MARKDOWN_MATH -> Column(
                    Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MessageCard(ConversationItem.Message(
                        "markdown",
                        MessageRole.ASSISTANT,
                        "# 结果\n- 长 Markdown 列表\n- [链接](https://example.test)\n> 移动端引用内容\n\n| 项目 | 状态 |\n|---|---|\n| Markdown | 完成 |\n\n行内公式 \\(x^2+y^2=1\\)\n\n\\[\\int_0^1 x^2\\,dx=\\frac{1}{3}+\\sum_{i=1}^{n}\\frac{i^2}{n^3}\\]",
                    ))
                }
                RendererScenario.CODE_COMMAND -> Column(
                    Modifier.fillMaxSize().padding(12.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MessageCard(ConversationItem.Message(
                        "code",
                        MessageRole.ASSISTANT,
                        "```kotlin\nfun reconcile(threadId: String) {\n    val immutableVeryLongValue = repository.readThread(threadId, includeTurns = true)\n    // Long source remains horizontally scrollable and selectable.\n    require(immutableVeryLongValue.isSuccess)\n}\n```",
                    ))
                    ConversationCard(ConversationItem.CommandExecution(
                        "command",
                        "./gradlew test lint assembleDebug --continue --stacktrace",
                        "/home/Woodward/Documents/TailCodex",
                        "success · 238 lines",
                        (1..40).joinToString("\n") { "test line $it" },
                    ))
                }
                RendererScenario.DIFF -> FileDiffViewer(
                    buildString {
                        repeat(3) { file ->
                            append("diff --git a/File$file.kt b/File$file.kt\n--- a/File$file.kt\n+++ b/File$file.kt\n@@ -1,2 +1,3 @@\n-old$file\n+new$file\n+extra$file\n")
                        }
                    },
                    emptyList(),
                )
                RendererScenario.APPROVAL -> ApprovalDialog(
                    ServerRequest.CommandApproval(
                        RpcId(42), "thread", "turn", "item",
                        "sudo systemctl restart codex-bridge --no-block",
                        "/home/Woodward/Documents/TailCodex",
                        "需要重启主机侧服务以应用配置",
                        "arch.tailnet", "tcp",
                        ApprovalDecision.entries.toSet(),
                    ),
                    enabled = true,
                    onDecision = {},
                )
                RendererScenario.COMPOSER -> Box(
                    Modifier.fillMaxSize().padding(12.dp),
                    contentAlignment = Alignment.BottomCenter,
                ) {
                    Composer(
                        value = "粘贴的多行代码\nval answer = 42\nprintln(answer)",
                        running = true,
                        enabled = true,
                        attachmentCount = 2,
                        pasteDetected = true,
                        onValueChange = {}, onSend = {}, onStop = {}, onGallery = {},
                        onCamera = {}, onFile = {}, onCode = {},
                    )
                }
            }
        }
    }
}

@Preview(name = "Small phone", device = "spec:width=360dp,height=640dp,dpi=420")
@Composable
private fun MobileRendererPreview() = MobileRendererShowcase(RendererScenario.MARKDOWN_MATH)
