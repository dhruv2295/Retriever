package app.retriever

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.retriever.core.Stage
import app.retriever.core.motion.MascotDirector
import app.retriever.core.motion.MascotMood
import app.retriever.motion.Mascot
import app.retriever.ui.RetrieverTheme

/** Every mascot animation in one place, with the switches that drive its transitions. Debug builds only. */
class MascotGalleryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = intent.getStringExtra("mood")?.let { MascotMood.valueOf(it) }
        setContent { RetrieverTheme { Surface { Gallery(start) } } }
    }
}

@Composable
private fun Gallery(start: MascotMood?) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Section("Reading", listOf(MascotMood.WALKING, MascotMood.RESTING), start)
        Section("Search", listOf(MascotMood.SEARCHING, MascotMood.NO_MATCH, MascotMood.NO_MATCH_QUIET), start)
        Section("Welcome (180 dp)", listOf(MascotMood.WELCOME), start, width = 180)
        Section("All caught up", listOf(MascotMood.CAUGHT_UP), start)
        Section("Stages", Stage.entries.map(MascotDirector::working) + MascotMood.RESTING, start)
        Section("Stage done", listOf(MascotMood.FOUND_IT), start)
    }
}

@Composable
private fun Section(title: String, moods: List<MascotMood>, start: MascotMood?, width: Int = 160) {
    var mood by remember { mutableStateOf(start?.takeIf { it in moods } ?: moods.first()) }
    var replays by remember { mutableIntStateOf(0) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        key(replays) { Mascot(mood, Modifier.width(width.dp)) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (moods.size > 1) moods.forEach { m -> FilterChip(mood == m, { mood = m }, { Text(m.name.lowercase()) }) }
            OutlinedButton({ replays++ }) { Text("Replay") }
        }
    }
}
