package com.pocketlinux.de.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import com.pocketlinux.de.vnc.InputEncoder
import kotlin.math.min

/**
 * Renders a VNC framebuffer Bitmap, with pinch-to-zoom and pan, and translates
 * touch input to VNC pointer events.
 *
 * Two coordinate systems live here:
 *   - View space: pixels of this Android View
 *   - Framebuffer space: pixels of the remote desktop (sent to the VNC server)
 *
 * A 3x3 [Matrix] maps view -> framebuffer for input, and we use its inverse
 * (computed by Canvas.concat) to draw the bitmap scaled/translated for output.
 *
 * We default to "fit to screen" on first frame — most phone screens are
 * narrower than a typical desktop resolution, so the user pinches to zoom in
 * for fine control.
 */
class VncCanvasView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    /** Set by the activity once the RfbClient has produced the framebuffer. */
    var framebuffer: Bitmap? = null
        set(value) {
            field = value
            value?.let {
                if (!hasFitInitial) {
                    fitToView(it.width, it.height)
                    hasFitInitial = true
                }
                postInvalidate()
            }
        }

    /** Set by the activity. The view will call this with framebuffer-space coords. */
    var pointerListener: ((buttonMask: Int, x: Int, y: Int) -> Unit)? = null

    private val drawMatrix = Matrix()
    private val invMatrix = Matrix()
    private val paint = Paint().apply {
        isFilterBitmap = true
        isAntiAlias = false
    }

    private var hasFitInitial = false
    private var scale = 1.0f
    private var transX = 0.0f
    private var transY = 0.0f

    private var lastButtonMask = 0

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val factor = d.scaleFactor
            // Scale around the gesture focus, not the origin
            transX = d.focusX - (d.focusX - transX) * factor
            transY = d.focusY - (d.focusY - transY) * factor
            scale = (scale * factor).coerceIn(0.25f, 8.0f)
            updateMatrix()
            invalidate()
            return true
        }
    })

    private val panDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            // Only pan if there's more than one pointer (two-finger drag = pan).
            // A single-finger drag is used for pointer movement instead.
            if (e2.pointerCount >= 2) {
                transX -= dx
                transY -= dy
                updateMatrix()
                invalidate()
                return true
            }
            return false
        }
    })

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    // === Soft keyboard wiring =================================================
    //
    // Returning true here tells Android "yes, I want a soft keyboard". The
    // system then calls onCreateInputConnection to get our IME bridge.
    override fun onCheckIsTextEditor(): Boolean = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        VncInputConnection.configureEditorInfo(outAttrs)
        return VncInputConnection(this, fullEditor = false)
    }

    private fun fitToView(fbW: Int, fbH: Int) {
        if (width == 0 || height == 0) return
        val sx = width.toFloat() / fbW
        val sy = height.toFloat() / fbH
        scale = min(sx, sy)
        transX = (width - fbW * scale) / 2f
        transY = (height - fbH * scale) / 2f
        updateMatrix()
    }

    private fun updateMatrix() {
        drawMatrix.reset()
        drawMatrix.postScale(scale, scale)
        drawMatrix.postTranslate(transX, transY)
        drawMatrix.invert(invMatrix)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        framebuffer?.let { fitToView(it.width, it.height); hasFitInitial = true }
    }

    override fun onDraw(canvas: Canvas) {
        val fb = framebuffer ?: return
        canvas.save()
        canvas.concat(drawMatrix)
        canvas.drawBitmap(fb, 0f, 0f, paint)
        canvas.restore()
    }

    /**
     * Map view coords back to framebuffer coords using the inverse matrix.
     */
    private fun mapToFb(x: Float, y: Float): Pair<Int, Int>? {
        val fb = framebuffer ?: return null
        val pt = floatArrayOf(x, y)
        invMatrix.mapPoints(pt)
        val fx = pt[0].toInt().coerceIn(0, fb.width - 1)
        val fy = pt[1].toInt().coerceIn(0, fb.height - 1)
        return fx to fy
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        panDetector.onTouchEvent(event)

        // If a multi-touch gesture is happening, suppress single-finger pointer logic
        if (event.pointerCount >= 2 || scaleDetector.isInProgress) {
            // Release any held button so the desktop doesn't see a stuck drag
            if (lastButtonMask != 0) {
                val (fx, fy) = mapToFb(event.x, event.y) ?: return true
                pointerListener?.invoke(0, fx, fy)
                lastButtonMask = 0
            }
            return true
        }

        val (fx, fy) = mapToFb(event.x, event.y) ?: return true

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                val mask = InputEncoder.Buttons.LEFT
                pointerListener?.invoke(mask, fx, fy)
                lastButtonMask = mask
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pointerListener?.invoke(0, fx, fy)
                lastButtonMask = 0
            }
        }
        return true
    }

    /**
     * Called by the activity when the framebuffer has changed in a region.
     * We invalidate just that view-space rect to keep redraws cheap.
     */
    fun invalidateFbRect(x: Int, y: Int, w: Int, h: Int) {
        val pts = floatArrayOf(
            x.toFloat(), y.toFloat(),
            (x + w).toFloat(), (y + h).toFloat()
        )
        drawMatrix.mapPoints(pts)
        val r = Rect(
            pts[0].toInt() - 1, pts[1].toInt() - 1,
            pts[2].toInt() + 1, pts[3].toInt() + 1
        )
        invalidate(r)
    }
}
