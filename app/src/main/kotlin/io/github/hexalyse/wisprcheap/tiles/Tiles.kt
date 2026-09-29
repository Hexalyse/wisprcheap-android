package io.github.hexalyse.wisprcheap.tiles

import android.graphics.drawable.Icon
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import io.github.hexalyse.wisprcheap.R
import io.github.hexalyse.wisprcheap.WisprApp
import io.github.hexalyse.wisprcheap.core.translate.TranslationPairs

/** Quick Settings tile: dictation bubble on/off (pause). */
class BubbleTileService : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        WisprApp.graph.settings.update { it.copy(bubble = it.bubble.copy(paused = !it.bubble.paused)) }
        render()
    }

    private fun render() {
        val tile = qsTile ?: return
        val paused = WisprApp.graph.settings.current.bubble.paused
        tile.state = if (paused) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
        tile.label = "Dictation"
        tile.subtitle = if (paused) "Paused" else "On"
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_mic)
        tile.updateTile()
    }
}

/** Quick Settings tile: cycles Off → pair 1 → pair 2 → … → Off. */
class TranslateTileService : TileService() {
    override fun onStartListening() = render()

    override fun onClick() {
        val g = WisprApp.graph
        val s = g.settings.current
        val pairs = TranslationPairs.build(s.translation.pairs).first
        if (pairs.isEmpty()) return
        val index = pairs.indexOfFirst { it.id == s.translation.active }
        val next = if (index + 1 < pairs.size) pairs[index + 1].id else null
        g.settings.update { it.copy(translation = it.translation.copy(active = next)) }
        render(next)
    }

    private fun render(active: String? = WisprApp.graph.settings.current.translation.active) {
        val tile = qsTile ?: return
        val s = WisprApp.graph.settings.current
        val pairs = TranslationPairs.build(s.translation.pairs).first
        val pair = pairs.firstOrNull { it.id == active }
        tile.state = when {
            pairs.isEmpty() -> Tile.STATE_UNAVAILABLE
            pair == null -> Tile.STATE_INACTIVE
            else -> Tile.STATE_ACTIVE
        }
        tile.label = "Translate"
        tile.subtitle = when {
            pairs.isEmpty() -> "No pair set up"
            pair == null -> "Off"
            else -> pair.label
        }
        tile.icon = Icon.createWithResource(this, R.drawable.ic_stat_mic)
        tile.updateTile()
    }
}
