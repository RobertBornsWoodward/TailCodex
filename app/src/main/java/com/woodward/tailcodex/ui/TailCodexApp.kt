package com.woodward.tailcodex.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.woodward.tailcodex.data.ApprovalKind
import com.woodward.tailcodex.data.ChatEntry
import com.woodward.tailcodex.data.ConnectionStatus
import com.woodward.tailcodex.data.MessageRole
import com.woodward.tailcodex.data.TailCodexState
import com.woodward.tailcodex.data.TailCodexViewModel
import com.woodward.tailcodex.data.ThreadSummary
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun TailCodexApp(viewModel: TailCodexViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        if (state.config.token.isNotBlank() && state.status == ConnectionStatus.DISCONNECTED) {
            viewModel.reconnect()
        }
    }

    when {
        state.activeThread != null -> ChatScreen(state, viewModel)
        state.status == ConnectionStatus.CONNECTED -> ThreadListScreen(state, viewModel)
        else -> SetupScreen(state, viewModel)
    }

    state.approval?.let { approval ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text(approval.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(approval.detail)
                    if (approval.kind == ApprovalKind.PERMISSIONS) {
                        Text("权限仅授予当前任务。", style = MaterialTheme.typography.labelMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.resolveApproval("accept") }) { Text("本次允许") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { viewModel.resolveApproval("decline") }) { Text("拒绝") }
                    TextButton(onClick = { viewModel.resolveApproval("cancel") }) { Text("拒绝并停止") }
                }
            },
        )
    }
}

@Composable
private fun SetupScreen(state: TailCodexState, viewModel: TailCodexViewModel) {
    var endpoint by remember(state.config.endpoint) { mutableStateOf(state.config.endpoint) }
    var token by remember(state.config.token) { mutableStateOf(state.config.token) }
    var cwd by remember(state.config.defaultCwd) { mutableStateOf(state.config.defaultCwd) }
    val busy = state.status == ConnectionStatus.CONNECTING

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("TailCodex", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
            Text(
                "通过 Tailnet 安全连接本机 Codex",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = endpoint,
                onValueChange = { endpoint = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("WSS 端点") },
                singleLine = true,
                placeholder = { Text("wss://arch.example.ts.net:8443") },
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("访问令牌") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
            )
            OutlinedTextField(
                value = cwd,
                onValueChange = { cwd = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("默认工作目录") },
                singleLine = true,
            )
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = { viewModel.connect(endpoint, token, cwd) },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                enabled = !busy,
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("正在连接…")
                } else {
                    Text(if (state.status == ConnectionStatus.RECONNECTING) "立即重试" else "连接")
                }
            }
            Text(
                "令牌由 Android Keystore 加密；应用拒绝 ws:// 明文连接。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ThreadListScreen(state: TailCodexState, viewModel: TailCodexViewModel) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ConnectionDot(state.status)
                        Spacer(Modifier.width(10.dp))
                        Text("会话")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::loadThreads) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新")
                    }
                    IconButton(onClick = { viewModel.disconnect() }) {
                        Icon(Icons.Default.Close, contentDescription = "断开")
                    }
                },
            )
        },
        floatingActionButton = {
            FilledIconButton(onClick = viewModel::startThread, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Default.Add, contentDescription = "新会话")
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = state.search,
                onValueChange = viewModel::updateSearch,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("搜索会话") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.search.isNotEmpty()) {
                        IconButton(onClick = { viewModel.updateSearch(""); viewModel.loadThreads() }) {
                            Icon(Icons.Default.Close, contentDescription = "清除")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { viewModel.loadThreads() }),
            )
            state.error?.let { ErrorStrip(it, viewModel::reconnect) }
            if (state.loadingThreads) {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else if (state.threads.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("没有找到会话", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 88.dp)) {
                    items(state.threads, key = ThreadSummary::id) { thread ->
                        ThreadRow(thread, onClick = { viewModel.openThread(thread) })
                        HorizontalDivider(Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ThreadRow(thread: ThreadSummary, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(thread.title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(formatTime(thread.updatedAt), style = MaterialTheme.typography.labelSmall)
            }
            if (thread.preview.isNotBlank() && thread.preview != thread.title) {
                Text(
                    thread.preview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                )
            }
            Text(thread.cwd, style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(state: TailCodexState, viewModel: TailCodexViewModel) {
    val thread = requireNotNull(state.activeThread)
    val listState = rememberLazyListState()
    var composer by remember(thread.id) { mutableStateOf("") }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.text?.length) {
        if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(thread.title, maxLines = 1, style = MaterialTheme.typography.titleMedium)
                        Text(
                            if (state.activeTurnId == null) "空闲" else "正在运行",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = viewModel::closeThread) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
        bottomBar = {
            Composer(
                value = composer,
                running = state.activeTurnId != null,
                onValueChange = { composer = it },
                onSend = {
                    if (composer.isNotBlank()) {
                        viewModel.sendMessage(composer)
                        composer = ""
                    }
                },
                onStop = viewModel::interrupt,
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            state.error?.let { ErrorStrip(it, viewModel::reconnect) }
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(state.messages, key = ChatEntry::id) { MessageBubble(it) }
            }
        }
    }
}

@Composable
private fun MessageBubble(message: ChatEntry) {
    val isUser = message.role == MessageRole.USER
    val isEvent = message.role == MessageRole.EVENT
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(if (isEvent) 1f else 0.9f)
                .background(
                    when {
                        isUser -> MaterialTheme.colorScheme.primaryContainer
                        isEvent -> MaterialTheme.colorScheme.surfaceVariant
                        else -> MaterialTheme.colorScheme.surface
                    },
                    RoundedCornerShape(16.dp),
                )
                .padding(14.dp),
        ) {
            message.detail?.let {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(5.dp))
            }
            Text(
                message.text,
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (isEvent) FontFamily.Monospace else FontFamily.Default,
            )
        }
    }
}

@Composable
private fun Composer(
    value: String,
    running: Boolean,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).imePadding().padding(10.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.weight(1f),
            placeholder = { Text(if (running) "追加指令…" else "发送指令…") },
            maxLines = 5,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { onSend() }),
        )
        if (running) {
            FilledIconButton(onClick = onStop) { Icon(Icons.Default.Stop, contentDescription = "停止") }
        } else {
            FilledIconButton(onClick = onSend, enabled = value.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送")
            }
        }
    }
}

@Composable
private fun ErrorStrip(message: String, onRetry: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.errorContainer).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
        OutlinedButton(onClick = onRetry) { Text("重试") }
    }
}

@Composable
private fun ConnectionDot(status: ConnectionStatus) {
    val color = when (status) {
        ConnectionStatus.CONNECTED -> Color(0xFF27A269)
        ConnectionStatus.CONNECTING, ConnectionStatus.RECONNECTING -> Color(0xFFE5A50A)
        ConnectionStatus.DISCONNECTED -> Color(0xFFC01C28)
    }
    Box(Modifier.size(10.dp).background(color, CircleShape))
}

private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm")

private fun formatTime(epochSeconds: Long): String = runCatching {
    Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).format(dateFormatter)
}.getOrDefault("")
