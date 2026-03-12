package com.ravewave.app.render

import com.ravewave.app.scene.PostEffect
import com.ravewave.app.scene.SceneState
import com.ravewave.app.scene.VisualLayer
import kotlin.math.max
import kotlin.math.min

object RenderBudget {
    enum class Profile {
        EXPO,
        NATIVE
    }

    private val coreLayers = setOf(
        VisualLayer.SPECTRUM,
        VisualLayer.WAVEFORM,
        VisualLayer.CIRCULAR,
        VisualLayer.RINGS
    )

    private fun layerCost(layer: VisualLayer): Float = when (layer) {
        VisualLayer.SPECTRUM -> 1.0f
        VisualLayer.WAVEFORM -> 1.0f
        VisualLayer.CIRCULAR -> 1.1f
        VisualLayer.VU -> 1.0f
        VisualLayer.GRAVITY -> 1.15f
        VisualLayer.DNA -> 1.15f
        VisualLayer.LIQUID -> 1.2f
        VisualLayer.CITY -> 1.2f
        VisualLayer.NEURAL -> 1.3f
        VisualLayer.ORBIT -> 1.1f
        VisualLayer.BLACK_HOLE -> 1.35f
        VisualLayer.CRYSTAL -> 1.25f
        VisualLayer.FIRE -> 1.2f
        VisualLayer.MAGNETIC -> 1.25f
        VisualLayer.MANDALA -> 1.3f
        VisualLayer.LOTUS -> 1.2f
        VisualLayer.METATRON -> 1.1f
        VisualLayer.YANTRA -> 1.1f
        VisualLayer.SPIRAL -> 1.35f
        VisualLayer.LASER -> 1.1f
        VisualLayer.RINGS -> 1.0f
        VisualLayer.SWARM -> 1.8f
        VisualLayer.BOUNCERS -> 1.55f
        VisualLayer.COMETS -> 1.7f
        VisualLayer.SHARDS -> 1.65f
        VisualLayer.ORBITERS -> 1.7f
        VisualLayer.RIBBONS -> 1.45f
        VisualLayer.RAIN -> 1.45f
        VisualLayer.BUBBLES -> 1.45f
    }

    private fun effectLoad(effect: PostEffect): Float = when (effect) {
        PostEffect.CAMERA_ZOOM -> 0.35f
        PostEffect.SCREEN_SHAKE -> 0.6f
        PostEffect.CAMERA_ROTATE -> 0.45f
        PostEffect.SCREEN_DRIFT -> 0.55f
        PostEffect.TILE -> 0.85f
        PostEffect.PIXELATE -> 0.2f
        PostEffect.RGB_SPLIT -> 0.75f
        PostEffect.KALEIDOSCOPE -> 1.0f
        PostEffect.BLOOM_GLOW -> 0.7f
        PostEffect.PRISM_SHIFT -> 0.35f
        PostEffect.SOLAR_BURST -> 0.55f
        PostEffect.SACRED_GRID -> 0.45f
        PostEffect.NITROUS -> 1.45f
        PostEffect.CYCLONE -> 1.0f
        PostEffect.NEON -> 1.0f
        PostEffect.X_RAY -> 1.15f
        PostEffect.HYPERSPACE -> 1.3f
        PostEffect.BOUNCE -> 0.55f
        else -> 0.25f
    }

    private fun budgetForScene(scene: SceneState, profile: Profile): Float {
        val baseBudget = if (profile == Profile.EXPO) 15.5f else 18.0f
        val minBudget = if (profile == Profile.EXPO) 6.5f else 8.0f
        val maxBudget = if (profile == Profile.EXPO) 16.0f else 18.5f

        var budget = baseBudget
        budget -= scene.fxIntensity * if (profile == Profile.EXPO) 3.4f else 2.6f
        budget -= scene.speed * if (profile == Profile.EXPO) 1.1f else 0.8f

        var effectCount = 0
        for (effect in scene.enabledEffects) {
            effectCount += 1
            budget -= effectLoad(effect)
        }
        if (effectCount > 3) {
            budget -= (effectCount - 3) * 0.25f
        }

        return max(minBudget, min(maxBudget, budget))
    }

    fun resolveRenderableLayers(
        scene: SceneState,
        profile: Profile = Profile.NATIVE
    ): Set<VisualLayer> {
        val active = scene.enabledLayers.toList()
        if (active.size <= 1) return active.toSet()

        val selected = active.toMutableList()
        var totalCost = selected.sumOf { layerCost(it).toDouble() }.toFloat()
        val budget = budgetForScene(scene, profile)
        if (totalCost <= budget) return LinkedHashSet(selected)

        while (selected.size > 2 && totalCost > budget) {
            val categoryCounts = selected.groupingBy { it.category }.eachCount()
            var removeIndex = -1
            var bestScore = Float.NEGATIVE_INFINITY

            for (i in selected.indices) {
                val layer = selected[i]
                val age = if (selected.size > 1) 1f - i.toFloat() / (selected.size - 1).toFloat() else 0f
                var score = layerCost(layer) * 1.35f + age * 0.85f

                if (coreLayers.contains(layer)) score -= 1.2f
                if ((categoryCounts[layer.category] ?: 0) <= 1) score -= 0.55f

                if (score > bestScore) {
                    bestScore = score
                    removeIndex = i
                }
            }

            if (removeIndex < 0) break
            totalCost -= layerCost(selected[removeIndex])
            selected.removeAt(removeIndex)
        }

        return LinkedHashSet(selected)
    }
}
