package com.aucai.aicamera.ui

import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import com.aucai.aicamera.core.LookParams
import com.aucai.aicamera.core.LookShader
import kotlin.math.pow

/** The look as a GPU effect for the live preview (Android 13+). */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
object LookEffects {

    private var shader: RuntimeShader? = null

    /** Throws if the shader cannot be compiled on this phone; the caller falls back. */
    fun renderEffect(p: LookParams): RenderEffect {
        val s = shader ?: RuntimeShader(LookShader.SOURCE).also { shader = it }
        val gain = 2f.pow(p.exposure / 2.2f)
        s.setFloatUniform("wb", gain * (1f + 0.1f * p.warmth), gain, gain * (1f - 0.1f * p.warmth))
        s.setFloatUniform("shadows", p.shadows)
        s.setFloatUniform("highlights", p.highlights)
        s.setFloatUniform("contrast", p.contrast)
        s.setFloatUniform("fade", p.fade)
        s.setFloatUniform("sat", 1f + p.saturation)
        s.setFloatUniform("vib", p.vibrance)
        s.setFloatUniform("sky", p.sky)
        s.setFloatUniform("green", p.green)
        s.setFloatUniform("warm", p.warm)
        s.setFloatUniform("mono", if (p.mono) 1f else 0f)
        // Effects keep a copy of the uniforms, so one shader can serve every update.
        return RenderEffect.createRuntimeShaderEffect(s, "content")
    }
}
