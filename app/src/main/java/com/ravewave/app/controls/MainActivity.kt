package com.ravewave.app.controls

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.materialswitch.MaterialSwitch
import com.ravewave.app.R
import com.ravewave.app.RaveWaveApplication
import com.ravewave.app.databinding.ActivityMainBinding
import com.ravewave.app.render.VisualizerSurfaceView
import com.ravewave.app.scene.LayerCategory
import com.ravewave.app.scene.PostEffect
import com.ravewave.app.scene.SourceMode
import com.ravewave.app.scene.VisualLayer
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var app: RaveWaveApplication
    private lateinit var previewSurface: VisualizerSurfaceView
    private var externalSurface: VisualizerSurfaceView? = null

    private val layerSwitches = mutableMapOf<VisualLayer, MaterialSwitch>()
    private val effectSwitches = mutableMapOf<PostEffect, MaterialSwitch>()

    private var isMenuHidden = false

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            viewModel.setSelectedFile(uri, resolveDisplayName(uri))
            viewModel.selectSource(SourceMode.FILE)
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.selectSource(SourceMode.MICROPHONE)
        } else {
            binding.statusText.text = "Microphone permission denied"
        }
    }

    private val captureConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode != RESULT_OK || result.data == null) {
            binding.statusText.text = "Playback capture canceled"
            return@registerForActivityResult
        }

        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val projection = manager.getMediaProjection(result.resultCode, result.data!!)
        viewModel.setPlaybackCaptureSource(projection)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as RaveWaveApplication

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        previewSurface = VisualizerSurfaceView(this, app.analyzerEngine)
        binding.renderHost.addView(previewSurface)

        setupLayerToggles()
        setupEffectToggles()
        setupUiActions()
        observeState()
    }

    override fun onStart() {
        super.onStart()
        app.castController.onStart()
        app.displaySessionManager.onStart()
    }

    override fun onStop() {
        super.onStop()
        app.castController.onStop()
        app.displaySessionManager.onStop()
    }

    override fun onResume() {
        super.onResume()
        previewSurface.onResume()
        externalSurface?.onResume()
    }

    override fun onPause() {
        externalSurface?.onPause()
        previewSurface.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        externalSurface = null
    }

    private fun setupUiActions() {
        binding.micButton.setOnClickListener {
            startMicrophoneMode()
        }

        binding.fileButton.setOnClickListener {
            filePickerLauncher.launch(arrayOf("audio/*"))
        }

        binding.captureButton.setOnClickListener {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            captureConsentLauncher.launch(manager.createScreenCaptureIntent())
        }

        binding.playPauseButton.setOnClickListener {
            viewModel.toggleFilePlayback()
        }

        binding.fullscreenButton.setOnClickListener {
            viewModel.setFullscreen(!viewModel.uiState.value.scene.isFullscreen)
        }

        binding.externalButton.setOnClickListener {
            val enable = !viewModel.uiState.value.scene.isExternalVisualsEnabled
            val activated = app.displaySessionManager.setExternalVisualsEnabled(enable, this) { context ->
                VisualizerSurfaceView(context, app.analyzerEngine).also {
                    externalSurface = it
                    it.updateSceneState(viewModel.uiState.value.scene)
                }
            }
            viewModel.setExternalVisualsEnabled(if (enable) activated else false)
            if (!enable || !activated) externalSurface = null
        }

        binding.castButton.setOnClickListener {
            val castState = viewModel.uiState.value.castState
            if (castState.isConnected) {
                viewModel.setCastMode(!viewModel.uiState.value.scene.isCastModeEnabled)
            } else {
                app.castController.showRouteChooser(this)
            }
        }

        binding.hideMenuButton.setOnClickListener {
            setMenuHidden(true)
        }

        binding.showMenuButton.setOnClickListener {
            setMenuHidden(false)
        }

        binding.intensitySeek.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            viewModel.setFxIntensity(value)
        })

        binding.speedSeek.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            viewModel.setSpeed(value)
        })

        binding.tileSeek.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            viewModel.setTileCount(value)
        })

        binding.symmetrySeek.setOnSeekBarChangeListener(SimpleSeekListener { value ->
            viewModel.setSymmetry(value)
        })

        binding.savePresetButton.setOnClickListener {
            viewModel.savePreset(binding.presetNameInput.text?.toString().orEmpty())
        }

        binding.loadPresetButton.setOnClickListener {
            val name = binding.presetNameInput.text?.toString().orEmpty().ifBlank {
                viewModel.uiState.value.presetNames.firstOrNull().orEmpty()
            }
            if (!viewModel.loadPreset(name)) {
                binding.statusText.text = "Preset not found"
            }
        }
    }

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                viewModel.uiState.collect { ui ->
                    previewSurface.updateSceneState(ui.scene)
                    externalSurface?.updateSceneState(ui.scene)

                    binding.statusText.text = buildStatusText(ui)
                    binding.fileNameText.text = ui.sourceStatus.selectedFileName ?: "No file selected"

                    syncSwitches(ui)
                    syncSliders(ui)
                    syncButtons(ui)
                    applyFullscreen(ui.scene.isFullscreen)
                }
            }
        }
    }

    private fun setupLayerToggles() {
        addLayerGroup(binding.baseLayersContainer, LayerCategory.BASE)
        addLayerGroup(binding.sacredLayersContainer, LayerCategory.SACRED)
        addLayerGroup(binding.extraLayersContainer, LayerCategory.EXTRA)
        addLayerGroup(binding.musicLayersContainer, LayerCategory.MUSIC_OBJECT)
    }

    private fun setupEffectToggles() {
        for (effect in PostEffect.entries) {
            val toggle = createSwitch(effect.displayName) { enabled ->
                viewModel.setEffectEnabled(effect, enabled)
            }
            effectSwitches[effect] = toggle
            binding.postFxContainer.addView(toggle)
        }
    }

    private fun addLayerGroup(container: LinearLayout, category: LayerCategory) {
        val layers = VisualLayer.entries.filter { it.category == category }
        for (layer in layers) {
            val toggle = createSwitch(layer.displayName) { enabled ->
                viewModel.setLayerEnabled(layer, enabled)
            }
            layerSwitches[layer] = toggle
            container.addView(toggle)
        }
    }

    private fun createSwitch(label: String, onChanged: (Boolean) -> Unit): MaterialSwitch {
        return MaterialSwitch(this).apply {
            text = label
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.rw_text))
            setOnCheckedChangeListener { _, isChecked ->
                onChanged(isChecked)
            }
        }
    }

    private fun syncSwitches(ui: ControlUiState) {
        layerSwitches.forEach { (layer, toggle) ->
            val checked = ui.scene.enabledLayers.contains(layer)
            if (toggle.isChecked != checked) toggle.isChecked = checked
        }
        effectSwitches.forEach { (effect, toggle) ->
            val checked = ui.scene.enabledEffects.contains(effect)
            if (toggle.isChecked != checked) toggle.isChecked = checked
        }
    }

    private fun syncSliders(ui: ControlUiState) {
        val intensity = (ui.scene.fxIntensity * 100f).toInt().coerceIn(0, 100)
        if (binding.intensitySeek.progress != intensity) {
            binding.intensitySeek.progress = intensity
        }

        val speed = (ui.scene.speed * 100f).toInt().coerceIn(0, 100)
        if (binding.speedSeek.progress != speed) {
            binding.speedSeek.progress = speed
        }

        val tileProgress = (ui.scene.tileCount - 2).coerceIn(0, 4)
        if (binding.tileSeek.progress != tileProgress) {
            binding.tileSeek.progress = tileProgress
        }

        val symmetryProgress = (ui.scene.symmetrySegments - 4).coerceIn(0, 8)
        if (binding.symmetrySeek.progress != symmetryProgress) {
            binding.symmetrySeek.progress = symmetryProgress
        }
    }

    private fun syncButtons(ui: ControlUiState) {
        binding.castButton.text = if (ui.castState.isConnected) {
            if (ui.scene.isCastModeEnabled) "Cast: Sync On" else "Cast: Sync Off"
        } else {
            getString(R.string.cast)
        }

        binding.externalButton.text = if (ui.scene.isExternalVisualsEnabled) {
            "External: On"
        } else {
            getString(R.string.external_display)
        }

        if (ui.scene.activePresetName != null && binding.presetNameInput.text.isNullOrBlank()) {
            binding.presetNameInput.setText(ui.scene.activePresetName)
        }
    }

    private fun startMicrophoneMode() {
        when {
            ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED -> {
                viewModel.selectSource(SourceMode.MICROPHONE)
            }

            shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO) -> {
                binding.statusText.text = "Microphone permission is required for mic mode"
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }

            else -> {
                micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    private fun setMenuHidden(hidden: Boolean) {
        isMenuHidden = hidden
        binding.controlsScroll.visibility = if (hidden) View.GONE else View.VISIBLE
        binding.showMenuButton.visibility = if (hidden) View.VISIBLE else View.GONE
    }

    private fun applyFullscreen(enabled: Boolean) {
        WindowCompat.setDecorFitsSystemWindows(window, !enabled)
        val controller = WindowInsetsControllerCompat(window, window.decorView)

        if (enabled) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

            val lp = binding.renderHost.layoutParams
            lp.height = LinearLayout.LayoutParams.MATCH_PARENT
            binding.renderHost.layoutParams = lp
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())

            val lp = binding.renderHost.layoutParams
            lp.height = resources.getDimensionPixelSize(R.dimen.preview_height)
            binding.renderHost.layoutParams = lp
        }
    }

    private fun buildStatusText(ui: ControlUiState): String {
        return "${ui.sourceStatus.message} | " +
            "Display: ${ui.externalDisplayState.message} | " +
            "Cast: ${ui.castState.message} | " +
            "Energy ${"%.2f".format(ui.analyzerMetrics.energy)}"
    }

    private fun resolveDisplayName(uri: Uri): String {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) {
                return cursor.getString(index)
            }
        }
        return uri.lastPathSegment ?: "Selected file"
    }

    private class SimpleSeekListener(
        private val onProgress: (Int) -> Unit
    ) : android.widget.SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(seekBar: android.widget.SeekBar?, progress: Int, fromUser: Boolean) {
            if (fromUser) onProgress(progress)
        }

        override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
        override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) = Unit
    }
}
