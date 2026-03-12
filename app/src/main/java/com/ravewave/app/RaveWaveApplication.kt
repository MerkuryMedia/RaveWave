package com.ravewave.app

import android.app.Application
import com.ravewave.app.analyzer.AudioAnalyzerEngine
import com.ravewave.app.audio.AudioSourceManager
import com.ravewave.app.cast.CastController
import com.ravewave.app.cast.CastStateSyncChannel
import com.ravewave.app.display.DisplaySessionManager
import com.ravewave.app.scene.SceneRepository

class RaveWaveApplication : Application() {
    lateinit var sceneRepository: SceneRepository
        private set

    lateinit var analyzerEngine: AudioAnalyzerEngine
        private set

    lateinit var audioSourceManager: AudioSourceManager
        private set

    lateinit var castController: CastController
        private set

    lateinit var castStateSync: CastStateSyncChannel
        private set

    lateinit var displaySessionManager: DisplaySessionManager
        private set

    override fun onCreate() {
        super.onCreate()
        sceneRepository = SceneRepository(this)
        analyzerEngine = AudioAnalyzerEngine()
        analyzerEngine.start()

        audioSourceManager = AudioSourceManager(this, analyzerEngine)
        castController = CastController(this)
        castStateSync = CastStateSyncChannel()
        displaySessionManager = DisplaySessionManager(this)
    }

    override fun onTerminate() {
        super.onTerminate()
        audioSourceManager.release()
        analyzerEngine.stop()
    }
}
