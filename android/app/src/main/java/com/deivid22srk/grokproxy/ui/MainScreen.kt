package com.deivid22srk.grokproxy.ui

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.deivid22srk.grokproxy.Bridge
import com.deivid22srk.grokproxy.ProxyService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

/** Splash shown while libgrokproxy is being loaded + initialized. */
@Composable
fun LoadingSplash(error: String?) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (error == null) {
                CircularProgressIndicator()
                Spacer(Modifier.height(16.dp))
                Text("Carregando núcleo Go…", style = MaterialTheme.typography.bodyMedium)
            } else {
                Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(12.dp))
                Text(
                    "Falha ao carregar libgrokproxy.so",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior(rememberTopAppBarState())
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    var status by remember { mutableStateOf<Bridge.Status?>(null) }
    var busy by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf<Bridge.Usage?>(null) }

    fun snack(msg: String) {
        scope.launch { snackbarHostState.showSnackbar(msg) }
    }

    // Continuous refresh: poll nativeStatus() every 2.5s. This keeps the server
    // state, the device-login poll result and the account list in sync without
    // per-action bookkeeping.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val raw = withContext(Dispatchers.IO) { Bridge.nativeStatus() }
                status = Bridge.parseStatus(raw)
            } catch (t: Throwable) {
                snack("Erro: ${t.message}")
            }
            delay(2500)
        }
    }

    // Usage refresh: poll nativeGetUsage() every 15s (slower because it makes
    // 3 HTTP calls to the xAI API). Also refresh once immediately after any
    // server start/stop action so the card updates without waiting.
    LaunchedEffect(Unit) {
        while (true) {
            try {
                val raw = withContext(Dispatchers.IO) { Bridge.nativeGetUsage() }
                usage = Bridge.parseUsage(raw)
            } catch (_: Throwable) {}
            delay(15000)
        }
    }

    fun runAction(block: () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            val err = withContext(Dispatchers.IO) { block() }
            if (err != null) snack(err)
            // Refresh immediately after an action.
            try {
                val raw = withContext(Dispatchers.IO) { Bridge.nativeStatus() }
                status = Bridge.parseStatus(raw)
            } catch (_: Throwable) {}
            busy = false
        }
    }

    // ---- Foreground service wiring ------------------------------------

    // Set true while we wait for the user to answer the POST_NOTIFICATIONS
    // prompt; when the launcher returns we run the pending start.
    var pendingStart by remember { mutableStateOf(false) }

    fun doStartProxy() {
        runAction {
            val err = Bridge.errorMessage(Bridge.nativeStartServer("0.0.0.0:8787"))
            if (err == null) ProxyService.start(context)
            err
        }
    }

    val notifPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        if (pendingStart) doStartProxy()
        pendingStart = false
    }

    fun startProxy() {
        // On Android 13+ ask for POST_NOTIFICATIONS so the FGS notification is
        // visible. The FGS itself runs either way, but without the permission
        // the user can't see/tap the notification.
        val needsPerm = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if (needsPerm) {
            pendingStart = true
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            doStartProxy()
        }
    }

    fun stopProxy() {
        runAction {
            ProxyService.stop(context)                 // leave foreground + stop server
            Bridge.errorMessage(Bridge.nativeStopServer()) // immediate local stop
        }
    }

    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            LargeTopAppBar(
                title = {
                    Column {
                        Text("Grok Proxy Plus", fontWeight = FontWeight.SemiBold)
                        Text(
                            "Proxy local OpenAI/Anthropic — núcleo Go",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                scrollBehavior = scrollBehavior,
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->
        val s = status
        if (s == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            ServerCard(
                status = s,
                busy = busy,
                onStart = { startProxy() },
                onStop = { stopProxy() },
                onCopy = { txt, label -> copy(context, txt); snack("$label copiado") },
                onOpen = { url -> open(context, url) },
            )
            LoginCard(
                status = s,
                busy = busy,
                onStartLogin = { runAction { Bridge.errorMessage(Bridge.nativeStartLogin()) } },
                onCancelLogin = { runAction { Bridge.errorMessage(Bridge.nativeCancelLogin()) } },
                onOpenUrl = { url -> open(context, url) },
                onCopy = { txt, label -> copy(context, txt); snack("$label copiado") },
            )
            UsageCard(usage = usage)
            AccountCard(
                status = s,
                busy = busy,
                onSetActive = { id -> runAction { Bridge.errorMessage(Bridge.nativeSetActive(id)) } },
                onLogout = { id -> runAction { Bridge.errorMessage(Bridge.nativeLogout(id)) } },
            )
            InfoCard(status = s)
            Spacer(Modifier.height(24.dp))
        }
    }
}

// --------------------------------------------------------------------
// Server
// --------------------------------------------------------------------

@Composable
private fun ServerCard(
    status: Bridge.Status,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopy: (String, String) -> Unit,
    onOpen: (String) -> Unit,
) {
    val lanIp = remember { primaryIpAddress() }
    val lanUrl = if (lanIp != null && status.running) "http://$lanIp:8787/v1" else ""

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (status.running) Icons.Default.Cloud else Icons.Default.CloudOff,
                    contentDescription = null,
                    tint = if (status.running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Servidor", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (status.running) "Em execução" else "Parado",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (busy) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            if (status.running) {
                LabeledValue("Endereço (no aparelho)", status.baseUrl, onCopy = { onCopy(status.baseUrl, "Endereço") })
                if (lanUrl.isNotEmpty()) {
                    LabeledValue("Endereço (na rede)", lanUrl, onCopy = { onCopy(lanUrl, "Endereço LAN") })
                }
                if (status.apiKey.isNotBlank()) {
                    LabeledValue(
                        "Chave de API",
                        status.apiKey,
                        icon = Icons.Default.Key,
                        onCopy = { onCopy(status.apiKey, "Chave de API") },
                    )
                }
            } else {
                Text(
                    "O proxy está desligado. Ligue para expor os endpoints OpenAI/Anthropic em http://127.0.0.1:8787/v1.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (status.running) {
                    OutlinedButton(onClick = onStop, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Desligar")
                    }
                    if (status.baseUrl.isNotBlank()) {
                        FilledTonalButton(
                            onClick = { onOpen("http://127.0.0.1:8787/") },
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Testar")
                        }
                    }
                } else {
                    Button(onClick = onStart, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.PowerSettingsNew, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Ligar servidor")
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------
// Login
// --------------------------------------------------------------------

@Composable
private fun LoginCard(
    status: Bridge.Status,
    busy: Boolean,
    onStartLogin: () -> Unit,
    onCancelLogin: () -> Unit,
    onOpenUrl: (String) -> Unit,
    onCopy: (String, String) -> Unit,
) {
    val hasAccount = status.accounts.isNotEmpty()
    val pending = status.loginState == "pending"
    val success = status.loginState == "success"
    val error = status.loginState == "error"

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Login, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("Conta xAI", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        when {
                            success -> "Logado como ${status.loginEmail.ifBlank { status.activeEmail }}"
                            pending -> "Aguardando autorização…"
                            error -> "Falha no login"
                            hasAccount -> "Conta ativa: ${status.activeEmail.ifBlank { status.activeLabel }}"
                            else -> "Nenhuma conta conectada"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (pending) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            }

            // Pending: show the verification URL + user code prominently.
            AnimatedVisibility(visible = pending) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (status.loginUrl.isNotBlank()) {
                        Text(
                            "Abra esta URL no navegador e autorize o acesso:",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        LabeledValue(
                            "URL de verificação",
                            status.loginUrl,
                            icon = Icons.Default.Link,
                            onCopy = { onCopy(status.loginUrl, "URL") },
                        )
                        Button(
                            onClick = { onOpenUrl(status.loginUrl) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("Abrir no navegador")
                        }
                    }
                    if (status.loginCode.isNotBlank()) {
                        LabeledValue(
                            "Código do usuário",
                            status.loginCode,
                            onCopy = { onCopy(status.loginCode, "Código") },
                        )
                    }
                    TextButton(onClick = onCancelLogin, modifier = Modifier.fillMaxWidth()) {
                        Text("Cancelar login")
                    }
                }
            }

            AnimatedVisibility(visible = error && status.loginError.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.width(8.dp))
                    Text(status.loginError, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }

            // Idle / success: offer to start a new login.
            AnimatedVisibility(visible = !pending) {
                Button(onClick = onStartLogin, modifier = Modifier.fillMaxWidth(), enabled = !busy) {
                    Icon(Icons.Default.Login, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (hasAccount) "Adicionar outra conta" else "Fazer login")
                }
            }
        }
    }
}

// --------------------------------------------------------------------
// Accounts
// --------------------------------------------------------------------

@Composable
private fun AccountCard(
    status: Bridge.Status,
    busy: Boolean,
    onSetActive: (String) -> Unit,
    onLogout: (String) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Contas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            if (status.accounts.isEmpty()) {
                Text(
                    "Nenhuma conta. Use “Fazer login” acima para conectar sua conta xAI via fluxo de dispositivo.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                HorizontalDivider()
                status.accounts.forEach { acc ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            if (acc.active) Icons.Default.CheckCircle else Icons.Default.Person,
                            contentDescription = null,
                            tint = if (acc.active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                acc.label.ifBlank { acc.email.ifBlank { "Conta" } },
                                style = MaterialTheme.typography.bodyLarge,
                            )
                            if (acc.email.isNotBlank()) {
                                Text(
                                    acc.email,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (acc.expired) {
                                Text(
                                    "Token expirado — faça login de novo",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                        if (!acc.active) {
                            TextButton(onClick = { onSetActive(acc.id) }, enabled = !busy) { Text("Ativar") }
                        }
                        IconButton(onClick = { onLogout(acc.id) }, enabled = !busy) {
                            Icon(Icons.Default.Logout, contentDescription = "Sair")
                        }
                    }
                }
            }
        }
    }
}

// --------------------------------------------------------------------
// Usage / quota
// --------------------------------------------------------------------

@Composable
private fun UsageCard(usage: Bridge.Usage?) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Insights, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Uso da conta", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }

            if (usage == null) {
                Text(
                    "Carregando uso…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Card
            }

            if (usage.error != null && usage.rateLimits?.hasData != true) {
                Text(
                    usage.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Card
            }

            // Account-blocked warning (free tier out of credits)
            if (usage.accountBlocked) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Error, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Conta sem créditos — adicione em grok.com/?_s=usage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }

            // Rate-limits (live, from X-Ratelimit-* headers captured by the proxy)
            val rl = usage.rateLimits
            if (rl != null && rl.hasData) {
                Text(
                    "Cota atual (free tier)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Requests
                val reqProgress = if (rl.limitRequests > 0) {
                    (rl.remainingRequests.toFloat() / rl.limitRequests.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Speed, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Requests", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${rl.remainingRequests} / ${rl.limitRequests}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                }
                LinearProgressIndicator(
                    progress = { reqProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )

                // Tokens
                val tokProgress = if (rl.limitTokens > 0) {
                    (rl.remainingTokens.toFloat() / rl.limitTokens.toFloat()).coerceIn(0f, 1f)
                } else 0f
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Router, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(6.dp))
                    Text("Tokens", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${formatNum(rl.remainingTokens)} / ${formatNum(rl.limitTokens)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                    )
                }
                LinearProgressIndicator(
                    progress = { tokProgress },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                )

                if (rl.lastModel.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "Último modelo: ${rl.lastModel}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                Text(
                    "Cota disponível após a primeira requisição ao modelo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // Billing (paid-plan usage)
            val b = usage.billing
            if (b != null && (b.monthlyLimit > 0 || b.used > 0)) {
                HorizontalDivider()
                Text(
                    "Faturamento (plano pago)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (b.monthlyLimit > 0) {
                    val bp = (b.used.toFloat() / b.monthlyLimit.toFloat()).coerceIn(0f, 1f)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Usado no ciclo", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            "${formatNum(b.used)} / ${formatNum(b.monthlyLimit)}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                    LinearProgressIndicator(
                        progress = { bp },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                    )
                }
                if (b.periodStart.isNotBlank() && b.periodEnd.isNotBlank()) {
                    Text(
                        "Ciclo: ${formatDate(b.periodStart)} → ${formatDate(b.periodEnd)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** Formats a large number with thousands separators (pt-BR style). */
private fun formatNum(n: Long): String {
    return "%,d".format(n).replace(",", ".")
}

/** Formats an ISO date string (2026-07-01T00:00:00+00:00) to dd/MM/yyyy. */
private fun formatDate(iso: String): String {
    return try {
        val date = java.time.OffsetDateTime.parse(iso)
        "%02d/%02d/%d".format(date.dayOfMonth, date.monthValue, date.year)
    } catch (_: Throwable) {
        iso.take(10)
    }
}

// --------------------------------------------------------------------
// Info
// --------------------------------------------------------------------

@Composable
private fun InfoCard(status: Bridge.Status) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Router, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text("Detalhes técnicos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            HorizontalDivider()
            LabeledValue("Modelo padrão", status.model.ifBlank { "grok-4.5" })
            LabeledValue("Endpoints", "/v1/models, /v1/chat/completions, /v1/responses, /v1/messages")
            if (status.dataDir.isNotBlank()) LabeledValue("Diretório de dados", status.dataDir)
            Text(
                "O núcleo Go é compilado como libgrokproxy.so (buildmode=c-shared) e empacotado em jniLibs — não em assets, pois Android 14+ bloqueia execução de binários em assets. Um Foreground Service mantém o processo vivo quando o app está em background.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------------
// Small building blocks + helpers
// --------------------------------------------------------------------

@Composable
private fun LabeledValue(
    label: String,
    value: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onCopy: (() -> Unit)? = null,
) {
    Column {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(6.dp))
            }
            Text(
                value.ifBlank { "—" },
                style = MaterialTheme.typography.bodyLarge,
                fontFamily = if (value.matches(Regex("^https?://|^[0-9A-Za-z_:-]{8,}$"))) FontFamily.Monospace else null,
                modifier = Modifier.weight(1f),
            )
            if (onCopy != null && value.isNotBlank()) {
                IconButton(onClick = onCopy) {
                    Icon(Icons.Default.ContentCopy, contentDescription = "Copiar", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

private fun copy(context: Context, text: String) {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("grok", text))
}

private fun open(context: Context, url: String) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    } catch (_: Throwable) { /* no browser available */ }
}

/** Returns the first non-loopback IPv4 of the device, or null. */
private fun primaryIpAddress(): String? {
    return try {
        NetworkInterface.getNetworkInterfaces().toList().flatMap { it.inetAddresses.toList() }
            .firstOrNull { !it.isLoopbackAddress() && it.address.size == 4 }?.hostAddress
    } catch (_: Throwable) {
        null
    }
}
