package org.example.winsignin

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 账号管理：勾选启用、看登录方式、添加（账密/网页）、删除，右上角批量登录。
 *
 * 提示用应用内 Snackbar 而不是系统 Toast——这台 ColorOS 会把系统 Toast 拦掉。
 */
@Composable
fun AccountsScreen(
    accounts: List<Account>,
    rememberMe: Boolean,
    onToggle: (String, Boolean) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
    onUpdateAccounts: (List<Account>) -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("winsignin", Context.MODE_PRIVATE) }
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var pendingDelete by remember { mutableStateOf<Account?>(null) }
    var batchRunning by remember { mutableStateOf(false) }
    var errors by remember { mutableStateOf<List<String>?>(null) }

    // 批量登录：所有"启用 + 账密登录 + 有账密"的账号，重新走一遍自动登录（协程并发）
    fun batchLogin() {
        val targets = accounts.filter {
            it.enabled && it.passwordLogin && it.username.isNotBlank() && it.password.isNotBlank()
        }
        if (targets.isEmpty()) {
            scope.launch { snackbarHostState.showSnackbar("没有可批量登录的账号") }
            return
        }
        batchRunning = true
        scope.launch {
            val res = reloginAccounts(prefs, accounts, targets, rememberMe)
            onUpdateAccounts(res.accounts)
            batchRunning = false
            snackbarHostState.showSnackbar("成功登录 ${res.okCount} 个账号")
            if (res.errors.isNotEmpty()) errors = res.errors
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            ScreenTopBar(title = "账号管理") {
                TextButton(onClick = { batchLogin() }, enabled = !batchRunning) {
                    Text(if (batchRunning) "登录中…" else "批量登录")
                }
            }

            if (accounts.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "还没有账号，点下面「添加账号」去登录",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                val listState = rememberLazyListState()
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 88.dp),
                    ) {
                    items(accounts, key = { it.id }) { account ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .alpha(if (account.enabled) 1f else 0.45f),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceContainer,
                            ),
                        ) {
                            ListItem(
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                leadingContent = {
                                    Checkbox(
                                        checked = account.enabled,
                                        onCheckedChange = { onToggle(account.id, it) },
                                    )
                                },
                                headlineContent = {
                                    Text(
                                        account.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                },
                                 supportingContent = {
                                     val detail = buildString {
                                         if (account.username.isNotBlank()) {
                                             append("认证码：${account.username}\n")
                                         }
                                         append(account.loginMethodLabel)
                                     }
                                     Text(detail, style = MaterialTheme.typography.bodySmall)
                                 },
                                trailingContent = {
                                    IconButton(onClick = { pendingDelete = account }) {
                                        Icon(
                                            imageVector = Icons.Filled.Delete,
                                            contentDescription = "删除",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                },
                            )
                        }
                    }
                }
                AppVerticalScrollbar(
                    state = listState,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxHeight()
                        .padding(vertical = 8.dp),
                )
            }
        }
        }

        ExtendedFloatingActionButton(
            onClick = onAdd,
            icon = { Icon(Icons.Filled.Add, contentDescription = null) },
            text = { Text("添加账号") },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
        )

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    pendingDelete?.let { account ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除账号") },
            text = { Text("确定删除「${account.displayName}」吗？") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDelete(account.id)
                        pendingDelete = null
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    errors?.let { lines ->
        AlertDialog(
            onDismissRequest = { errors = null },
            title = { Text("批量登录失败") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    lines.forEach { Text(it) }
                }
            },
            confirmButton = {
                TextButton(onClick = { errors = null }) { Text("知道了") }
            },
        )
    }
}
