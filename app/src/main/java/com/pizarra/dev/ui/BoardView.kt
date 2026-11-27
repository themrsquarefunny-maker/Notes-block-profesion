package com.pizarra.dev.ui

import android.app.AlertDialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import com.pizarra.dev.data.BlockEntity
import com.pizarra.dev.data.EdgeEntity
import com.pizarra.dev.data.Repo
import kotlin.math.hypot
import kotlin.math.max

/**
 * Lienzo infinito. No hay una vista gigante: cada bloque se coloca con translación+escala
 * (pivote 0,0) calculadas desde (s, tx, ty), así el espacio es ilimitado y el costo por frame
 * es proporcional al número de bloques. Fondo punteado con un solo BitmapShader.
 * Pan: 1 dedo sobre espacio vacío. Zoom/pan: 2 dedos en cualquier parte.
 */
class BoardView(ctx: Context, private val repo: Repo) : ViewGroup(ctx), BlockView.Host {

    private companion object {
        const val MIN = 0.2f
        const val MAX = 3f
        val DX = floatArrayOf(0f, 1f, 0f, -1f)
        val DY = floatArrayOf(-1f, 0f, 1f, 0f)
    }

    private var s = 1f
    private var tx = 0f
    private var ty = 0f
    private var inited = false
    private var restored = false

    private val dens = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val views = HashMap<Long, BlockView>()
    private val edges = ArrayList<EdgeEntity>()

