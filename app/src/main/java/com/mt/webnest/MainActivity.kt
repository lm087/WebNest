package com.mt.webnest

import android.content.Intent
import android.content.pm.ShortcutManager
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.lifecycleScope
import com.mt.webnest.data.*
import com.mt.webnest.notification.WebAppNotifications
import com.mt.webnest.shortcut.WebAppShortcutManager
import com.mt.webnest.ui.*
import com.mt.webnest.ui.theme.WebNestTheme
import com.mt.webnest.web.SitePermissions
import com.mt.webnest.web.WebUrls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var apps by mutableStateOf<List<WebApp>>(emptyList())
    private var loading by mutableStateOf(true)
    private var error by mutableStateOf(false)
    private var working by mutableStateOf(false)
    private val snackbar = SnackbarHostState()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { WebNestTheme { ManagerScreen() } }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        lifecycleScope.launch {
            error = false
            try {
                apps = AppDatabase.get(this@MainActivity).all()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                error = true
            }
            if (!error) WebAppShortcutManager.refreshIconsIfNeeded(this@MainActivity, apps)
            loading = false
        }
    }

    private fun notify(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun delete(
        records: List<WebApp>,
        onFailure: () -> Unit = {},
        onDeleted: suspend () -> Unit = {},
    ) {
        if (records.isEmpty() || working) {
            onFailure()
            return
        }
        working = true
        lifecycleScope.launch {
            val db = AppDatabase.get(this@MainActivity)
            try {
                db.deleteAll(records.map { it.id })
                records.forEach {
                    WebAppNotifications.close(this@MainActivity, it.id)
                    WebAppShortcutManager.remove(this@MainActivity, it.id)
                }
                val remaining = db.all()
                onDeleted()
                apps = remaining
                WebAppShortcutManager.sync(this@MainActivity, apps)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                onFailure()
                notify("Couldn't delete")
                return@launch
            } finally {
                working = false
            }
            run {
                val label =
                    if (records.size == 1) records.single().name else "${records.size} Web Apps"
                var restored = false
                try {
                    if (
                        snackbar.showSnackbar(
                            "Deleted $label",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Short,
                        ) == SnackbarResult.ActionPerformed
                    ) {
                        db.restoreAll(records)
                        restored = true
                        apps = db.all()
                        WebAppShortcutManager.sync(this@MainActivity, apps)
                        runCatching {
                            getSystemService(ShortcutManager::class.java)
                                .enableShortcuts(records.map { it.id.toString() })
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    notify("Couldn't restore")
                } finally {
                    if (!restored)
                        records.forEach { SitePermissions.clear(this@MainActivity, it.id) }
                }
            }
        }
    }

    private suspend fun togglePinned(app: WebApp) {
        AppDatabase.get(this).setPinned(app.id, !app.pinned)
        apps = AppDatabase.get(this).all()
    }

    @OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
    @Composable
    private fun ManagerScreen() {
        var selectedIds by rememberSaveable { mutableStateOf(emptyList<Long>()) }
        var settingsMenu by remember { mutableStateOf(false) }
        var sortingMenu by remember { mutableStateOf(false) }
        val preferences = remember { getSharedPreferences("manager", MODE_PRIVATE) }
        var sort by remember {
            val field =
                SortField.entries.firstOrNull {
                    it.name == preferences.getString("sort_field", null)
                } ?: SortField.CREATED
            mutableStateOf(
                ManagerSort(
                    field,
                    preferences.getBoolean("sort_ascending", field.ascendingByDefault),
                )
            )
        }
        var customOrder by remember { mutableStateOf<List<Long>?>(null) }
        val ordered =
            remember(apps, sort, customOrder) {
                val ids = customOrder
                if (ids == null) sort.arrange(apps)
                else {
                    val indexed = apps.associateBy { it.id }
                    ids.mapNotNull(indexed::get) + apps.filter { it.id !in ids }
                }
            }
        val listState = rememberLazyListState()
        val reorder = remember(listState) { ManagerReorderState(listState) }
        val haptics = LocalHapticFeedback.current
        val edge = with(LocalDensity.current) { 56.dp.toPx() }
        val selecting = selectedIds.isNotEmpty()
        val scope = rememberCoroutineScope()
        val contentMaxWidth = 720.dp
        LaunchedEffect(apps, loading) {
            if (!loading) selectedIds = selectedIds.filter { id -> apps.any { it.id == id } }
        }
        fun chooseSort(next: ManagerSort) {
            if (sort == next) return
            sort = next
            preferences
                .edit()
                .putString("sort_field", next.field.name)
                .putBoolean("sort_ascending", next.ascending)
                .apply()
        }
        reorder.onMove = { id, target ->
            val from = ordered.indexOfFirst { it.id == id }
            val to = ordered.indexOfFirst { it.id == target }
            if (from >= 0 && to >= 0 && ordered[from].pinned == ordered[to].pinned) {
                customOrder =
                    ordered.map { it.id }.toMutableList().apply { add(to, removeAt(from)) }
                chooseSort(ManagerSort(SortField.CUSTOM, true))
            }
        }
        reorder.onFinish = {
            customOrder?.let { ids ->
                working = true
                scope.launch {
                    try {
                        AppDatabase.get(this@MainActivity).reorder(ids)
                        apps = AppDatabase.get(this@MainActivity).all()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        notify("Couldn't save order")
                    } finally {
                        customOrder = null
                        working = false
                    }
                }
            }
        }
        LaunchedEffect(reorder.draggingId) {
            if (reorder.draggingId != null)
                while (true) withFrameMillis { reorder.scrollStep(edge) }
        }
        fun select(id: Long) {
            selectedIds = if (id in selectedIds) selectedIds - id else selectedIds + id
        }
        BackHandler(enabled = selecting) { selectedIds = emptyList() }
        val selectionActive by rememberUpdatedState(selecting)
        val exitSelection by rememberUpdatedState({ selectedIds = emptyList() })
        Scaffold(
            modifier =
                Modifier.pointerInput(Unit) {
                    detectTapGestures(onTap = { if (selectionActive) exitSelection() })
                },
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    TopAppBar(
                        modifier = Modifier.widthIn(max = contentMaxWidth).fillMaxWidth(),
                        title = {
                            Text(
                                if (selecting) "${selectedIds.size} selected" else "WebNest",
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        navigationIcon = {
                            if (selecting)
                                IconButton(onClick = { selectedIds = emptyList() }) {
                                    Icon(painterResource(R.drawable.ic_back), "Exit selection")
                                }
                        },
                        actions = {
                            if (selecting) {
                                val allPinned =
                                    apps.filter { it.id in selectedIds }.all { it.pinned }
                                IconButton(
                                    enabled = !working,
                                    onClick = {
                                        val ids = selectedIds
                                        working = true
                                        scope.launch {
                                            try {
                                                AppDatabase.get(this@MainActivity)
                                                    .setPinned(ids, !allPinned)
                                                apps = AppDatabase.get(this@MainActivity).all()
                                                selectedIds = emptyList()
                                            } catch (e: CancellationException) {
                                                throw e
                                            } catch (_: Exception) {
                                                notify("Couldn't change pin")
                                            } finally {
                                                working = false
                                            }
                                        }
                                    },
                                ) {
                                    Icon(
                                        painterResource(
                                            if (allPinned) R.drawable.ic_unpin
                                            else R.drawable.ic_pin
                                        ),
                                        if (allPinned) "Unpin selected" else "Pin selected",
                                    )
                                }
                                IconButton(
                                    enabled = !working,
                                    onClick = {
                                        delete(apps.filter { it.id in selectedIds })
                                        selectedIds = emptyList()
                                    },
                                ) {
                                    Icon(painterResource(R.drawable.ic_delete), "Delete selected")
                                }
                            } else {
                                IconButton(
                                    onClick = {
                                        startActivity(
                                            Intent(this@MainActivity, AddWebAppActivity::class.java)
                                        )
                                    }
                                ) {
                                    Icon(painterResource(R.drawable.ic_add), "Add Web App")
                                }
                                Box {
                                    IconButton(onClick = { settingsMenu = true }) {
                                        Icon(painterResource(R.drawable.ic_more), "Settings")
                                    }
                                    DropdownMenu(
                                        expanded = settingsMenu,
                                        onDismissRequest = { settingsMenu = false },
                                    ) {
                                        AppMenuItem(
                                            "Sort by",
                                            onClick = {
                                                settingsMenu = false
                                                sortingMenu = true
                                            },
                                            trailingIcon = {
                                                Icon(
                                                    painterResource(R.drawable.ic_chevron),
                                                    null,
                                                    Modifier.size(18.dp),
                                                )
                                            },
                                        )
                                        AppMenuItem(
                                            "Notifications",
                                            onClick = {
                                                settingsMenu = false
                                                startActivity(
                                                    Intent(
                                                            Settings
                                                                .ACTION_APP_NOTIFICATION_SETTINGS
                                                        )
                                                        .putExtra(
                                                            Settings.EXTRA_APP_PACKAGE,
                                                            packageName,
                                                        )
                                                )
                                            },
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = sortingMenu,
                                        onDismissRequest = { sortingMenu = false },
                                    ) {
                                        SortField.entries.forEach { field ->
                                            AppMenuItem(
                                                field.label,
                                                onClick = {
                                                    chooseSort(sort.choose(field))
                                                    sortingMenu = false
                                                },
                                                trailingIcon =
                                                    if (sort.field == field)
                                                        ({
                                                            Icon(
                                                                painterResource(
                                                                    if (
                                                                        sort.field ==
                                                                            SortField.CUSTOM
                                                                    )
                                                                        R.drawable.ic_check
                                                                    else if (sort.ascending)
                                                                        R.drawable.ic_arrow_up
                                                                    else R.drawable.ic_arrow_down
                                                                ),
                                                                if (sort.field == SortField.CUSTOM)
                                                                    "Selected"
                                                                else if (sort.ascending)
                                                                    "Ascending order"
                                                                else "Descending order",
                                                                Modifier.size(18.dp),
                                                            )
                                                        })
                                                    else null,
                                            )
                                        }
                                    }
                                }
                            }
                        },
                    )
                }
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.TopCenter) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    error ->
                        Column(
                            Modifier.align(Alignment.Center).padding(32.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Text(
                                "Could not load your Web Apps",
                                style = MaterialTheme.typography.titleLarge,
                            )
                            TextButton(onClick = { refresh() }) { Text("Retry") }
                        }
                    apps.isEmpty() ->
                        Column(
                            Modifier.align(Alignment.Center)
                                .widthIn(max = 420.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Text("No Web Apps", style = MaterialTheme.typography.titleLarge)
                            Text(
                                "Add a URL or share one from your browser.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    else ->
                        LazyColumn(
                            Modifier.widthIn(max = contentMaxWidth)
                                .fillMaxSize()
                                .clipToBounds()
                                .background(MaterialTheme.colorScheme.surface),
                            state = listState,
                            contentPadding = PaddingValues(vertical = 8.dp),
                        ) {
                            items(ordered, key = { it.id }) { app ->
                                var menu by remember { mutableStateOf(false) }
                                val swipe =
                                    rememberSwipeToDismissBoxState(
                                        positionalThreshold = { it * .4f }
                                    )
                                val currentApp by rememberUpdatedState(app)
                                val onDismiss =
                                    remember(swipe) {
                                        { direction: SwipeToDismissBoxValue ->
                                            if (direction == SwipeToDismissBoxValue.EndToStart) {
                                                delete(
                                                    listOf(currentApp),
                                                    onFailure = { scope.launch { swipe.reset() } },
                                                    onDeleted = {
                                                        swipe.snapTo(SwipeToDismissBoxValue.Settled)
                                                    },
                                                )
                                            } else if (
                                                direction == SwipeToDismissBoxValue.StartToEnd
                                            )
                                                scope.launch {
                                                    try {
                                                        togglePinned(currentApp)
                                                    } catch (e: CancellationException) {
                                                        throw e
                                                    } catch (_: Exception) {
                                                        notify("Couldn't change pin")
                                                    } finally {
                                                        swipe.reset()
                                                    }
                                                }
                                        }
                                    }
                                val allowDrag by rememberUpdatedState(!working)
                                val beginDrag by
                                    rememberUpdatedState({
                                        if (!selecting) selectedIds = listOf(app.id)
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        reorder.start(app.id)
                                    })
                                SwipeToDismissBox(
                                    state = swipe,
                                    modifier =
                                        Modifier.pointerInput(app.id, reorder) {
                                                detectDragGesturesAfterLongPress(
                                                    onDragStart = { if (allowDrag) beginDrag() },
                                                    onDragEnd = reorder::finish,
                                                    onDragCancel = reorder::finish,
                                                    onDrag = { change, amount ->
                                                        if (reorder.draggingId == app.id) {
                                                            change.consume()
                                                            reorder.drag(amount.y)
                                                        }
                                                    },
                                                )
                                            }
                                            .zIndex(if (reorder.draggingId == app.id) 1f else 0f)
                                            .then(
                                                if (reorder.draggingId == app.id) Modifier
                                                else Modifier.animateItem()
                                            )
                                            .then(
                                                if (reorder.draggingId == app.id)
                                                    Modifier.graphicsLayer {
                                                        translationY = reorder.offset(app.id)
                                                    }
                                                else Modifier
                                            ),
                                    gesturesEnabled = !selecting && !working,
                                    onDismiss = onDismiss,
                                    backgroundContent = {
                                        val deleting =
                                            swipe.dismissDirection ==
                                                SwipeToDismissBoxValue.EndToStart
                                        Box(
                                            Modifier.fillMaxSize()
                                                .background(
                                                    if (deleting)
                                                        MaterialTheme.colorScheme.errorContainer
                                                    else
                                                        MaterialTheme.colorScheme.secondaryContainer
                                                )
                                                .padding(horizontal = 24.dp),
                                            contentAlignment =
                                                if (deleting) Alignment.CenterEnd
                                                else Alignment.CenterStart,
                                        ) {
                                            Icon(
                                                painterResource(
                                                    if (deleting) R.drawable.ic_delete
                                                    else R.drawable.ic_pin
                                                ),
                                                null,
                                                tint =
                                                    if (deleting)
                                                        MaterialTheme.colorScheme.onErrorContainer
                                                    else
                                                        MaterialTheme.colorScheme
                                                            .onSecondaryContainer,
                                            )
                                        }
                                    },
                                ) {
                                    key(selecting) {
                                        Box {
                                            ListItem(
                                                modifier =
                                                    Modifier.semantics {
                                                            selected = app.id in selectedIds
                                                            onLongClick("Select ${app.name}") {
                                                                if (!selecting)
                                                                    selectedIds = listOf(app.id)
                                                                true
                                                            }
                                                            if (!selecting)
                                                                customActions =
                                                                    listOf(
                                                                        CustomAccessibilityAction(
                                                                            if (app.pinned) "Unpin"
                                                                            else "Pin"
                                                                        ) {
                                                                            scope.launch {
                                                                                try {
                                                                                    togglePinned(
                                                                                        app
                                                                                    )
                                                                                } catch (
                                                                                    e:
                                                                                        CancellationException) {
                                                                                    throw e
                                                                                } catch (
                                                                                    _: Exception) {
                                                                                    notify(
                                                                                        "Couldn't change pin"
                                                                                    )
                                                                                }
                                                                            }
                                                                            true
                                                                        }
                                                                    )
                                                        }
                                                        .clickable(
                                                            enabled = !working,
                                                            onClickLabel =
                                                                if (selecting) "Select ${app.name}"
                                                                else "Open ${app.name}",
                                                            onClick = {
                                                                if (selecting) select(app.id)
                                                                else
                                                                    startActivity(
                                                                        WebAppActivity.intent(
                                                                            this@MainActivity,
                                                                            app.id,
                                                                        )
                                                                    )
                                                            },
                                                        ),
                                                headlineContent = {
                                                    Row(
                                                        verticalAlignment =
                                                            Alignment.CenterVertically,
                                                        horizontalArrangement =
                                                            Arrangement.spacedBy(8.dp),
                                                    ) {
                                                        Text(
                                                            app.name,
                                                            Modifier.weight(1f, fill = false),
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                        )
                                                        if (app.pinned)
                                                            Icon(
                                                                painterResource(R.drawable.ic_pin),
                                                                "Pinned",
                                                                Modifier.size(16.dp),
                                                                tint =
                                                                    MaterialTheme.colorScheme
                                                                        .onSurfaceVariant,
                                                            )
                                                    }
                                                },
                                                supportingContent = {
                                                    Text(
                                                        WebUrls.host(app.url),
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis,
                                                    )
                                                },
                                                leadingContent = {
                                                    if (selecting) Spacer(Modifier.size(48.dp))
                                                    else
                                                        AppIcon(
                                                            app.icon,
                                                            themeColor = app.themeColor,
                                                        )
                                                },
                                                trailingContent = {
                                                    if (selecting)
                                                        Box(Modifier.offset(x = 12.dp)) {
                                                            ReorderHandle(
                                                                app.id,
                                                                app.name,
                                                                reorder,
                                                                !working,
                                                            )
                                                        }
                                                    else
                                                        Box(Modifier.offset(x = 12.dp)) {
                                                            IconButton(onClick = { menu = true }) {
                                                                Icon(
                                                                    painterResource(
                                                                        R.drawable.ic_more
                                                                    ),
                                                                    "Options for ${app.name}",
                                                                )
                                                            }
                                                            DropdownMenu(
                                                                expanded = menu,
                                                                onDismissRequest = { menu = false },
                                                            ) {
                                                                AppMenuItem(
                                                                    "Edit",
                                                                    onClick = {
                                                                        menu = false
                                                                        startActivity(
                                                                            Intent(
                                                                                    this@MainActivity,
                                                                                    AddWebAppActivity::class
                                                                                        .java,
                                                                                )
                                                                                .putExtra(
                                                                                    "edit_id",
                                                                                    app.id,
                                                                                )
                                                                        )
                                                                    },
                                                                )
                                                                AppMenuItem(
                                                                    if (app.pinned) "Unpin"
                                                                    else "Pin",
                                                                    onClick = {
                                                                        menu = false
                                                                        scope.launch {
                                                                            try {
                                                                                togglePinned(app)
                                                                            } catch (
                                                                                e:
                                                                                    CancellationException) {
                                                                                throw e
                                                                            } catch (_: Exception) {
                                                                                notify(
                                                                                    "Couldn't change pin"
                                                                                )
                                                                            }
                                                                        }
                                                                    },
                                                                )
                                                                AppMenuItem(
                                                                    "Add to home screen",
                                                                    onClick = {
                                                                        menu = false
                                                                        if (
                                                                            !WebAppShortcutManager
                                                                                .pin(
                                                                                    this@MainActivity,
                                                                                    app,
                                                                                )
                                                                        )
                                                                            notify(
                                                                                "Launcher doesn't support pinning"
                                                                            )
                                                                    },
                                                                )
                                                                AppMenuItem(
                                                                    "Share",
                                                                    onClick = {
                                                                        menu = false
                                                                        startActivity(
                                                                            Intent.createChooser(
                                                                                Intent(
                                                                                        Intent
                                                                                            .ACTION_SEND
                                                                                    )
                                                                                    .setType(
                                                                                        "text/plain"
                                                                                    )
                                                                                    .putExtra(
                                                                                        Intent
                                                                                            .EXTRA_TEXT,
                                                                                        app.url,
                                                                                    ),
                                                                                null,
                                                                            )
                                                                        )
                                                                    },
                                                                )
                                                                AppMenuItem(
                                                                    "Delete",
                                                                    destructive = true,
                                                                    onClick = {
                                                                        menu = false
                                                                        delete(listOf(app))
                                                                    },
                                                                )
                                                            }
                                                        }
                                                },
                                            )
                                            if (selecting)
                                                SelectionMark(
                                                    app.id in selectedIds,
                                                    Modifier.matchParentSize(),
                                                )
                                        }
                                    }
                                }
                            }
                        }
                }
            }
        }
    }
}
