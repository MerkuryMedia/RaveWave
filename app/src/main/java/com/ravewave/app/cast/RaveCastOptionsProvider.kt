package com.ravewave.app.cast

import androidx.annotation.Keep
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider
import com.ravewave.app.R

@Keep
class RaveCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: android.content.Context): CastOptions {
        return CastOptions.Builder()
            .setReceiverApplicationId(context.getString(R.string.cast_receiver_app_id))
            .build()
    }

    override fun getAdditionalSessionProviders(context: android.content.Context): MutableList<SessionProvider> {
        return mutableListOf()
    }
}
