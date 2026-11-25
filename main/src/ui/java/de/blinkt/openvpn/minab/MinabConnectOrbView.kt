/*
 * Minab — addition on top of ics-openvpn (GPLv2).
 * Copyright (c) 2026 Minab contributors.
 * Distributed under the GNU GPL v2, same as the base project.
 *
 * MinabConnectOrbView: custom hardware-accelerated animated connect button
 * replacing the dull grey Lottie overlay that masked the state colours.
 *
 * Distinct animated visual states:
 *  - DISCONNECTED: vibrant indigo-violet gradient orb with calm breathing
 *    aura halo and soft rotating rim shimmer.
 *  - CONNECTING: luminous amber-orange gradient orb with dual expanding
 *    radar pulse waves, fast SweepGradient comet arc, and counter-rotating
 *    dashed orbital ring.
 *  - CONNECTED: vivid emerald-mint gradient orb with an entry shockwave
 *    burst + spring pop, steady ambient emerald glow, and protective
 *    rotating sheen ring.
 *  - DISCONNECTING: coral-crimson gradient orb with inward-contracting
 *    ring wave and reverse-spinning arc before morphing back to indigo.
 *  - DISABLED_TESTING: muted cool slate state with animations paused while
 *    a ping or real test is running.
 *
 * All paints, rects, matrices and paths are pre-allocated (zero allocations
 * in onDraw), colours morph smoothly with ArgbEvaluator across state
 * changes, and animations pause automatically when detached or hidden.
 */

package de.blinkt.openvpn.minab

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import de.blinkt.openvpn.R
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

class MinabConnectOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    enum class State {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        DISCONNECTING,
        DISABLED_TESTING
    }

    private data class Palette(
        val coreStart: Int,
        val coreEnd: Int,
        val glow: Int,
        val ring: Int,
        val iconTint: Int
    )

    private val disconnectedPalette = Palette(
        coreStart = ContextCompat.getColor(context, R.color.minab_orb_idle_start),
        coreEnd = ContextCompat.getColor(context, R.color.minab_orb_idle_end),
        glow = ContextCompat.getColor(context, R.color.minab_orb_idle_glow),
        ring = ContextCompat.getColor(context, R.color.minab_orb_idle_ring),
        iconTint = Color.WHITE
    )
    private val connectingPalette = Palette(
        coreStart = ContextCompat.getColor(context, R.color.minab_orb_connecting_start),
        coreEnd = ContextCompat.getColor(context, R.color.minab_orb_connecting_end),
        glow = ContextCompat.getColor(context, R.color.minab_orb_connecting_glow),
        ring = ContextCompat.getColor(context, R.color.minab_orb_connecting_ring),
        iconTint = Color.WHITE
    )
    private val connectedPalette = Palette(
        coreStart = ContextCompat.getColor(context, R.color.minab_orb_connected_start),
        coreEnd = ContextCompat.getColor(context, R.color.minab_orb_connected_end),
        glow = ContextCompat.getColor(context, R.color.minab_orb_connected_glow),
        ring = ContextCompat.getColor(context, R.color.minab_orb_connected_ring),
        iconTint = Color.WHITE
    )
    private val disconnectingPalette = Palette(
        coreStart = ContextCompat.getColor(context, R.color.minab_orb_disconnecting_start),
        coreEnd = ContextCompat.getColor(context, R.color.minab_orb_disconnecting_end),
        glow = ContextCompat.getColor(context, R.color.minab_orb_disconnecting_glow),
        ring = ContextCompat.getColor(context, R.color.minab_orb_disconnecting_ring),
        iconTint = Color.WHITE
    )
    private val disabledPalette = Palette(
        coreStart = ContextCompat.getColor(context, R.color.minab_orb_disabled_start),
        coreEnd = ContextCompat.getColor(context, R.color.minab_orb_disabled_end),
        glow = ContextCompat.getColor(context, R.color.minab_orb_disabled_glow),
        ring = ContextCompat.getColor(context, R.color.minab_orb_disabled_ring),
        iconTint = 0xCCFFFFFF.toInt()
    )

    private var currentState: State = State.DISCONNECTED
    private var animationsAllowed: Boolean = true
    private var attached: Boolean = false

    // Interpolated colours (updated smoothly across state changes).
    private var curCoreStart: Int = disconnectedPalette.coreStart
    private var curCoreEnd: Int = disconnectedPalette.coreEnd
    private var curGlowColor: Int = disconnectedPalette.glow
    private var curRingColor: Int = disconnectedPalette.ring
    private var curIconTint: Int = disconnectedPalette.iconTint

    // Continuous 0..1 phase driven by phaseAnimator.
    private var phase: Float = 0f
    // One-shot shockwave burst (0..1) on connect / disconnect.
    private var burstProgress: Float = 1f
    private var burstColor: Int = connectedPalette.ring
    // Core scale factor combining press feedback + connect pop.
    private var pressScale: Float = 1f
    private var popScale: Float = 1f

    // Cached geometry & shaders to avoid allocations in onDraw.
    private var cx: Float = 0f
    private var cy: Float = 0f
    private var maxRadius: Float = 0f
    private var coreRadius: Float = 0f
    private var shadersDirty: Boolean = true

    private val density: Float = resources.displayMetrics.density
    private val argbEvaluator = ArgbEvaluator()
    private val shaderMatrix = Matrix()
    private val arcRect = RectF()
    private val iconArcRect = RectF()

    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ripplePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val burstPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
    }
    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val coreBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.6f * density
        color = 0x70FFFFFF
    }
    private val outerOrbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.6f * density
    }
    private val innerOrbitPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 1.6f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 7f * density), 0f)
    }
    private val iconShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 3.2f * density
        color = 0x33000000
    }
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.8f * density
        color = Color.WHITE
    }

    private var coreShader: LinearGradient? = null
    private var sheenShader: RadialGradient? = null
    private var glowShader: RadialGradient? = null
    private var sweepShader: SweepGradient? = null

    private val phaseAnimator: ValueAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 3200L
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.RESTART
        interpolator = LinearInterpolator()
        addUpdateListener { anim ->
            phase = anim.animatedValue as Float
            invalidate()
        }
    }

    private var colorAnimator: ValueAnimator? = null
    private var burstAnimator: ValueAnimator? = null
    private var popAnimator: ValueAnimator? = null
    private var pressAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        updateContentDescription()
    }

    fun getState(): State = currentState

    fun setState(newState: State, animate: Boolean = true) {
        if (newState == currentState) {
            syncPhaseAnimator()
            return
        }
        val oldState = currentState
        currentState = newState
        updateContentDescription()

        val target = paletteFor(newState)
        colorAnimator?.cancel()
        if (!animate || width == 0) {
            curCoreStart = target.coreStart
            curCoreEnd = target.coreEnd
            curGlowColor = target.glow
            curRingColor = target.ring
            curIconTint = target.iconTint
            shadersDirty = true
            invalidate()
        } else {
            val fromStart = curCoreStart
            val fromEnd = curCoreEnd
            val fromGlow = curGlowColor
            val fromRing = curRingColor
            val fromIcon = curIconTint
            colorAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = if (newState == State.DISCONNECTING) 260L else 420L
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    val f = anim.animatedFraction
                    curCoreStart = argbEvaluator.evaluate(f, fromStart, target.coreStart) as Int
                    curCoreEnd = argbEvaluator.evaluate(f, fromEnd, target.coreEnd) as Int
                    curGlowColor = argbEvaluator.evaluate(f, fromGlow, target.glow) as Int
                    curRingColor = argbEvaluator.evaluate(f, fromRing, target.ring) as Int
                    curIconTint = argbEvaluator.evaluate(f, fromIcon, target.iconTint) as Int
                    shadersDirty = true
                    invalidate()
                }
                start()
            }
        }

        // Trigger celebratory burst + elastic pop when becoming CONNECTED,
        // or a quick reverse pulse when DISCONNECTING.
        if (animate && oldState != newState) {
            when (newState) {
                State.CONNECTED -> {
                    triggerBurst(connectedPalette.ring, 700L)
                    triggerPop(0.90f, 1.07f, 460L)
                }
                State.CONNECTING -> {
                    triggerBurst(connectingPalette.ring, 520L)
                    triggerPop(0.95f, 1.03f, 320L)
                }
                State.DISCONNECTING -> {
                    triggerBurst(disconnectingPalette.ring, 500L)
                }
                else -> {}
            }
        }

        // Adjust cycle speed per state for distinct energy levels.
        phaseAnimator.duration = when (newState) {
            State.CONNECTING -> 1700L
            State.DISCONNECTING -> 1400L
            State.CONNECTED -> 3200L
            State.DISCONNECTED -> 3800L
            State.DISABLED_TESTING -> 3800L
        }
        syncPhaseAnimator()
        invalidate()
    }

    /** Called by host fragment in onResume/onPause so offscreen/background
        tabs spend zero CPU animating. */
    fun setAnimationsEnabled(enabled: Boolean) {
        if (animationsAllowed == enabled) return
        animationsAllowed = enabled
        syncPhaseAnimator()
    }

    private fun paletteFor(state: State): Palette = when (state) {
        State.DISCONNECTED -> disconnectedPalette
        State.CONNECTING -> connectingPalette
        State.CONNECTED -> connectedPalette
        State.DISCONNECTING -> disconnectingPalette
        State.DISABLED_TESTING -> disabledPalette
    }

    private fun updateContentDescription() {
        val resId = when (currentState) {
            State.CONNECTED -> R.string.minab_orb_desc_connected
            State.CONNECTING -> R.string.minab_status_connecting
            State.DISCONNECTING -> R.string.minab_status_disconnecting
            State.DISABLED_TESTING -> R.string.minab_status_testing
            State.DISCONNECTED -> R.string.minab_tap_to_connect
        }
        contentDescription = try {
            context.getString(resId)
        } catch (_: Exception) {
            ""
        }
    }

    private fun triggerBurst(color: Int, durationMs: Long) {
        burstAnimator?.cancel()
        burstColor = color
        burstProgress = 0f
        burstAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = durationMs
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { anim ->
                burstProgress = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun triggerPop(downScale: Float, overshootScale: Float, durationMs: Long) {
        popAnimator?.cancel()
        popAnimator = ValueAnimator.ofFloat(downScale, overshootScale, 1f).apply {
            duration = durationMs
            interpolator = OvershootInterpolator(1.4f)
            addUpdateListener { anim ->
                popScale = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun animatePress(target: Float) {
        pressAnimator?.cancel()
        pressAnimator = ValueAnimator.ofFloat(pressScale, target).apply {
            duration = 120L
            interpolator = DecelerateInterpolator()
            addUpdateListener { anim ->
                pressScale = anim.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || currentState == State.DISABLED_TESTING) {
            return false
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animatePress(0.92f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animatePress(1f)
        }
        return super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        cx = w * 0.5f
        cy = h * 0.5f
        maxRadius = min(w, h) * 0.5f
        coreRadius = maxRadius * 0.68f
        shadersDirty = true
    }

    private fun rebuildShadersIfNeeded() {
        if (!shadersDirty || maxRadius <= 0f) return
        shadersDirty = false

        coreShader = LinearGradient(
            cx - coreRadius, cy - coreRadius,
            cx + coreRadius, cy + coreRadius,
            curCoreStart, curCoreEnd,
            Shader.TileMode.CLAMP
        )
        corePaint.shader = coreShader

        // Top-left glossy specular sheen gives the orb tactile depth.
        sheenShader = RadialGradient(
            cx - coreRadius * 0.32f,
            cy - coreRadius * 0.36f,
            coreRadius * 0.95f,
            intArrayOf(0x55FFFFFF, 0x16FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        sheenPaint.shader = sheenShader

        val glowMid = withAlpha(curGlowColor, 0x66)
        val glowEdge = withAlpha(curGlowColor, 0x00)
        glowShader = RadialGradient(
            cx, cy, maxRadius,
            intArrayOf(curGlowColor, glowMid, glowEdge),
            floatArrayOf(0.45f, 0.76f, 1f),
            Shader.TileMode.CLAMP
        )
        glowPaint.shader = glowShader

        val brightHead = blendWithWhite(curRingColor, 0.45f)
        sweepShader = SweepGradient(
            cx, cy,
            intArrayOf(
                withAlpha(curRingColor, 0x00),
                withAlpha(curRingColor, 0x22),
                withAlpha(curRingColor, 0xCC),
                brightHead,
                withAlpha(curRingColor, 0x00)
            ),
            floatArrayOf(0f, 0.48f, 0.80f, 0.94f, 1f)
        )
        outerOrbitPaint.shader = sweepShader
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (maxRadius <= 0f) return
        rebuildShadersIfNeeded()

        val breath = (sin(phase * 2.0 * PI) * 0.5 + 0.5).toFloat() // 0..1 smooth sine
        val state = currentState

        // 1. Ambient outer aura glow.
        if (state != State.DISABLED_TESTING) {
            val glowAlphaFactor = when (state) {
                State.CONNECTED -> 0.72f + 0.28f * breath
                State.CONNECTING -> 0.55f + 0.45f * breath
                State.DISCONNECTING -> 0.50f + 0.35f * breath
                State.DISCONNECTED -> 0.40f + 0.30f * breath
                State.DISABLED_TESTING -> 0.15f
            }
            val glowScale = when (state) {
                State.CONNECTED -> 0.94f + 0.06f * breath
                State.CONNECTING -> 0.90f + 0.10f * breath
                else -> 0.90f + 0.07f * breath
            }
            glowPaint.alpha = (255 * glowAlphaFactor).toInt().coerceIn(0, 255)
            canvas.save()
            canvas.scale(glowScale, glowScale, cx, cy)
            canvas.drawCircle(cx, cy, maxRadius, glowPaint)
            canvas.restore()
        }

        // 2. State-specific dynamic outer waves & rings.
        when (state) {
            State.CONNECTING -> {
                // Dual staggered sonar waves radiating outward.
                drawRadarWave(canvas, phase, curRingColor, 2.0f * density)
                drawRadarWave(canvas, (phase + 0.5f) % 1f, curRingColor, 1.6f * density)
                // Fast luminous comet-tail SweepGradient orbital ring.
                val orbitR = coreRadius + (maxRadius - coreRadius) * 0.44f
                arcRect.set(cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR)
                shaderMatrix.setRotate(phase * 360f, cx, cy)
                sweepShader?.setLocalMatrix(shaderMatrix)
                outerOrbitPaint.alpha = 255
                outerOrbitPaint.strokeWidth = 2.8f * density
                canvas.drawArc(arcRect, 0f, 360f, false, outerOrbitPaint)

                // Counter-rotating dashed energy ring slightly inside.
                val innerR = coreRadius + (maxRadius - coreRadius) * 0.20f
                innerOrbitPaint.color = withAlpha(curRingColor, 0xAA)
                canvas.save()
                canvas.rotate(-phase * 180f, cx, cy)
                canvas.drawCircle(cx, cy, innerR, innerOrbitPaint)
                canvas.restore()
            }

            State.CONNECTED -> {
                // Soft steady protective ring + slow rotating shimmer sweep.
                val ringR = coreRadius + (maxRadius - coreRadius) * 0.38f
                ripplePaint.color = withAlpha(curRingColor, (70 + (45 * breath)).toInt())
                ripplePaint.strokeWidth = 1.4f * density
                canvas.drawCircle(cx, cy, ringR, ripplePaint)

                arcRect.set(cx - ringR, cy - ringR, cx + ringR, cy + ringR)
                shaderMatrix.setRotate(phase * 360f, cx, cy)
                sweepShader?.setLocalMatrix(shaderMatrix)
                outerOrbitPaint.alpha = 215
                outerOrbitPaint.strokeWidth = 2.3f * density
                canvas.drawArc(arcRect, 0f, 360f, false, outerOrbitPaint)
            }

            State.DISCONNECTING -> {
                // Inward-contracting ring wave (1 -> 0) + reverse spinning arc.
                val inv = 1f - phase
                val waveR = coreRadius + (maxRadius - coreRadius) * inv
                ripplePaint.color = withAlpha(curRingColor, (190 * (1f - inv)).toInt().coerceIn(0, 255))
                ripplePaint.strokeWidth = 2.2f * density
                canvas.drawCircle(cx, cy, waveR, ripplePaint)

                val orbitR = coreRadius + (maxRadius - coreRadius) * 0.40f
                arcRect.set(cx - orbitR, cy - orbitR, cx + orbitR, cy + orbitR)
                shaderMatrix.setRotate(-phase * 360f, cx, cy)
                sweepShader?.setLocalMatrix(shaderMatrix)
                outerOrbitPaint.alpha = 230
                outerOrbitPaint.strokeWidth = 2.5f * density
                canvas.drawArc(arcRect, 0f, 360f, false, outerOrbitPaint)
            }

            State.DISCONNECTED -> {
                // Subtle breathing halo ring + slow elegant glint arc.
                val ringR = coreRadius + (maxRadius - coreRadius) * (0.28f + 0.14f * breath)
                ripplePaint.color = withAlpha(curRingColor, (55 + (55 * breath)).toInt())
                ripplePaint.strokeWidth = 1.3f * density
                canvas.drawCircle(cx, cy, ringR, ripplePaint)

                val glintR = coreRadius + (maxRadius - coreRadius) * 0.34f
                arcRect.set(cx - glintR, cy - glintR, cx + glintR, cy + glintR)
                shaderMatrix.setRotate(phase * 360f, cx, cy)
                sweepShader?.setLocalMatrix(shaderMatrix)
                outerOrbitPaint.alpha = 145
                outerOrbitPaint.strokeWidth = 1.8f * density
                canvas.drawArc(arcRect, 0f, 360f, false, outerOrbitPaint)
            }

            State.DISABLED_TESTING -> {
                val ringR = coreRadius + (maxRadius - coreRadius) * 0.28f
                ripplePaint.color = withAlpha(curRingColor, 0x44)
                ripplePaint.strokeWidth = 1.2f * density
                canvas.drawCircle(cx, cy, ringR, ripplePaint)
            }
        }

        // 3. One-shot celebratory shockwave burst (on connect / start connecting).
        if (burstProgress < 1f) {
            val bR = coreRadius + (maxRadius - coreRadius) * burstProgress
            val alpha = ((1f - burstProgress) * 230f).toInt().coerceIn(0, 255)
            burstPaint.color = withAlpha(burstColor, alpha)
            burstPaint.strokeWidth = (3.2f - 2.2f * burstProgress) * density
            canvas.drawCircle(cx, cy, bR, burstPaint)
        }

        // 4. Core tactile gradient disc + specular sheen + crisp power glyph.
        val breathCoreScale = when (state) {
            State.CONNECTING -> 0.975f + 0.045f * breath
            State.CONNECTED -> 0.99f + 0.02f * breath
            State.DISCONNECTED -> 0.985f + 0.02f * breath
            else -> 1f
        }
        val finalCoreScale = pressScale * popScale * breathCoreScale

        canvas.save()
        canvas.scale(finalCoreScale, finalCoreScale, cx, cy)

        // Core gradient circle.
        canvas.drawCircle(cx, cy, coreRadius, corePaint)
        // Subtle specular sheen for dimensional glass-orb depth.
        canvas.drawCircle(cx, cy, coreRadius, sheenPaint)
        // Luminous white edge stroke.
        canvas.drawCircle(cx, cy, coreRadius - coreBorderPaint.strokeWidth * 0.5f, coreBorderPaint)

        // Crisp Power Symbol (arc + vertical stem) with gentle drop shadow.
        val iconR = coreRadius * 0.39f
        iconArcRect.set(cx - iconR, cy - iconR, cx + iconR, cy + iconR)
        val stemTop = cy - iconR * 1.18f
        val stemBottom = cy - iconR * 0.08f

        // Subtle shadow pass (1dp down).
        val shadowDy = 1.1f * density
        canvas.save()
        canvas.translate(0f, shadowDy)
        canvas.drawArc(iconArcRect, -50f, 280f, false, iconShadowPaint)
        canvas.drawLine(cx, stemTop, cx, stemBottom, iconShadowPaint)
        canvas.restore()

        // Main power glyph (pulses slightly in brightness during connecting).
        iconPaint.color = curIconTint
        iconPaint.alpha = if (state == State.CONNECTING) {
            (190 + (65 * breath)).toInt().coerceIn(0, 255)
        } else {
            Color.alpha(curIconTint)
        }
        canvas.drawArc(iconArcRect, -50f, 280f, false, iconPaint)
        canvas.drawLine(cx, stemTop, cx, stemBottom, iconPaint)

        canvas.restore()
    }

    private fun drawRadarWave(canvas: Canvas, progress: Float, color: Int, baseStroke: Float) {
        val r = coreRadius + (maxRadius - coreRadius) * progress
        val fade = (1f - progress) * (1f - progress)
        val alpha = (fade * 210f).toInt().coerceIn(0, 255)
        ripplePaint.color = withAlpha(color, alpha)
        ripplePaint.strokeWidth = baseStroke * (1f - 0.55f * progress)
        canvas.drawCircle(cx, cy, r, ripplePaint)
    }

    private fun syncPhaseAnimator() {
        val shouldRun = attached &&
            animationsAllowed &&
            visibility == VISIBLE &&
            currentState != State.DISABLED_TESTING
        if (shouldRun) {
            if (!phaseAnimator.isStarted) {
                phaseAnimator.start()
            } else if (phaseAnimator.isPaused) {
                phaseAnimator.resume()
            }
        } else {
            if (phaseAnimator.isRunning) {
                phaseAnimator.pause()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attached = true
        syncPhaseAnimator()
    }

    override fun onDetachedFromWindow() {
        attached = false
        phaseAnimator.cancel()
        colorAnimator?.cancel()
        burstAnimator?.cancel()
        popAnimator?.cancel()
        pressAnimator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncPhaseAnimator()
    }

    private fun withAlpha(color: Int, alpha: Int): Int {
        return (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)
    }

    private fun blendWithWhite(color: Int, ratio: Float): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(color) + (255 - Color.green(color)) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) + (255 - Color.blue(color)) * ratio).toInt().coerceIn(0, 255)
        return Color.argb(255, r, g, b)
    }
}
