package com.ravewave.app.scene

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

class SceneRepository(context: Context) {
    private val presetStore = PresetStore(context.applicationContext)

    private val _state = MutableStateFlow(SceneState())
    val state: StateFlow<SceneState> = _state.asStateFlow()

    fun setSourceMode(mode: SourceMode) {
        _state.value = _state.value.copy(sourceMode = mode)
    }

    fun setFullscreen(enabled: Boolean) {
        _state.value = _state.value.copy(isFullscreen = enabled)
    }

    fun setExternalVisualsEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(isExternalVisualsEnabled = enabled)
    }

    fun setCastModeEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(isCastModeEnabled = enabled)
    }

    fun setFxIntensity(value: Float) {
        _state.value = _state.value.copy(fxIntensity = value.coerceIn(0f, 1f))
    }

    fun setSpeed(value: Float) {
        _state.value = _state.value.copy(speed = value.coerceIn(0f, 1f))
    }

    fun setTileCount(value: Int) {
        _state.value = _state.value.copy(tileCount = value.coerceIn(2, 6))
    }

    fun setSymmetrySegments(value: Int) {
        _state.value = _state.value.copy(symmetrySegments = value.coerceIn(4, 12))
    }

    fun setLayerEnabled(layer: VisualLayer, enabled: Boolean) {
        val next = _state.value.enabledLayers.toMutableSet()
        if (enabled) next.add(layer) else next.remove(layer)
        _state.value = _state.value.copy(enabledLayers = next)
    }

    fun setEffectEnabled(effect: PostEffect, enabled: Boolean) {
        val next = _state.value.enabledEffects.toMutableSet()
        if (enabled) next.add(effect) else next.remove(effect)
        _state.value = _state.value.copy(enabledEffects = next)
    }

    fun savePreset(name: String) {
        if (name.isBlank()) return
        val cleanName = name.trim()
        presetStore.save(ScenePreset(cleanName, _state.value.copy(activePresetName = cleanName)))
        _state.value = _state.value.copy(activePresetName = cleanName)
    }

    fun loadPreset(name: String): Boolean {
        val preset = presetStore.load(name.trim()) ?: return false
        _state.value = preset.state.copy(activePresetName = preset.name)
        return true
    }

    fun listPresetNames(): List<String> = presetStore.listNames()
}

private class PresetStore(context: Context) {
    private val prefs = context.getSharedPreferences("ravewave_presets", Context.MODE_PRIVATE)

    fun save(preset: ScenePreset) {
        prefs.edit().putString("preset_${preset.name}", encodeState(preset.state)).apply()
    }

    fun load(name: String): ScenePreset? {
        val raw = prefs.getString("preset_$name", null) ?: return null
        return ScenePreset(name, decodeState(raw))
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
