package com.mt.webnest.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.mt.webnest.R
import com.mt.webnest.data.AppDatabase
import com.mt.webnest.data.WebApp
import com.mt.webnest.notification.WebAppNotifications
import com.mt.webnest.shortcut.WebAppShortcutManager
import com.mt.webnest.ui.theme.WebNestTheme
import com.mt.webnest.web.*
import kotlinx.coroutines.*

class AddWebAppActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val sharedText =
            if (intent.action == Intent.ACTION_SEND)
                intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
            else ""
        val sharedUrl = WebUrls.fromSharedText(sharedText)
        setContent {
            WebNestTheme {
                Editor(intent.getLongExtra("edit_id", 0), sharedUrl, sharedText.isNotBlank())
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    private fun Editor(editId: Long, sharedUrl: String?, receivedShare: Boolean) {
        var original by remember { mutableStateOf<WebApp?>(null) }
        var initialized by rememberSaveable { mutableStateOf(false) }
        var loading by remember { mutableStateOf(editId != 0L) }
        var url by rememberSaveable { mutableStateOf(sharedUrl.orEmpty()) }
        var name by rememberSaveable { mutableStateOf("") }
        var icon by rememberSaveable { mutableStateOf<ByteArray?>(null) }
        var themeColor by rememberSaveable { mutableStateOf<Int?>(null) }
        var fetching by remember { mutableStateOf(false) }
        var saving by remember { mutableStateOf(false) }
        var pin by rememberSaveable { mutableStateOf(editId == 0L) }
        var desktop by rememberSaveable { mutableStateOf(false) }
        var thirdParty by rememberSaveable { mutableStateOf(false) }
        var userAgent by rememberSaveable { mutableStateOf("") }
        var processingIcon by remember { mutableStateOf(false) }
        var iconMenu by remember { mutableStateOf(false) }
        var clearing by remember { mutableStateOf(false) }
        var clearDialog by remember { mutableStateOf(false) }
        fun notify(text: String) {
            Toast.makeText(this@AddWebAppActivity, text, Toast.LENGTH_SHORT).show()
        }
        LaunchedEffect(receivedShare, sharedUrl) {
            if (receivedShare && sharedUrl == null) notify("No valid URL found")
        }
        var urlError by remember { mutableStateOf(false) }
        var fetchJob by remember { mutableStateOf<Job?>(null) }
        var fetchGeneration by remember { mutableIntStateOf(0) }
        var lastFetchUrl by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()
        val density = LocalDensity.current
        var nameFieldHeight by remember { mutableIntStateOf(0) }
        val iconSize =
            with(density) {
                if (nameFieldHeight == 0) 57.dp
                else
                    (nameFieldHeight.toDp() -
                            MaterialTheme.typography.bodySmall.lineHeight.toDp() / 2)
                        .coerceAtLeast(48.dp) + 1.dp
            }
        fun fetch(normalizeInput: Boolean = true) {
            val target = WebUrls.normalize(url)
            if (target == null) {
                urlError = true
                notify("Enter an http or https URL")
                return
            }
            if (normalizeInput) url = target
            lastFetchUrl = target
            urlError = false
            fetchJob?.cancel()
            val generation = ++fetchGeneration
            fetching = true
            fetchJob = scope.launch {
                try {
                    val result =
                        SiteMetadata.fetch(
                            target,
                            android.webkit.WebSettings.getDefaultUserAgent(this@AddWebAppActivity),
                        )
                    if (generation == fetchGeneration && WebUrls.normalize(url) == target) {
                        if (name.isBlank()) name = result.name
                        if (icon == null) icon = result.icon
                        if (themeColor == null) themeColor = result.themeColor
                        if (!result.titleFound && result.icon == null)
                            notify("No site details found")
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    notify("Couldn't fetch site details")
                } finally {
                    if (generation == fetchGeneration) fetching = false
                }
            }
        }
        val cropper =
            rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
                result ->
                val uri = result.data?.data
                if (result.resultCode == RESULT_OK && uri != null) {
                    processingIcon = true
                    scope.launch {
                        val bytes =
                            withContext(Dispatchers.IO) {
                                runCatching {
                                    contentResolver.openInputStream(uri)?.use {
                                        SiteIcons.normalize(it.readBytes())
                                    }
                                }
                                    .getOrNull()
                            }
                        runCatching { contentResolver.delete(uri, null, null) }
                        if (bytes == null) notify("Couldn't load icon")
                        else {
                            fetchJob?.cancel()
                            ++fetchGeneration
                            fetching = false
                            icon = bytes
                        }
                        processingIcon = false
                    }
                }
            }
        val iconPicker =
            rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri != null)
                    cropper.launch(
                        Intent(this@AddWebAppActivity, IconCropActivity::class.java).setData(uri)
                    )
            }
        LaunchedEffect(editId) {
            if (editId != 0L) {
                try {
                    val record = AppDatabase.get(this@AddWebAppActivity).find(editId)
                    if (record == null) finish()
                    else {
                        original = record
                        if (!initialized) {
                            url = record.url
                            name = record.name
                            icon = record.icon
                            themeColor = record.themeColor
                            desktop = record.desktopMode
                            thirdParty = record.thirdPartyCookies
                            userAgent = record.userAgent
                        }
                    }
                } catch (_: Exception) {
                    notify("Couldn't load Web App")
                }
                loading = false
            }
            initialized = true
        }
        LaunchedEffect(url, initialized, saving) {
            if (editId == 0L && initialized && !saving) {
                val target = WebUrls.normalize(url)
                if (target != null && target != lastFetchUrl) {
                    delay(700)
                    if (target != lastFetchUrl) fetch(normalizeInput = false)
                }
            }
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(if (editId == 0L) "Add Web App" else "Edit Web App") },
                    navigationIcon = {
                        IconButton(onClick = { finish() }, enabled = !saving) {
                            Icon(painterResource(R.drawable.ic_back), "Back")
                        }
                    },
                )
            }
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                if (loading) CircularProgressIndicator(Modifier.align(Alignment.Center))
                else
                    Column(
                        Modifier.widthIn(max = 560.dp)
                            .fillMaxSize()
                            .consumeWindowInsets(padding)
                            .imePadding()
                    ) {
                        Column(
                            Modifier.weight(1f)
                                .fillMaxWidth()
                                .verticalScroll(rememberScrollState())
                                .padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                            ) {
                                Box(Modifier.align(Alignment.Bottom).offset(y = .5.dp)) {
                                    AppIcon(
                                        icon,
                                        size = iconSize,
                                        themeColor = themeColor,
                                        modifier =
                                            Modifier.semantics {
                                                    contentDescription = "Change icon"
                                                }
                                                .clickable(
                                                    enabled = !saving && !clearing,
                                                    role = Role.Button,
                                                ) {
                                                    iconMenu = true
                                                },
                                    )
                                    DropdownMenu(
                                        expanded = iconMenu,
                                        onDismissRequest = { iconMenu = false },
                                    ) {
                                        AppMenuItem(
                                            "Choose photo",
                                            onClick = {
                                                iconMenu = false
                                                iconPicker.launch("image/*")
                                            },
                                        )
                                        AppMenuItem(
                                            "Remove icon",
                                            enabled = icon != null,
                                            onClick = {
                                                iconMenu = false
                                                fetchJob?.cancel()
                                                ++fetchGeneration
                                                fetching = false
                                                icon = null
                                            },
                                        )
                                    }
                                }
                                OutlinedTextField(
                                    value = name,
                                    onValueChange = { name = it.take(100) },
                                    label = { Text("Name") },
                                    placeholder = {
                                        Text(WebUrls.normalize(url)?.let(WebUrls::host) ?: "Name")
                                    },
                                    singleLine = true,
                                    enabled = !saving && !clearing,
                                    modifier =
                                        Modifier.weight(1f).onSizeChanged {
                                            nameFieldHeight = it.height
                                        },
                                )
                            }
                            OutlinedTextField(
                                value = url,
                                onValueChange = {
                                    fetchJob?.cancel()
                                    ++fetchGeneration
                                    fetching = false
                                    url = it
                                    lastFetchUrl = null
                                    themeColor = null
                                    urlError = false
                                },
                                label = { Text("URL") },
                                placeholder = { Text("https://example.com") },
                                singleLine = true,
                                isError = urlError,
                                enabled = !saving && !clearing,
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                                trailingIcon = {
                                    IconButton(
                                        onClick = { fetch() },
                                        enabled =
                                            !fetching && !saving && !clearing && url.isNotBlank(),
                                    ) {
                                        if (fetching)
                                            CircularProgressIndicator(
                                                Modifier.size(24.dp).semantics {
                                                    contentDescription = "Fetching site details"
                                                },
                                                strokeWidth = 2.dp,
                                            )
                                        else
                                            Icon(
                                                painterResource(R.drawable.ic_refresh),
                                                "Fetch site details",
                                            )
                                    }
                                },
                            )
                            Column {
                                if (editId == 0L)
                                    SettingSwitch("Add to home screen", pin, !saving && !clearing) {
                                        pin = it
                                    }
                                SettingSwitch("Desktop mode", desktop, !saving && !clearing) {
                                    desktop = it
                                }
                                SettingSwitch(
                                    "Third-party cookies",
                                    thirdParty,
                                    !saving && !clearing,
                                ) {
                                    thirdParty = it
                                }
                            }
                            OutlinedTextField(
                                value = userAgent,
                                onValueChange = { userAgent = it.take(512) },
                                label = { Text("User agent") },
                                placeholder = { Text("Default") },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !saving && !clearing,
                                isError = !WebSettingsPolicy.validUserAgent(userAgent),
                                maxLines = 4,
                                keyboardOptions =
                                    KeyboardOptions(keyboardType = KeyboardType.Ascii),
                            )
                            if (editId != 0L)
                                Column {
                                    TextButton(
                                        enabled = !saving && !clearing,
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = {
                                            SitePermissions.clear(this@AddWebAppActivity, editId)

                                            notify("Site permissions reset")
                                        },
                                    ) {
                                        Text("Reset site permissions")
                                    }
                                    TextButton(
                                        enabled = !saving && !clearing,
                                        contentPadding = PaddingValues(horizontal = 0.dp),
                                        modifier = Modifier.heightIn(min = 48.dp),
                                        onClick = { clearDialog = true },
                                    ) {
                                        Text(
                                            if (clearing) "Clearing…" else "Clear site data",
                                            color = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Column(
                            Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                enabled =
                                    !saving &&
                                        !clearing &&
                                        !processingIcon &&
                                        (editId == 0L || original != null),
                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                onClick = {
                                    val target = WebUrls.normalize(url)
                                    if (target == null) {
                                        urlError = true
                                        notify("Enter an http or https URL")
                                    } else if (!WebSettingsPolicy.validUserAgent(userAgent)) {
                                        notify("Use a single-line ASCII user agent")
                                    } else {
                                        saving = true
                                        lifecycleScope.launch {
                                            try {
                                                if (editId == 0L) {
                                                    if (lastFetchUrl != target) fetch(normalizeInput = false)
                                                    fetchJob?.join()
                                                } else fetchJob?.cancel()
                                                val db = AppDatabase.get(this@AddWebAppActivity)
                                                val record =
                                                    db.save(
                                                        WebApp(
                                                            id = editId,
                                                            name =
                                                                name.trim().ifBlank {
                                                                    WebUrls.host(target)
                                                                },
                                                            url = target,
                                                            icon = icon,
                                                            createdAt =
                                                                original?.createdAt
                                                                    ?: System.currentTimeMillis(),
                                                            lastOpenedAt = original?.lastOpenedAt,
                                                            themeColor = themeColor,
                                                            desktopMode = desktop,
                                                            userAgent = userAgent.trim(),
                                                            thirdPartyCookies = thirdParty,
                                                        )
                                                    )
                                                WebAppShortcutManager.sync(
                                                    this@AddWebAppActivity,
                                                    db.all(),
                                                )
                                                if (
                                                    editId == 0L &&
                                                        pin &&
                                                        !WebAppShortcutManager.pin(
                                                            this@AddWebAppActivity,
                                                            record,
                                                        )
                                                )
                                                    Toast.makeText(
                                                            this@AddWebAppActivity,
                                                            "Launcher doesn't support pinning",
                                                            Toast.LENGTH_SHORT,
                                                        )
                                                        .show()
                                                finish()
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                notify("Couldn't save")
                                                saving = false
                                            }
                                        }
                                    }
                                },
                            ) {
                                Text(if (saving) "Saving…" else "Save")
                            }
                        }
                    }
            }
        }
        if (clearDialog) {
            val target = original?.url.orEmpty()
            val supported = remember { SiteData.supported() }
            AppDialog(
                "Clear site data?",
                onDismiss = { clearDialog = false },
                actions = {
                    DialogAction("Cancel", onClick = { clearDialog = false })
                    if (supported)
                        DialogAction(
                            "Clear",
                            destructive = true,
                            onClick = {
                                clearDialog = false
                                clearing = true
                                scope.launch {
                                    try {
                                        val apps = AppDatabase.get(this@AddWebAppActivity).all()
                                        SiteData.clear(target) { site ->
                                            apps
                                                .filter { record ->
                                                    val host = java.net.URI(record.url).host
                                                    host.equals(site, ignoreCase = true) ||
                                                        host.endsWith(".$site", ignoreCase = true)
                                                }
                                                .forEach {
                                                    WebAppNotifications.close(
                                                        this@AddWebAppActivity,
                                                        it.id,
                                                    )
                                                }
                                        }

                                        notify("Site data cleared")
                                    } catch (_: Exception) {
                                        notify("Couldn't clear site data")
                                    } finally {
                                        clearing = false
                                    }
                                }
                            },
                        )
                },
            ) {
                Text(
                    if (supported)
                        "Remove cookies and stored data for the domain of ${WebUrls.host(target)}, including all its subdomains. Web Apps on that domain will be signed out."
                    else "Update Android System WebView to clear site data.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SettingSwitch(
    label: String,
    value: Boolean,
    enabled: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(
                value = value,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onChange,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        Switch(checked = value, onCheckedChange = null, enabled = enabled)
    }
}
