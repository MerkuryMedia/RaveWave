package com.ravewave.app.scene

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import android.util.Log

class SceneRepository(context: Context) {
    private val presetStore = PresetStore(context.applicationContext)

    private val _state = MutableStateFlow(SceneState())
    val state: StateFlow<SceneState> = _state.asStateFlow()

    fun setSourceMode(mode: SourceMode) {
        _state.update { it.copy(sourceMode = mode) }
    }

    fun setFullscreen(enabled: Boolean) {
        _state.update { it.copy(isFullscreen = enabled) }
    }

    fun setExternalVisualsEnabled(enabled: Boolean) {
        _state.update { it.copy(isExternalVisualsEnabled = enabled) }
    }

    fun setCastModeEnabled(enabled: Boolean) {
        _state.update { it.copy(isCastModeEnabled = enabled) }
    }

    fun setFxIntensity(value: Float) {
        _state.update { it.copy(fxIntensity = value.coerceIn(0f, 1f)) }
    }

    fun setSpeed(value: Float) {
        _state.update { it.copy(speed = value.coerceIn(0f, 1f)) }
    }

    fun setColorMode(mode: ColorMode) {
        _state.update { it.copy(colorMode = mode) }
    }

    fun setTileCount(value: Int) {
        _state.update { it.copy(tileCount = value.coerceIn(2, 6)) }
    }

    fun setSymmetrySegments(value: Int) {
        _state.update { it.copy(symmetrySegments = value.coerceIn(4, 12)) }
    }

    fun setLayerEnabled(layer: VisualLayer, enabled: Boolean) {
        _state.update { currentState ->
            val next = currentState.enabledLayers.toMutableSet()
            if (enabled) next.add(layer) else next.remove(layer)
            currentState.copy(enabledLayers = next)
        }
    }

    fun setEffectEnabled(effect: PostEffect, enabled: Boolean) {
        _state.update { currentState ->
            val next = currentState.enabledEffects.toMutableSet()
            if (enabled) next.add(effect) else next.remove(effect)
            currentState.copy(enabledEffects = next)
        }
    }

    fun updateScene(transform: (SceneState) -> SceneState) {
        _state.update(transform)
    }

    fun savePreset(name: String) {
        if (name.isBlank()) return
        val cleanName = name.trim()
        val currentState = _state.value
        presetStore.save(ScenePreset(cleanName, currentState.copy(activePresetName = cleanName)))
        _state.update { it.copy(activePresetName = cleanName) }
    }

    fun loadPreset(name: String): Boolean {
        val preset = presetStore.load(name.trim()) ?: return false
        _state.update { preset.state.copy(activePresetName = preset.name) }
        return true
    }

    fun listPresetNames(): List<String> = presetStore.listNames()
}

private class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("ravewave_presets", Context.MODE_PRIVATE)

    fun save(preset: ScenePreset) {
        try {
            prefs.edit().putString("preset_${preset.name}", encodeState(preset.state)).apply()
        } catch (e: Exception) {
            Log.e("PresetStore", "Failed to save preset: ${preset.name}", e)
        }
    }

    fun load(name: String): ScenePreset? {
        val raw = prefs.getString("preset_$name", null) ?: return null
        return try {
            ScenePreset(name, decodeState(raw))
        } catch (e: Exception) {
            Log.e("PresetStore", "Failed to decode preset: $name", e)
            null
        }
    }

    fun listNames(): List<String> {
        return prefs.all.keys
            .filter { it.startsWith("preset_") }
            .map { it.removePrefix("preset_") }
            .sorted()
    }

    private fun encodeState(state: SceneState): String {
        val root = JSONObject()
        root.put("layers", JSONArray(state.enabledLayers.map { it.name }))
        root.put("effects", JSONArray(state.enabledEffects.map { it.name }))
        root.put("colorMode", state.colorMode.name)
        root.put("fxIntensity", state.fxIntensity.toDouble())
        root.put("speed", state.speed.toDouble())
        root.put("tileCount", state.tileCount)
        root.put("symmetrySegments", state.symmetrySegments)
        root.put("sourceMode", state.sourceMode.name)
        root.put("isFullscreen", state.isFullscreen)
        root.put("isExternalVisualsEnabled", state.isExternalVisualsEnabled)
        root.put("isCastModeEnabled", state.isCastModeEnabled)
        root.put("activePresetName", state.activePresetName)
        return root.toString()
    }

    private fun decodeState(raw: String): SceneState {
        val root = JSONObject(raw)
        val layers = mutableSetOf<VisualLayer>()
        val effects = mutableSetOf<PostEffect>()

        val layerArray = root.optJSONArray("layers") ?: JSONArray()
        for (i in 0 until layerArray.length()) {
            val value = layerArray.optString(i)
            VisualLayer.entries.firstOrNull { it.name == value }?.let(layers::add)
        }

        val effectArray = root.optJSONArray("effects") ?: JSONArray()
        for (i in 0 until effectArray.length()) {
            val value = effectArray.optString(i)
            PostEffect.entries.firstOrNull { it.name == value }?.let(effects::add)
        }

        return SceneState(
            enabledLayers = if (layers.isEmpty()) setOf(VisualLayer.SPECTRUM) else layers,
            enabledEffects = effects,
            colorMode = ColorMode.entries.firstOrNull {
                it.name == root.optString("colorMode")
            } ?: ColorMode.RAINBOW,
            fxIntensity = root.optDouble("fxIntensity", 0.6).toFloat(),
            speed = root.optDouble("speed", 0.6).toFloat(),
            tileCount = root.optInt("tileCount", 3),
            symmetrySegments = root.optInt("symmetrySegments", 6),
            sourceMode = SourceMode.entries.firstOrNull {
                it.name == root.optString("sourceMode")
            } ?: SourceMode.MICROPHONE,
            isFullscreen = root.optBoolean("isFullscreen", false),
            isExternalVisualsEnabled = root.optBoolean("isExternalVisualsEnabled", false),
            isCastModeEnabled = root.optBoolean("isCastModeEnabled", false),
            activePresetName = root.optString("activePresetName").takeIf { it.isNotBlank() }
        )
    }
}
