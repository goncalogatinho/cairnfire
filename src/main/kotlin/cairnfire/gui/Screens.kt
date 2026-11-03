package cairnfire.gui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.loadImageBitmap
import androidx.compose.ui.res.useResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cairnfire.Action
import cairnfire.Config
import cairnfire.GameConfig
import cairnfire.Kind

@Composable
fun App(state: AppState) {
    val banner = remember { useResource("assets/banner-ui.png", ::loadImageBitmap) }
    val cfg = state.config

    Box(Modifier.fillMaxSize().background(Px.Bg)) {
        Column(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 22.dp)) {
            Header(state, cfg, banner)
            Spacer(Modifier.height(18.dp))
            if (cfg == null) {
                Welcome(state)
            } else {
                CloudFolder(state, cfg)
                Spacer(Modifier.height(18.dp))
                Games(state, cfg, Modifier.weight(1f))
                Spacer(Modifier.height(14.dp))
                Activity(state)
            }
        }
        state.draft?.let { GameDialog(state, it) }
    }
}

@Composable
private fun Header(state: AppState, cfg: Config?, banner: androidx.compose.ui.graphics.ImageBitmap) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(
            banner,
            contentDescription = "Cairnfire",
            modifier = Modifier.height((banner.height / 2).dp).aspectRatio(banner.width / banner.height.toFloat()), // 4 px per art pixel = 2 dp
            contentScale = ContentScale.Fit,
            filterQuality = FilterQuality.None, // pixel art: never smooth
        )
        Spacer(Modifier.weight(1f))
        if (cfg != null) {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                PixelToggle(state.auto, "Automatic", state::enableAuto)
                PixelButton(
                    if (state.busy) "Syncing…" else "Sync all",
                    onClick = state::syncAll,
                    primary = true,
                    enabled = !state.busy && cfg.games.isNotEmpty(),
                )
            }
        }
    }
}

@Composable
private fun Welcome(state: AppState) {
    Column(
        Modifier.fillMaxWidth().panel(Px.BorderHi).padding(28.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PxText("Let's get started.", color = Px.TextHi, size = 16.sp, bold = true)
        PxText(
            "Choose your cloud folder (Google Drive, Dropbox, OneDrive…). That is where Cairnfire leaves the saves " +
                "for your other computer to pick up.",
            color = Px.Text,
        )
        Spacer(Modifier.height(6.dp))
        PixelButton("Choose folder…", onClick = { pickFolder("Cloud folder")?.let(state::setSyncRoot) }, primary = true)
    }
}

@Composable
private fun CloudFolder(state: AppState, cfg: Config) {
    Column {
        Label("Cloud folder")
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).panel().padding(horizontal = 12.dp, vertical = 9.dp)) {
                PxText(cfg.syncRoot, color = Px.TextHi, maxLines = 1)
            }
            Spacer(Modifier.width(10.dp))
            PixelButton("Change", onClick = { pickFolder("Cloud folder")?.let(state::setSyncRoot) })
        }
    }
}

@Composable
private fun Games(state: AppState, cfg: Config, modifier: Modifier) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Label("Games")
            Spacer(Modifier.weight(1f))
            PixelButton("+ Add", onClick = state::newGame)
        }
        Spacer(Modifier.height(8.dp))
        if (cfg.games.isEmpty()) {
            Column(Modifier.fillMaxWidth().panel().padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PxText("No games yet.", color = Px.TextHi, bold = true)
                PxText("Add the first one and tell Cairnfire where the saves live on each computer.", color = Px.Muted, size = 12.sp)
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(cfg.games, key = { it.name }) { game ->
                    GameRow(state, game, state.statuses[game.name] ?: GameStatus.Checking)
                }
            }
        }
    }
}

private fun GameStatus.text(): String = when (this) {
    GameStatus.Checking -> "checking…"
    GameStatus.Synced -> "in sync"
    is GameStatus.Pending -> if (actions.size == 1) "1 change" else "${actions.size} changes"
    is GameStatus.Skipped -> "skipped"
    is GameStatus.Failed -> "error"
}

private fun GameStatus.color(): Color = when (this) {
    GameStatus.Checking -> Px.BorderHi
    GameStatus.Synced -> Px.Muted
    is GameStatus.Pending -> if (actions.any { it.conflict }) Px.Red else Px.Orange
    is GameStatus.Skipped -> Px.BorderHi
    is GameStatus.Failed -> Px.Red
}

private fun Kind.short(): String = when (this) {
    Kind.PUSH -> "UPLOAD"
    Kind.PULL -> "DOWNLOAD"
    Kind.DELETE_REMOTE -> "DELETE IN CLOUD"
    Kind.DELETE_LOCAL -> "DELETE HERE"
}

