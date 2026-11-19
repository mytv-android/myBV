package com.github.mytv.bv.player.entity

import android.content.Context
import com.github.mytv.bv.player.shared.R

enum class SpatialAudioMode(private val label: Int) {
    Off(R.string.spatial_audio_off),
    Surround51(R.string.spatial_audio_adaptive),
    Matrix51(R.string.spatial_audio_matrix);

    fun getDisplayName(context: Context) = context.getString(label)
}

/** Conservative stereo processing controls; no implicit height/object channels. */
data class StereoUpmixSettings(
    val centre: Double = 0.45,
    val surround: Double = 0.30,
    val decorrelation: Double = 0.65,
    val trimDb: Double = -3.0,
) {
    init {
        require(centre.isFinite() && centre in 0.0..0.75)
        require(surround.isFinite() && surround in 0.0..0.60)
        require(decorrelation.isFinite() && decorrelation in 0.0..1.0)
        require(trimDb.isFinite() && trimDb in -12.0..6.0)
    }
}
