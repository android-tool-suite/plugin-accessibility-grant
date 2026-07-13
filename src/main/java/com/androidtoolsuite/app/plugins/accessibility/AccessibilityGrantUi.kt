package com.androidtoolsuite.app.plugins.accessibility

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccessibilityNew
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.androidtoolsuite.app.ui.EmptyState
import com.androidtoolsuite.app.ui.Notice
import com.androidtoolsuite.app.ui.SectionHeader
import com.androidtoolsuite.app.ui.SuiteCard
import com.androidtoolsuite.app.ui.SuiteColors

@Composable
internal fun AccessibilityGrantScreen(plugin: AccessibilityGrantPlugin) {
    val ui by plugin.state
    val visibleServices = ui.services.filter { service ->
        (!ui.favoritesOnly || service.favorite) && (ui.query.isBlank() || service.matches(ui.query.trim()))
    }
    Column(Modifier.fillMaxSize()) {
        SectionHeader(
            title = "无障碍授权",
            subtitle = "通过 Shizuku 安全地管理设备上的无障碍服务",
            action = {
                IconButton(onClick = plugin::refreshState, enabled = !ui.loading) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "刷新")
                }
            },
        )
        if (ui.loading) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
        Spacer(Modifier.height(12.dp))
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Notice("无障碍服务可以读取屏幕内容并执行点击、滑动等操作，请只为完全信任的应用授权。", warning = true) }
            item { ConnectionCard(ui.connection) }
            item {
                SuiteCard {
                    Text("查找与自动化", style = MaterialTheme.typography.titleLarge)
                    OutlinedTextField(
                        value = ui.query,
                        onValueChange = plugin::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("搜索应用、服务或包名") },
                        singleLine = true,
                    )
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilterChip(
                            selected = ui.favoritesOnly,
                            onClick = { plugin.setFavoritesOnly(!ui.favoritesOnly) },
                            label = { Text("仅看收藏") },
                            leadingIcon = { Icon(Icons.Rounded.Favorite, contentDescription = null) },
                        )
                        Column(Modifier.weight(1f)) {
                            Text("自动启用收藏", style = MaterialTheme.typography.titleMedium)
                            Text("进入插件时自动授权", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = ui.autoGrant, onCheckedChange = plugin::setAutoGrant)
                    }
                }
            }
            item {
                val enabledCount = ui.services.count { it.enabled }
                SectionHeader("无障碍服务", if (ui.services.isEmpty()) "等待读取设备服务" else "${ui.services.size} 个服务 · $enabledCount 个已启用")
            }
            val message = ui.message
            if (message != null) {
                item { EmptyState(connectionTitle(ui.connection), message) }
            } else if (!ui.loading && visibleServices.isEmpty()) {
                item {
                    EmptyState(
                        if (ui.services.isEmpty()) "没有发现无障碍服务" else "没有匹配的服务",
                        if (ui.services.isEmpty()) "安装包含无障碍服务的应用后再刷新。" else "尝试清除搜索内容或关闭“仅看收藏”。",
                    )
                }
            }
            items(visibleServices, key = { it.component }) { service ->
                ServiceCard(service, plugin, ui.loading)
            }
        }
    }
}

@Composable
private fun ConnectionCard(connection: AccessibilityConnection) {
    val ready = connection == AccessibilityConnection.READY
    val title = connectionTitle(connection)
    val detail = when (connection) {
        AccessibilityConnection.DISCONNECTED -> "请先在设备上启动 Shizuku"
        AccessibilityConnection.UNAUTHORIZED -> "Shizuku 已连接，等待宿主获得授权"
        AccessibilityConnection.CONNECTING -> "授权已就绪，正在连接 UserService"
        AccessibilityConnection.READY -> "UserService 已连接，可以管理无障碍服务"
    }
    SuiteCard(containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                if (ready) Icons.Rounded.CheckCircle else Icons.Rounded.CloudOff,
                contentDescription = null,
                tint = if (ready) SuiteColors.Success else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge)
                Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ServiceCard(service: AccessibilityServiceItem, plugin: AccessibilityGrantPlugin, loading: Boolean) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.AccessibilityNew, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(service.appLabel, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(service.serviceLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                IconButton(onClick = { plugin.toggleFavorite(service.component) }) {
                    Icon(
                        if (service.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (service.favorite) "取消收藏" else "收藏",
                        tint = if (service.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(service.component, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = if (service.enabled) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
                ) {
                    Text(
                        if (service.enabled) "已启用" else "未启用",
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (service.enabled) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.weight(1f))
                if (service.enabled) {
                    OutlinedButton(onClick = { plugin.setServiceEnabled(service.component, false) }, enabled = !loading) { Text("停用") }
                } else {
                    Button(onClick = { plugin.setServiceEnabled(service.component, true) }, enabled = !loading) { Text("启用") }
                }
            }
        }
    }
}

@Composable
internal fun AccessibilityGrantHomeWidget(favoriteCount: Int, autoGrant: Boolean) {
    SuiteCard {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.AccessibilityNew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text("无障碍授权", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
        Text("$favoriteCount 个收藏", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(
            if (autoGrant) "自动启用 · 开启" else "自动启用 · 关闭",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun connectionTitle(connection: AccessibilityConnection): String = when (connection) {
    AccessibilityConnection.DISCONNECTED -> "Shizuku 未连接"
    AccessibilityConnection.UNAUTHORIZED -> "等待宿主授权"
    AccessibilityConnection.CONNECTING -> "正在连接服务"
    AccessibilityConnection.READY -> "运行正常"
}