@Composable
private fun GameRow(state: AppState, game: GameConfig, status: GameStatus) {
    val expanded = state.expanded == game.name
    var confirmRemove by remember(game.name) { mutableStateOf(false) }

    Column(Modifier.fillMaxWidth().panel(if (expanded) Px.BorderHi else Px.Border)) {
        Row(
            Modifier
                .fillMaxWidth()
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                    state.expanded = if (expanded) null else game.name
                    confirmRemove = false
                }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatusSquare(status.color())
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                PxText(game.name, color = Px.TextHi, bold = true, maxLines = 1)
                PxText(state.localPath(game) ?: "no folder on this computer", color = Px.Muted, size = 11.sp, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            PxText(status.text(), color = status.color(), size = 12.sp, maxLines = 1)
            Spacer(Modifier.width(14.dp))
            PixelButton(
                "Sync",
                onClick = { state.syncOne(game.name) },
                enabled = !state.busy && status !is GameStatus.Skipped,
            )
        }

        if (expanded) {
            Box(Modifier.fillMaxWidth().height(2.dp).background(Px.Border))
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                when (status) {
                    is GameStatus.Pending -> Changes(status.actions)
                    is GameStatus.Skipped -> PxText(status.reason, color = Px.Muted, size = 12.sp)
                    is GameStatus.Failed -> PxText(status.message, color = Px.Red, size = 12.sp)
                    GameStatus.Synced -> PxText("Identical on both sides.", color = Px.Muted, size = 12.sp)
                    GameStatus.Checking -> PxText("Checking…", color = Px.Muted, size = 12.sp)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelButton("Edit", onClick = { state.editGame(game) })
                    Spacer(Modifier.width(10.dp))
                    PixelButton(
                        if (confirmRemove) "Confirm removal" else "Remove",
                        onClick = { if (confirmRemove) state.removeGame(game.name) else confirmRemove = true },
                    )
                    Spacer(Modifier.width(12.dp))
                    PxText(
                        if (confirmRemove) "Only removes it from the list; never deletes saves." else "",
                        color = Px.Muted, size = 11.sp, maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun Changes(actions: List<Action>) {
    val shown = actions.take(8)
    for (a in shown) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PxText(a.kind.short(), Modifier.width(130.dp), color = if (a.conflict) Px.Red else Px.Orange, size = 10.sp, bold = true, spacing = 1.sp)
            PxText(a.path, Modifier.weight(1f), color = Px.Text, size = 12.sp, maxLines = 1)
            if (a.conflict) PxText("CONFLICT", color = Px.Red, size = 10.sp, bold = true, spacing = 1.sp)
        }
        a.note?.let { PxText(it, Modifier.padding(start = 130.dp), color = Px.Muted, size = 11.sp, maxLines = 1) }
    }
    if (actions.size > shown.size) PxText("… and ${actions.size - shown.size} more", color = Px.Muted, size = 11.sp)
}

@Composable
private fun Activity(state: AppState) {
    Column {
        Label("Activity")
        Spacer(Modifier.height(6.dp))
        Column(Modifier.fillMaxWidth().height(92.dp).panel().padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (state.log.isEmpty()) PxText("No activity yet.", color = Px.BorderHi, size = 11.sp)
            for (line in state.log.takeLast(4)) {
                PxText("${line.time}  ${line.text}", color = if (line.error) Px.Red else Px.Muted, size = 11.sp, maxLines = 1)
            }
        }
    }
}

@Composable
private fun GameDialog(state: AppState, d: GameDraft) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xCC0B0910))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(Modifier.width(500.dp).panel(Px.BorderHi).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Label(if (d.editing) "Edit game" else "New game")

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                PxText("Name (also the folder name in the cloud)", color = Px.Muted, size = 11.sp)
                PixelField(d.name, { state.draft = d.copy(name = it, error = null) }, Modifier.fillMaxWidth(), "Stardew Valley", enabled = !d.editing)
            }
            PathField("Saves folder on Windows", d.windows, "%APPDATA%/StardewValley/Saves") { state.draft = d.copy(windows = it, error = null) }
            PathField("Saves folder on Mac", d.mac, "~/.config/StardewValley/Saves") { state.draft = d.copy(mac = it, error = null) }
            PxText("Fill in only the one for the computer you are on; you can add the other later.", color = Px.Muted, size = 11.sp)

            d.error?.let { PxText(it, color = Px.Red, size = 12.sp) }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                PixelButton("Cancel", onClick = { state.draft = null })
                Spacer(Modifier.width(10.dp))
                PixelButton("Save", onClick = state::saveDraft, primary = true)
            }
        }
    }
}

@Composable
private fun PathField(label: String, value: String, placeholder: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        PxText(label, color = Px.Muted, size = 11.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            PixelField(value, onChange, Modifier.weight(1f), placeholder)
            Spacer(Modifier.width(10.dp))
            PixelButton("Choose…", onClick = { pickFolder(label)?.let(onChange) })
        }
    }
}
