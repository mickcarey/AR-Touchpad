package au.com.michaelcarey.artouchpad.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import au.com.michaelcarey.artouchpad.R
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import au.com.michaelcarey.artouchpad.AppShortcut

@Composable
internal fun AppPicker(catalog: List<AppShortcut>?, pinned: Set<String>, onCancel: () -> Unit,
    onAdd: (Set<String>) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var selectedOnly by rememberSaveable { mutableStateOf(false) }
    var draft by rememberSaveable { mutableStateOf(arrayListOf<String>()) }
    val selected = remember(catalog, pinned, draft) {
        catalog.orEmpty().map { it.id }.intersect(draft.toSet()) - pinned
    }
    val matches = remember(catalog, query, selectedOnly, selected) {
        val term = query.trim()
        catalog.orEmpty().filter { app ->
            (!selectedOnly || app.id in selected) && (app.label.contains(term, ignoreCase = true) ||
                app.intent.component?.packageName?.contains(term, ignoreCase = true) == true)
        }
    }
    val grayscale = remember { ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) }) }
    val keyboard = LocalSoftwareKeyboardController.current
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.34f), RoundedCornerShape(28.dp))) {
        Box(Modifier.matchParentSize().pointerInput(onCancel) { detectTapGestures { onCancel() } })
        Surface(modifier = Modifier.padding(12.dp).fillMaxSize().semantics { paneTitle = "Add apps" },
            shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface) {
            Column {
                OutlinedTextField(value = query, onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp)
                        .testTag("app-search"), label = { Text("Search installed apps") }, singleLine = true,
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    trailingIcon = {
                        if (query.isNotEmpty()) TextButton(onClick = { query = "" }) { Text("Clear") }
                    })
                PanelTabs(listOf("All apps", "Selected"), if (selectedOnly) "Selected" else "All apps") {
                    selectedOnly = it == "Selected"
                }
                Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    if (matches.isEmpty()) {
                        Text(when {
                            catalog == null -> "Loading installed apps…"
                            query.isNotBlank() -> "No matching apps"
                            selectedOnly -> "No apps selected"
                            else -> "No installed apps"
                        }, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else key(query, selectedOnly) {
                        LazyVerticalGrid(columns = GridCells.Adaptive(96.dp), modifier = Modifier.fillMaxSize().testTag("app-results"),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(matches, key = { it.id }) { app ->
                                val alreadyPinned = app.id in pinned
                                val checked = alreadyPinned || app.id in selected
                                Column(Modifier.fillMaxWidth().toggleable(checked, enabled = !alreadyPinned, role = Role.Checkbox) {
                                    draft = ArrayList(if (it) draft + app.id else draft - app.id)
                                }.semantics { if (alreadyPinned) stateDescription = "Already pinned" }
                                    .alpha(if (alreadyPinned) 0.55f else 1f).padding(vertical = 6.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally) {
                                    Box(Modifier.size(56.dp)) {
                                        Image(app.icon.asImageBitmap(), null, Modifier.size(48.dp).align(Alignment.Center),
                                            colorFilter = if (alreadyPinned) grayscale else null)
                                        if (checked) {
                                            Icon(painterResource(R.drawable.ic_check), contentDescription = null,
                                                modifier = Modifier.align(Alignment.BottomEnd).size(20.dp)
                                                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, CircleShape).padding(2.dp),
                                                tint = if (alreadyPinned) MaterialTheme.colorScheme.onSurfaceVariant else Color(0xFF63D88B))
                                        }
                                    }
                                    Text(app.label, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                        style = MaterialTheme.typography.labelLarge)
                                }
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { keyboard?.hide(); onCancel() }) { Text("Cancel") }
                    Button(onClick = { keyboard?.hide(); onAdd(selected) }, enabled = selected.isNotEmpty()) {
                        Text(if (selected.size <= 1) "Add App" else "Add ${selected.size} Apps")
                    }
                }
            }
        }
    }
}
