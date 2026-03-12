package com.ravewave.app.display

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.widget.FrameLayout
import android.widget.TextView

class VisualizerPresentation(
    context: Context,
    display: Display,
    private val contentFactory: (Context) -> android.view.View
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = FrameLayout(context)
        val view = runCatching { contentFactory(context) }.getOrElse {
            TextView(context).apply {
                text = "Failed to create renderer: ${it.message}"
            }
        }
        root.addView(view)
        setContentView(root)
    }
}
