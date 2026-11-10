/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// Adapted from android/nav3-recipes app/src/main/java/com/example/nav3recipes/navscenedecorator
// (ResponsiveNavigationSceneDecorator.kt and Modifiers.kt's cacheSize). Modified for Retriever: a rail only (no bottom
// bar), shown or not at draw time so a resize keeps the scene's key, and photo and video scenes left undecorated.
package app.retriever.nav

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateMeasurement
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.navigation3.scene.Scene
import androidx.navigation3.scene.SceneDecoratorStrategy
import androidx.navigation3.scene.SceneDecoratorStrategyScope
import androidx.navigation3.ui.LocalNavAnimatedContentScope

/**
 * Puts the navigation rail at the start of every scene that doesn't take the whole window. A photo or video scene isn't
 * decorated, so it covers the window, and the rail stays where it is in the scene underneath, as in Google Photos.
 * Between two decorated scenes the rail is one shared element, called from one scene at a time, so it never moves.
 */
class RailDecorator(
    private val shared: SharedTransitionScope,
    private val shown: () -> Boolean,
    private val rail: @Composable () -> Unit,
    private val fullWindow: (Scene<Route>) -> Boolean,
) : SceneDecoratorStrategy<Route> {
    override fun SceneDecoratorStrategyScope<Route>.decorateScene(scene: Scene<Route>): Scene<Route> =
        if (fullWindow(scene)) scene else RailScene(scene, shared, shown, rail)
}

@Composable
fun rememberRailDecorator(
    shared: SharedTransitionScope,
    shown: () -> Boolean,
    fullWindow: (Scene<Route>) -> Boolean,
    rail: @Composable () -> Unit,
): RailDecorator {
    val current by rememberUpdatedState(rail)
    val currentShown by rememberUpdatedState(shown)
    val movable = remember { movableContentOf { current() } }
    return remember(shared) { RailDecorator(shared, { currentShown() }, movable, fullWindow) }
}

private data class RailScene(
    private val scene: Scene<Route>,
    private val shared: SharedTransitionScope,
    private val shown: () -> Boolean,
    private val rail: @Composable () -> Unit,
) : Scene<Route> by scene {
    override val key = scene::class to scene.key

    override val content: @Composable () -> Unit = {
        val animated = LocalNavAnimatedContentScope.current
        // The scene being shown calls the rail; the one leaving keeps its place at the size it last had.
        val caller = animated.transition.targetState == EnterExitState.Visible
        with(shared) {
            Row(Modifier.fillMaxSize()) {
                if (shown()) {
                    Box(Modifier.cacheSize(!caller).sharedElement(rememberSharedContentState("rail"), animated)) {
                        if (caller) rail()
                    }
                }
                Box(Modifier.weight(1f)) { scene.content() }
            }
        }
    }
}

/** Measures normally and remembers the size, or keeps the remembered size when [useCachedSize]. */
private fun Modifier.cacheSize(useCachedSize: Boolean): Modifier = this then CacheSizeElement(useCachedSize)

private data class CacheSizeElement(val useCachedSize: Boolean) : ModifierNodeElement<CacheSizeNode>() {
    override fun create() = CacheSizeNode(useCachedSize)

    override fun update(node: CacheSizeNode) {
        node.useCachedSize = useCachedSize
    }
}

private class CacheSizeNode(useCachedSize: Boolean) :
    Modifier.Node(),
    LayoutModifierNode {
    var useCachedSize: Boolean = useCachedSize
        set(value) {
            if (field != value) {
                field = value
                invalidateMeasurement()
            }
        }
    private var cached: IntSize? = null

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        val size = cached?.takeIf { useCachedSize } ?: IntSize(placeable.width, placeable.height)
        cached = size
        return layout(size.width, size.height) { placeable.placeRelative(0, 0) }
    }
}
