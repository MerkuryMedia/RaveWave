package com.ravewave.app.scene

import com.ravewave.app.analyzer.AnalyzerMetrics
import kotlin.math.roundToInt
import kotlin.random.Random

object SceneRandomizer {
    data class EvolvePulseState(
        var lastMutationAt: Long = 0L,
        var phraseMomentum: Float = 0f
    )

    private fun randomFloat(min: Float, max: Float): Float = min + Random.nextFloat() * (max - min)
    private fun randomInt(min: Int, max: Int): Int = Random.nextInt(from = min, until = max + 1)
    private fun clamp(value: Float, min: Float, max: Float): Float = value.coerceIn(min, max)

    private fun <T> pickSubset(items: List<T>, min: Int, max: Int): Set<T> {
        val count = randomInt(min.coerceAtMost(items.size), max.coerceAtMost(items.size))
        return items.shuffled().take(count).toSet()
    }

    fun randomizeScene(scene: SceneState): SceneState {
        return scene.copy(
            enabledLayers = pickSubset(VisualLayer.entries, 1, VisualLayer.entries.size),
            enabledEffects = pickSubset(PostEffect.entries, 0, (PostEffect.entries.size - 2).coerceAtLeast(0)),
            colorMode = ColorMode.entries[randomInt(0, ColorMode.entries.size - 1)],
            fxIntensity = (randomFloat(0.25f, 1f) * 100f).roundToInt() / 100f,
            speed = (randomFloat(0.2f, 1f) * 100f).roundToInt() / 100f,
            tileCount = randomInt(2, 6),
            symmetrySegments = randomInt(4, 12)
        )
    }

    private fun toggleLayer(layers: MutableSet<VisualLayer>, addBias: Float) {
        val shouldAdd = layers.size <= 2 || (layers.size < 12 && Random.nextFloat() < addBias)
        if (shouldAdd) {
            VisualLayer.entries.shuffled().firstOrNull { !layers.contains(it) }?.let(layers::add)
            return
        }
        if (layers.size > 1) {
            layers.remove(layers.shuffled().first())
        }
    }

    private fun toggleEffect(effects: MutableSet<PostEffect>, addBias: Float) {
        val shouldAdd = effects.size < 7 && Random.nextFloat() < addBias
        if (shouldAdd) {
            PostEffect.entries.shuffled().firstOrNull { !effects.contains(it) }?.let(effects::add)
            return
        }
        if (effects.isNotEmpty()) {
            effects.remove(effects.shuffled().first())
        }
    }

    fun evolveSceneWithAudio(
        scene: SceneState,
        metrics: AnalyzerMetrics,
        pulseState: EvolvePulseState
    ): SceneState {
        val now = System.currentTimeMillis()
        val accent = metrics.beatPulse * 0.55f + metrics.onsetPulse * 0.35f + metrics.attackMod * 0.1f
        pulseState.phraseMomentum = clamp(
            pulseState.phraseMomentum * 0.82f + metrics.energy * 0.12f + metrics.beatPulse * 0.22f + metrics.onsetPulse * 0.18f,
            0f,
            1.8f
        )

        val minInterval = 220f + (1f - accent) * 480f
        if (now - pulseState.lastMutationAt < minInterval.toLong()) {
            return scene
        }

        val mutationChance = accent * 0.9f + metrics.energy * 0.18f
        if (Random.nextFloat() > mutationChance) {
            return scene
        }

        pulseState.lastMutationAt = now

        val nextLayers = scene.enabledLayers.toMutableSet()
        val nextEffects = scene.enabledEffects.toMutableSet()
        var nextColorMode = scene.colorMode
        var nextFxIntensity = scene.fxIntensity
        var nextSpeed = scene.speed
        var nextTileCount = scene.tileCount
        var nextSymmetry = scene.symmetrySegments

        val phraseHit = pulseState.phraseMomentum > 1.15f && metrics.beatPulse > 0.58f
        val mutationCount = when {
            phraseHit -> randomInt(2, 4)
            accent > 0.72f -> randomInt(1, 3)
            else -> 1
        }

        repeat(mutationCount) {
            when (Random.nextFloat()) {
                in 0f..0.52f -> toggleLayer(nextLayers, 0.74f - metrics.decayMod * 0.2f)
                in 0.52f..0.8f -> toggleEffect(nextEffects, 0.58f + metrics.highs * 0.16f)
                in 0.8f..0.9f -> {
                    if (phraseHit || Random.nextFloat() < metrics.highs * 0.5f) {
                        nextColorMode = ColorMode.entries[randomInt(0, ColorMode.entries.size - 1)]
                    }
                }
                else -> {
                    nextFxIntensity = clamp(
                        nextFxIntensity + (metrics.attackMod - metrics.decayMod) * 0.08f + randomFloat(-0.04f, 0.04f),
                        0f,
                        1f
                    )
                    nextSpeed = clamp(
                        nextSpeed + metrics.beatPulse * 0.05f - metrics.decayMod * 0.03f + randomFloat(-0.03f, 0.03f),
                        0f,
                        1f
                    )
                    nextTileCount = (nextTileCount + if (phraseHit) randomInt(-1, 1) else 0).coerceIn(2, 6)
                    nextSymmetry = (nextSymmetry + if (metrics.highs > 0.62f && Random.nextFloat() < 0.4f) randomInt(-1, 1) else 0).coerceIn(4, 12)
                }
            }
        }

        if (phraseHit) {
            pulseState.phraseMomentum *= 0.58f
        }

        if (nextLayers.isEmpty()) {
            nextLayers.add(VisualLayer.entries[randomInt(0, VisualLayer.entries.size - 1)])
        }

        return scene.copy(
            enabledLayers = nextLayers,
            enabledEffects = nextEffects,
            colorMode = nextColorMode,
            fxIntensity = (nextFxIntensity * 100f).roundToInt() / 100f,
            speed = (nextSpeed * 100f).roundToInt() / 100f,
            tileCount = nextTileCount,
            symmetrySegments = nextSymmetry
        )
    }

    fun nextEvolveDelayMs(metrics: AnalyzerMetrics): Long {
        val accent = clamp(metrics.beatPulse * 0.6f + metrics.onsetPulse * 0.25f + metrics.energy * 0.15f, 0f, 1f)
        return (180 + (1f - accent) * 320f).roundToInt().toLong()
    }
}
