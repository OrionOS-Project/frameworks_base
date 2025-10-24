/*
 * Copyright (C) 2023-2024 The LibreMobileOS Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.systemui.orion.pulselight

import android.animation.Animator
import android.animation.ValueAnimator
import android.content.Context
import android.database.ContentObserver
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import android.util.AttributeSet
import android.widget.RelativeLayout

import androidx.core.view.isVisible
import androidx.palette.graphics.Palette

import com.android.settingslib.Utils
import com.android.systemui.people.PeopleSpaceUtils
import com.android.systemui.res.R

import lineageos.hardware.LineageHardwareManager

class PulseLightView @JvmOverloads constructor(
    context: Context?,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0
) : RelativeLayout(context, attrs, defStyleAttr, defStyleRes), Animator.AnimatorListener {

    private var lightAnimator: ValueAnimator? = null
    private var rainbowAnimator: ValueAnimator? = null
    private var rainbowRotation: Float = 0f

    // Canvas drawing for both styles
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.BUTT
    }

    private val roundedPath = Path()
    private val roundedRect = RectF()
    private var cornerRadius: Float = 0f

    private var currentStyle = STYLE_DEFAULT
    private var currentColor = Color.WHITE
    private var currentProgress = 0f
    private var useRainbowGradient = false

    private var onlyWhenFaceDown = false
    private val onlyWhenFaceDownDefault by lazy {
        val default = context?.resources?.getBoolean(
            com.android.internal.R.bool.config_edgeLightFaceDownEnabledByDefault
        ) ?: false
        if (default) 1 else 0
    }

    private var lineageHardware: LineageHardwareManager? = null
    private var hasHbmSupport = false
    private var hbmEnabled = false

    init {
        setWillNotDraw(false)
        setupContentObserver()
        lineageHardware = LineageHardwareManager.getInstance(context).also { hardware ->
            hasHbmSupport = hardware.isSupported(
                LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT
            )
        }

        cornerRadius = try {
            context?.resources?.getDimension(
                context.resources.getIdentifier(
                    "rounded_corner_radius",
                    "dimen",
                    "android"
                )
            ) ?: (32f * (context?.resources?.displayMetrics?.density ?: 1f))
        } catch (e: Exception) {
            32f * (context?.resources?.displayMetrics?.density ?: 1f)
        }
    }

    private fun setupContentObserver() {
        val pulseAmbientLightFaceDown = Settings.Secure.getUriFor(PULSE_AMBIENT_LIGHT_FACE_DOWN)
        val pulseAmbientLightStyle = Settings.Secure.getUriFor(PULSE_AMBIENT_LIGHT_STYLE)
        val pulseAmbientLightWidth = Settings.Secure.getUriFor(PULSE_AMBIENT_LIGHT_WIDTH)
        
        val contentObserver = object: ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                when (uri) {
                    pulseAmbientLightFaceDown -> {
                        onlyWhenFaceDown = Settings.Secure.getIntForUser(
                            context.contentResolver,
                            PULSE_AMBIENT_LIGHT_FACE_DOWN,
                            onlyWhenFaceDownDefault,
                            UserHandle.USER_CURRENT
                        ) != 0
                        updateBackgroundColor()
                    }
                    pulseAmbientLightStyle -> {
                        currentStyle = Settings.Secure.getStringForUser(
                            context.contentResolver,
                            PULSE_AMBIENT_LIGHT_STYLE,
                            UserHandle.USER_CURRENT
                        ) ?: STYLE_DEFAULT
                        if (useRainbowGradient) updateRainbowGradient()
                        invalidate()
                    }
                    pulseAmbientLightWidth -> {
                        val width = Settings.Secure.getIntForUser(
                            context.contentResolver,
                            PULSE_AMBIENT_LIGHT_WIDTH,
                            125,
                            UserHandle.USER_CURRENT
                        )
                        edgePaint.strokeWidth = width.toFloat()
                        invalidate()
                    }
                }
            }
        }
        
        context.contentResolver.registerContentObserver(
            pulseAmbientLightFaceDown, false, contentObserver, UserHandle.USER_CURRENT)
        context.contentResolver.registerContentObserver(
            pulseAmbientLightStyle, false, contentObserver, UserHandle.USER_CURRENT)
        context.contentResolver.registerContentObserver(
            pulseAmbientLightWidth, false, contentObserver, UserHandle.USER_CURRENT)
        
        contentObserver.onChange(true, pulseAmbientLightFaceDown)
        contentObserver.onChange(true, pulseAmbientLightStyle)
        contentObserver.onChange(true, pulseAmbientLightWidth)
    }

    private fun updateBackgroundColor() {
        val bgColor = if (onlyWhenFaceDown) {
            Color.BLACK
        } else {
            Color.TRANSPARENT
        }
        setBackgroundColor(bgColor)
    }

    override fun onAnimationStart(animator: Animator) {
        enableHbm()
    }

    override fun onAnimationEnd(animator: Animator) {
        disableHbm()
    }

    override fun onAnimationCancel(animator: Animator) {
        disableHbm()
    }

    override fun onAnimationRepeat(animator: Animator) {
        // Nothing
    }

    fun startAnimation(notificationPackageName: String) {
        // Make it visible
        isVisible = true
        
        val lightDuration = Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_DURATION, 2,
            UserHandle.USER_CURRENT
        ) * 1000L
        
        val repeat = Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_REPEAT_COUNT, 0,
            UserHandle.USER_CURRENT
        )
        
        currentColor = getLightColor(notificationPackageName)
        
        if (currentColor != COLOR_RAINBOW) {
            useRainbowGradient = false
            edgePaint.shader = null
            edgePaint.color = currentColor
            edgePaint.alpha = 255
        } else {
            useRainbowGradient = true
            updateRainbowGradient()
        }
        
        lightAnimator = ValueAnimator.ofFloat(*floatArrayOf(0.0f, 2.0f)).apply {
            duration = lightDuration
            repeatCount = repeat
            repeatMode = ValueAnimator.RESTART
            addListener(this@PulseLightView)
            addUpdateListener { animation ->
                enableHbm()
                currentProgress = animation.animatedValue as Float
                
                var alpha = 1.0f
                if (currentProgress <= 0.3f) {
                    alpha = currentProgress / 0.3f
                } else if (currentProgress >= 1.0f) {
                    alpha = 2.0f - currentProgress
                }
                setAlpha(alpha)
                invalidate()
            }
            start()
        }
        
        startRainbowAnimation(lightDuration)
    }

    fun stopAnimation() {
        isVisible = false
        stopRainbowAnimation()
        lightAnimator?.cancel()
        lightAnimator = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        if (!isVisible || currentProgress == 0f) return
        
        when (currentStyle) {
            STYLE_ROUNDED -> drawRoundedEdges(canvas)
            else -> drawDefaultEdges(canvas)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (useRainbowGradient) {
            updateRainbowGradient()
            invalidate()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopRainbowAnimation()
        lightAnimator?.cancel()
        lightAnimator = null
    }

    private fun drawDefaultEdges(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.strokeCap = Paint.Cap.BUTT
        edgePaint.maskFilter = null
        
        // Left edge
        val leftScaleY = currentProgress.coerceIn(0f, 1f)
        val leftHeight = height * leftScaleY
        val leftTop = (height - leftHeight) / 2
        
        canvas.drawLine(
            halfStroke,
            leftTop,
            halfStroke,
            leftTop + leftHeight,
            edgePaint
        )
        
        // Right edge
        val rightScaleY = currentProgress.coerceIn(0f, 1f)
        val rightHeight = height * rightScaleY
        val rightTop = (height - rightHeight) / 2
        
        canvas.drawLine(
            width - halfStroke,
            rightTop,
            width - halfStroke,
            rightTop + rightHeight,
            edgePaint
        )
    }

    private fun drawRoundedEdges(canvas: Canvas) {
        val halfStroke = edgePaint.strokeWidth / 2
        edgePaint.strokeCap = Paint.Cap.ROUND
        edgePaint.maskFilter = null
        
        roundedRect.set(
            halfStroke,
            halfStroke,
            width.toFloat() - halfStroke,
            height.toFloat() - halfStroke
        )
        
        roundedPath.reset()
        roundedPath.addRoundRect(
            roundedRect,
            cornerRadius,
            cornerRadius,
            Path.Direction.CW
        )
        
        canvas.drawPath(roundedPath, edgePaint)
    }

    private fun getLightColor(notificationPackageName: String): Int {
        val colorMode = Settings.Secure.getIntForUser(
            context.contentResolver,
            Settings.Secure.PULSE_AMBIENT_LIGHT_COLOR_MODE, 1,
            UserHandle.USER_CURRENT
        )
        return when (colorMode) {
            COLOR_MODE_APP -> {
                try {
                    val iconDrawable: Drawable = context.packageManager
                        .getApplicationIcon(notificationPackageName)
                    val bitmap: Bitmap? = PeopleSpaceUtils.convertDrawableToBitmap(iconDrawable)
                    bitmap?.let { bmp ->
                        val palette = Palette.from(bmp).generate()
                        val iconColor = palette.getDominantColor(0 /* default */)
                        iconColor
                    } ?: 0
                } catch (e: Exception) {
                    0
                }
            }

            COLOR_MODE_AUTO -> {
                Utils.getColorAccentDefaultColor(context)
            }

            COLOR_MODE_RAINBOW -> {
                COLOR_RAINBOW
            }

            else -> { // COLOR_MODE_MANUAL or any other value
                Settings.Secure.getIntForUser(
                    context.contentResolver,
                    Settings.Secure.PULSE_AMBIENT_LIGHT_COLOR, -9777409 /* hex - #FF6ACEFF */,
                    UserHandle.USER_CURRENT
                )
            }
        }
    }

    private fun enableHbm() {
        if (onlyWhenFaceDown && hasHbmSupport && !hbmEnabled) {
            lineageHardware?.let { hardware ->
                hardware.set(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT, true)
                hbmEnabled = hardware.get(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT)
            }
        }
    }

    private fun disableHbm() {
        if (onlyWhenFaceDown && hasHbmSupport) {
            lineageHardware?.let { hardware ->
                hardware.set(LineageHardwareManager.FEATURE_SUNLIGHT_ENHANCEMENT, false)
                hbmEnabled = false
            }
        }
    }

    private fun updateRainbowGradient() {
        if (width == 0 || height == 0) {
            post { _updateRainbowGradient() }
        } else {
            _updateRainbowGradient()
        }
    }

    private fun _updateRainbowGradient() {
        edgePaint.shader = when (currentStyle) {
            STYLE_ROUNDED -> {
                val matrix = Matrix()
                matrix.postRotate(rainbowRotation, width / 2f, height / 2f)
                SweepGradient(width / 2f, height / 2f, RAINBOW, null).also {
                    it.setLocalMatrix(matrix)
                }
            }
            else -> {
                val offset = (rainbowRotation / 360f) * height
                LinearGradient(
                    0f, -offset, 0f, height.toFloat() - offset,
                    RAINBOW, null, Shader.TileMode.REPEAT
                )
            }
        }
    }

    private fun startRainbowAnimation(pulseDuration: Long) {
        if (!useRainbowGradient || edgePaint.shader == null) return
        if (rainbowAnimator?.isRunning == true) return

        rainbowAnimator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = pulseDuration
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.RESTART
            interpolator = android.view.animation.LinearInterpolator()
            addUpdateListener { animator ->
                if (!useRainbowGradient) return@addUpdateListener
                rainbowRotation = animator.animatedValue as Float
                _updateRainbowGradient()
                invalidate()
            }
            addListener(object : Animator.AnimatorListener {
                override fun onAnimationEnd(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }
                override fun onAnimationCancel(animation: Animator) {
                    rainbowAnimator = null
                    rainbowRotation = 0f
                }
                override fun onAnimationStart(animation: Animator) {}
                override fun onAnimationRepeat(animation: Animator) {}
            })
            start()
        }
    }

    private fun stopRainbowAnimation() {
        rainbowAnimator?.cancel()
        rainbowAnimator = null
        rainbowRotation = 0f
    }

    companion object {
        // Color modes
        private const val COLOR_MODE_APP = 0
        private const val COLOR_MODE_AUTO = 1
        private const val COLOR_MODE_MANUAL = 2
        private const val COLOR_MODE_RAINBOW = 3
        
        // Color constants
        private const val COLOR_RAINBOW = -1
        private val RAINBOW = intArrayOf(
            0xFFFF0000.toInt(), 0xFFFF7F00.toInt(), 0xFFFFFF00.toInt(),
            0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF4B0082.toInt(),
            0xFF9400D3.toInt(), 0xFFFF0000.toInt()
        )

        // Styles
        private const val STYLE_DEFAULT = "default"
        private const val STYLE_ROUNDED = "rounded"

        private const val PULSE_AMBIENT_LIGHT_FACE_DOWN =
                Settings.Secure.PULSE_AMBIENT_LIGHT_FACE_DOWN
        private const val PULSE_AMBIENT_LIGHT_STYLE =
                Settings.Secure.PULSE_AMBIENT_LIGHT_STYLE
        private const val PULSE_AMBIENT_LIGHT_WIDTH =
                Settings.Secure.PULSE_AMBIENT_LIGHT_WIDTH
    }
}