    private val tmp = FloatArray(2)
    private val ctl = FloatArray(8)
    private val path = Path()
    private val m = Matrix()
    private val edgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = Ui.ACCENT; strokeWidth = 2.5f * dens; strokeCap = Paint.Cap.ROUND
    }
    private val gridPaint = Paint()
    private val gridShader: BitmapShader

    // gesto
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false
    private var two = false
    private var lastDist = 0f
    private var lastFx = 0f
    private var lastFy = 0f

    // conexión en curso (coordenadas de mundo)
    private var linkFrom: BlockView? = null
    private var linkSide = 0
    private var linkX = 0f
    private var linkY = 0f

    init {
        setBackgroundColor(Ui.BG)
        isFocusable = true
        isFocusableInTouchMode = true
        val t = max(8, (26 * dens).toInt())
        val bmp = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.GRID }
        Canvas(bmp).drawCircle(t / 2f, t / 2f, 1.2f * dens, p)
        gridShader = BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
    }

    // ---------------- API pública ----------------

    fun setView(scale: Float, x: Float, y: Float) { s = scale.coerceIn(MIN, MAX); tx = x; ty = y; restored = true }
    fun viewState() = floatArrayOf(s, tx, ty)

    fun recenter() { s = 1f; tx = width / 2f; ty = height / 2f; applyAll() }

    fun load() {
        repo.load { bl, ed ->
            if (!isAttachedToWindow) return@load
            for (b in bl) createView(b)
            edges.addAll(ed)
            applyAll()
        }
    }

    fun addBlock(type: Int) {
        val w = dp(300)
        val jitter = (views.size % 6) * 14f * dens
        val x = (width / 2f - tx) / s - w / 2f + jitter
        val y = (height / 2f - ty) / s - 60f * dens + jitter
        // Las notas nuevas nacen en modo normal (code = false) y expandidas,
        // para que el usuario escriba el título y el cuerpo de inmediato.
        val e = BlockEntity(type = type, x = x, y = y, w = w, code = false)
        repo.addBlock(e) { id ->
            if (!isAttachedToWindow) return@addBlock
            val v = createView(e.copy(id = id), expanded = type == BlockView.NOTE)
            applyAll()
            v.bringToFront()
            v.postDelayed({ v.focusTitleOrBody() }, 150)
        }
    }

    fun flush() { for (v in views.values) v.flush() }

    // ---------------- modelo ----------------

    private fun createView(e: BlockEntity, expanded: Boolean = false): BlockView {
        val v = BlockView(context, e.id, e.type, e.x, e.y, e.w, e.text, e.code, e.title, expanded, e.script, e.hasScript)
        v.host = this
        v.pivotX = 0f
        v.pivotY = 0f
        views[e.id] = v
        addView(v, ViewGroup.LayoutParams(e.w + 2 * v.pad, ViewGroup.LayoutParams.WRAP_CONTENT))
        place(v)
        return v
    }

    private fun place(v: BlockView) {
        v.translationX = tx + s * (v.wx - v.pad)
        v.translationY = ty + s * (v.wy - v.pad)
    }

    private fun applyAll() {
        for (v in views.values) {
            v.scaleX = s
            v.scaleY = s
            place(v)
        }
        invalidate()
    }

    private fun addEdge(a: BlockView, sa: Int, b: BlockView, sb: Int) {
        val dup = edges.any {
            (it.fromId == a.bid && it.toId == b.bid && it.fromSide == sa && it.toSide == sb) ||
                (it.fromId == b.bid && it.toId == a.bid && it.fromSide == sb && it.toSide == sa)
        }
        if (dup) return
        val e = EdgeEntity(fromId = a.bid, fromSide = sa, toId = b.bid, toSide = sb)
        repo.addEdge(e) { id -> edges.add(e.copy(id = id)); invalidate() }
    }

    private fun confirm(msg: String, ok: () -> Unit) {
        AlertDialog.Builder(context, android.R.style.Theme_Material_Dialog_Alert)
            .setMessage(msg)
            .setPositiveButton("Eliminar") { _, _ -> ok() }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    // ---------------- Host (eventos de los bloques) ----------------

    override fun scale() = s

    override fun onMoved(b: BlockView) { place(b); invalidate() }

    override fun onMoveEnd(b: BlockView) { repo.move(b.bid, b.wx, b.wy) }

    override fun onResizeEnd(b: BlockView) { repo.resize(b.bid, b.cardW) }

    override fun onText(b: BlockView, t: String) { repo.text(b.bid, t) }

    override fun onTitle(b: BlockView, t: String) { repo.title(b.bid, t) }

    override fun onCode(b: BlockView) { repo.code(b.bid, b.code) }

    override fun onScript(b: BlockView, script: String, has: Boolean) { repo.script(b.bid, script, has) }

    override fun onExpand(b: BlockView, expanded: Boolean) {
        b.bringToFront()
        if (expanded) postDelayed({ reveal(b) }, 350)
        invalidate()
    }

    override fun onDelete(b: BlockView) {
        val doIt = {
            b.flush()
            removeView(b)
            views.remove(b.bid)
            edges.removeAll { it.fromId == b.bid || it.toId == b.bid }
            repo.delBlock(b.bid)
            invalidate()
        }
        if (b.isBlank()) doIt() else confirm("¿Eliminar este bloque y sus conexiones?") { doIt() }
    }

    override fun onFocus(b: BlockView) { postDelayed({ reveal(b) }, 350) }

    /** Tras abrir el teclado, sube el bloque si quedó fuera de la mitad superior. */
    private fun reveal(b: BlockView) {
        if (!b.isAttachedToWindow) return
        val sy = ty + s * b.wy
        if (sy < 0f || sy > height * 0.5f) {
            ty += height * 0.12f - sy
            applyAll()
        }
    }

    override fun onLinkStart(b: BlockView, side: Int) {
        linkFrom = b
        linkSide = side
        anchor(b, side, tmp)
        linkX = tmp[0]
        linkY = tmp[1]
        invalidate()
    }

    override fun onLinkMove(x: Float, y: Float) { linkX = x; linkY = y; invalidate() }

    override fun onLinkEnd(x: Float, y: Float, commit: Boolean) {
        val a = linkFrom
        linkFrom = null
        if (commit && a != null) {
            for (v in views.values) {
                if (v === a) continue
                val h = (v.height - 2 * v.pad).toFloat()
                if (x >= v.wx - v.pad && x <= v.wx + v.cardW + v.pad && y >= v.wy - v.pad && y <= v.wy + h + v.pad) {
                    var best = 0
                    var bd = Float.MAX_VALUE
                    for (k in 0..3) {
                        anchor(v, k, tmp)
                        val d = (tmp[0] - x) * (tmp[0] - x) + (tmp[1] - y) * (tmp[1] - y)
                        if (d < bd) { bd = d; best = k }
                    }
                    addEdge(a, linkSide, v, best)
                    break
                }
            }
        }
        invalidate()
    }

    // ---------------- geometría de conexiones ----------------

    private fun anchor(b: BlockView, side: Int, o: FloatArray) {
        val h = (b.height - 2 * b.pad).toFloat()
        val w = b.cardW.toFloat()
        when (side) {
            0 -> { o[0] = b.wx + w / 2; o[1] = b.wy }
            1 -> { o[0] = b.wx + w; o[1] = b.wy + h / 2 }
            2 -> { o[0] = b.wx + w / 2; o[1] = b.wy + h }
            else -> { o[0] = b.wx; o[1] = b.wy + h / 2 }
        }
    }

    /** Puntos de control de la curva de Bézier de una conexión. */
    private fun ctrl(e: EdgeEntity, o: FloatArray): Boolean {
        val a = views[e.fromId] ?: return false
        val b = views[e.toId] ?: return false
        anchor(a, e.fromSide, tmp)
        val x0 = tmp[0]
        val y0 = tmp[1]
        anchor(b, e.toSide, tmp)
        val x3 = tmp[0]
        val y3 = tmp[1]
        val k = max(60f * dens, hypot(x3 - x0, y3 - y0) * 0.4f)
        o[0] = x0; o[1] = y0
        o[2] = x0 + DX[e.fromSide] * k; o[3] = y0 + DY[e.fromSide] * k
        o[4] = x3 + DX[e.toSide] * k; o[5] = y3 + DY[e.toSide] * k
        o[6] = x3; o[7] = y3
        return true
    }

    private fun edgeAt(x: Float, y: Float): EdgeEntity? {
        val lim = 26f * dens / s
        val lim2 = lim * lim
        for (e in edges) {
            if (!ctrl(e, ctl)) continue
            for (i in 0..24) {
                val t = i / 24f
                val u = 1 - t
                val a = u * u * u
                val b = 3 * u * u * t
                val c = 3 * u * t * t
                val d = t * t * t
                val px = a * ctl[0] + b * ctl[2] + c * ctl[4] + d * ctl[6]
                val py = a * ctl[1] + b * ctl[3] + c * ctl[5] + d * ctl[7]
                val dx = px - x
                val dy = py - y
                if (dx * dx + dy * dy < lim2) return e
            }
        }
        return null
    }

    // ---------------- medida / layout / dibujo ----------------

    override fun onMeasure(wSpec: Int, hSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(wSpec), MeasureSpec.getSize(hSpec))
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.measure(
                MeasureSpec.makeMeasureSpec(c.layoutParams.width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            c.layout(0, 0, c.measuredWidth, c.measuredHeight)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        if (!inited) {
            if (!restored) { tx = w / 2f; ty = h / 2f }
            inited = true
            applyAll()
        }
    }

    override fun dispatchDraw(c: Canvas) {
        if (s >= 0.45f) {
            m.setScale(s, s)
            m.postTranslate(tx, ty)
            gridShader.setLocalMatrix(m)
            gridPaint.shader = gridShader      // reasignar para que el cambio de matriz surta efecto
            c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), gridPaint)
        }
        c.save()
        c.translate(tx, ty)
        c.scale(s, s)
        for (e in edges) {
            if (!ctrl(e, ctl)) continue
            path.rewind()
            path.moveTo(ctl[0], ctl[1])
            path.cubicTo(ctl[2], ctl[3], ctl[4], ctl[5], ctl[6], ctl[7])
            c.drawPath(path, edgePaint)
        }
        val a = linkFrom
        if (a != null) {
            anchor(a, linkSide, tmp)
            val x0 = tmp[0]
            val y0 = tmp[1]
            val k = 60f * dens
            path.rewind()
            path.moveTo(x0, y0)
            path.cubicTo(x0 + DX[linkSide] * k, y0 + DY[linkSide] * k, linkX, linkY, linkX, linkY)
            edgePaint.alpha = 130
            c.drawPath(path, edgePaint)
            edgePaint.alpha = 255
        }
        c.restore()
        super.dispatchDraw(c)
    }

    // ---------------- gestos del lienzo ----------------

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_POINTER_DOWN && e.pointerCount == 2) {
            beginTwo(e)
            return true
        }
        return false
    }

    private fun dist(e: MotionEvent) = hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))

    private fun beginTwo(e: MotionEvent) {
        two = true
        lastDist = dist(e)
        lastFx = (e.getX(0) + e.getX(1)) / 2f
        lastFy = (e.getY(0) + e.getY(1)) / 2f
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; lastX = e.x; lastY = e.y
                moved = false; two = false
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (e.pointerCount == 2) beginTwo(e)
            MotionEvent.ACTION_MOVE -> {
                if (two) {
                    if (e.pointerCount >= 2) {
                        val d = dist(e)
                        val fx = (e.getX(0) + e.getX(1)) / 2f
                        val fy = (e.getY(0) + e.getY(1)) / 2f
                        if (lastDist > 0f) {
                            val ns = (s * d / lastDist).coerceIn(MIN, MAX)
                            val wxp = (lastFx - tx) / s
                            val wyp = (lastFy - ty) / s
                            s = ns
                            tx = fx - wxp * s
                            ty = fy - wyp * s
                            applyAll()
                        }
                        lastDist = d; lastFx = fx; lastFy = fy
                    }
                } else {
                    if (!moved && hypot(e.x - downX, e.y - downY) > slop) moved = true
                    if (moved) {
                        tx += e.x - lastX
                        ty += e.y - lastY
                        applyAll()
                    }
                    lastX = e.x; lastY = e.y
                }
            }
            MotionEvent.ACTION_POINTER_UP -> if (two) {
                two = false
                val i = if (e.actionIndex == 0) 1 else 0
                lastX = e.getX(i); lastY = e.getY(i)
                moved = true
            }
            MotionEvent.ACTION_UP -> {
                if (!two && !moved) tap(e.x, e.y)
                two = false
            }
            MotionEvent.ACTION_CANCEL -> two = false
        }
        return true
    }

    /** Toque en espacio vacío: borra una conexión si se tocó una línea; si no, quita el foco/teclado. */
    private fun tap(x: Float, y: Float) {
        val ed = edgeAt((x - tx) / s, (y - ty) / s)
        if (ed != null) {
            confirm("¿Eliminar esta conexión?") {
                edges.remove(ed)
                repo.delEdge(ed.id)
                invalidate()
            }
            return
        }
        requestFocus()
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(windowToken, 0)
    }
}
