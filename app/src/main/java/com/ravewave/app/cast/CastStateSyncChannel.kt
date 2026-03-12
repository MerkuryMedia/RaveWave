package com.ravewave.app.cast

import com.google.android.gms.cast.framework.CastSession
import com.ravewave.app.analyzer.AnalyzerMetrics
import com.ravewave.app.scene.SceneState
import org.json.JSONObject

class CastStateSyncChannel(
    private val namespace: String = "urn:x-cast:com.ravewave.state"
) {
    fun sendSceneState(session: CastSession?, sceneState: SceneState) {
        if (session?.isConnected != true) return
        val payload = JSONObject()
            .put("type", "scene")
            .put("layers", sceneState.enabledLayers.map { it.name })
            .put("effects", sceneState.enabledEffects.map { it.name })
            .put("fxIntensity", sceneState.fxIntensity)
            .put("speed", sceneState.speed)
            .put("tileCount", sceneState.tileCount)
            .put("symmetrySegments", sceneState.symmetrySegments)
            .toString()
        session.sendMessage(namespace, payload)
    }

    fun sendMetrics(session: CastSession?, metrics: AnalyzerMetrics) {
        if (session?.isConnected != true) return
        val payload = JSONObject()
            .put("type", "audio")
            .put("frame", metrics.frameIndex)
            .put("bass", metrics.bass)
            .put("mids", metrics.mids)
            .put("highs", metrics.highs)
            .put("energy", metrics.energy)
            .put("beatPulse", metrics.beatPulse)
            .put("onsetPulse", metrics.onsetPulse)
            .toString()
        session.sendMessage(namespace, payload)
    }
}
