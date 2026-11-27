package com.pizarra.dev.ui

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextUtils
import android.text.TextWatcher
import android.text.style.TabStopSpan
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlin.math.max
import kotlin.math.min

/**
 * Un bloque del lienzo (título, subtítulo o nota). La tarjeta se dibuja dentro de un margen (pad)
 * donde viven los 4 anclajes, así todo el bloque recibe toques sin salirse de sus límites.
 * Posición: wx,wy = esquina de la tarjeta en coordenadas de mundo. Es el BoardView quien lo traslada/escala.
 *
 * Bloque Nota (tipo 2):
 *  - Siempre muestra un campo de TÍTULO editable (una línea) en la parte superior.
 *  - Colapsado (por defecto al cargar): muestra título + vista previa del cuerpo (2 líneas).
 *  - Una flechita (▼/▲) en la cabecera expande/colapsa la interfaz completa de escritura
 *    (título + cuerpo editable con scroll y barra de herramientas).
 *  - Modo normal al principio. Solo si el usuario toca "+ Script" en la barra de la nota
 *    expandida, ESE bloque cambia a modo script (monoespaciado, sin wrap, TAB y auto-indent).
 *    Con "Aa Texto" vuelve a modo normal. El título nunca cambia de modo.
 */
class BlockView(
    ctx: Context,
    val bid: Long,
    val type: Int,
    var wx: Float,
    var wy: Float,
    var cardW: Int,
    text: String,
    var code: Boolean,
    title: String = "",
    expanded: Boolean = false,
    script: String = "",
    hasScript: Boolean = false
) : FrameLayout(ctx) {

    interface Host {
        fun scale(): Float
        fun onMoved(b: BlockView)
        fun onMoveEnd(b: BlockView)
        fun onResizeEnd(b: BlockView)
        fun onDelete(b: BlockView)
        fun onText(b: BlockView, t: String)
        fun onTitle(b: BlockView, t: String)
        fun onScript(b: BlockView, script: String, has: Boolean)
        fun onCode(b: BlockView)
        fun onFocus(b: BlockView)
        fun onExpand(b: BlockView, expanded: Boolean)
        fun onLinkStart(b: BlockView, side: Int)
        fun onLinkMove(x: Float, y: Float)
        fun onLinkEnd(x: Float, y: Float, commit: Boolean)
    }

    companion object {
        const val TITLE = 0
        const val SUBTITLE = 1
        const val NOTE = 2
    }

    var host: Host? = null
    val pad = dp(16)
    val edit = EditText(ctx)
    val titleEdit = EditText(ctx)

    /** true = interfaz completa visible; false = solo título + vista previa. */
    var expanded: Boolean = expanded
        private set

    private val rad = dpf(10f)
    private val body = FrameLayout(ctx)
    private val vScroll = object : ScrollView(ctx) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val limit = dp(380)
            val mode = View.MeasureSpec.getMode(heightMeasureSpec)
            val size = View.MeasureSpec.getSize(heightMeasureSpec)
            val capped = if (mode == View.MeasureSpec.UNSPECIFIED) limit else min(size, limit)
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(capped, View.MeasureSpec.AT_MOST))
        }
    }
    private val hScroll = HorizontalScrollView(ctx)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dpf(1.5f) }
    private val dotFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.BG }
    private val dotLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Ui.ACCENT; strokeWidth = dpf(1.5f) }

    private var tab: TabStopSpan.Standard? = null
    private var busy = false
    private var nl = -1
    private var bodyDirty = false
    private var titleDirty = false
    private var scriptDirty = false
    private var hasScript = hasScript
    private val scriptEdit = EditText(ctx)
    private val scriptBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private var linking = false
    private var lrx = 0f
    private var lry = 0f

    private val preview = TextView(ctx)
    private val expandedBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val btnTab = tv("TAB") { insertTab() }
    private val btnScript = tv("+ Script") { addScript() }
    private val btnFull = tv("Expandir completamente") { openFullEditor() }
    private val btnArrow = tv("▼") { toggleExpand() }
    private val btnDel = tv("×") { host?.onDelete(this) }
    private val grab = TextView(ctx)
    private val saveRun = Runnable { flush() }

    private val watcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {
            nl = if (type == NOTE && code && !busy && before == 0 && count == 1 && s != null && s[st] == '\n') st else -1
        }
        override fun afterTextChanged(e: Editable) {
            if (busy) return
            busy = true
            if (nl >= 0) autoIndent(e, nl)
            nl = -1
            ensureTabs(e)
            busy = false
            bodyDirty = true
            updatePreview()
            removeCallbacks(saveRun)
            postDelayed(saveRun, 700)
        }
    }

    private val titleWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {}
        override fun afterTextChanged(e: Editable) {
            titleDirty = true
            removeCallbacks(saveRun)
            postDelayed(saveRun, 700)
        }
    }

    init {
        setWillNotDraw(false)
        setPadding(pad, pad, pad, pad)

        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        grab.apply {
            this.text = "≡"
            setTextColor(Ui.DIM)
            textSize = 16f
            gravity = Gravity.CENTER
        }
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(grab, LinearLayout.LayoutParams(0, dp(28), 1f))
        if (type == NOTE) {
            header.addView(btnArrow, LinearLayout.LayoutParams(WRAP, dp(28)))
        }
        header.addView(btnDel, LinearLayout.LayoutParams(WRAP, dp(28)))

        col.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        if (type == NOTE) {
            // --- Título (siempre visible y editable, en ambos estados) ---
            titleEdit.apply {
                background = null
                setPadding(dp(10), dp(4), dp(10), dp(4))
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                minHeight = dp(36)
                textSize = 16f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Ui.TEXT)
                setHintTextColor(Ui.DIM)
                hint = "Título de la nota…"
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setSingleLine(true)
                maxLines = 1
                setText(title)
                setOnFocusChangeListener { _, f -> this@BlockView.invalidate(); if (f) host?.onFocus(this@BlockView) }
                setOnEditorActionListener { _, _, _ ->
                    // Enter en el título -> saltar al cuerpo (expandiendo si hace falta)
                    if (!expanded) toggleExpand() else edit.requestFocus()
                    true
                }
            }
            titleEdit.addTextChangedListener(titleWatcher)
            col.addView(titleEdit, LinearLayout.LayoutParams(MATCH, WRAP))

            // --- Vista previa (solo colapsado) ---
            preview.apply {
                setPadding(dp(10), dp(2), dp(10), dp(8))
                textSize = 13f
                setTextColor(Ui.DIM)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setOnClickListener { toggleExpand() }
            }
            col.addView(preview, LinearLayout.LayoutParams(MATCH, WRAP))

            // --- Interfaz completa de escritura (solo expandido) ---
            val toolbar = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            toolbar.addView(btnScript, LinearLayout.LayoutParams(WRAP, dp(32)))
            toolbar.addView(btnFull, LinearLayout.LayoutParams(WRAP, dp(32)))
            expandedBox.addView(toolbar, LinearLayout.LayoutParams(MATCH, WRAP))
            expandedBox.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
            val scriptHeader = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            scriptHeader.addView(tv("Script") {}, LinearLayout.LayoutParams(0, dp(36), 1f))
            scriptHeader.addView(tv("Copiar") { copyScript() }, LinearLayout.LayoutParams(WRAP, dp(36)))
            scriptBox.addView(scriptHeader)
            scriptEdit.apply {
                setText(script); hint = "Escribe tu script…"; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM)
                typeface = Typeface.MONOSPACE; textSize = 13f; minHeight = dp(90)
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(e: Editable) { scriptDirty = true; removeCallbacks(saveRun); postDelayed(saveRun, 700) }
                })
            }
            scriptBox.addView(scriptEdit, LinearLayout.LayoutParams(MATCH, WRAP))
            scriptBox.visibility = if (hasScript) View.VISIBLE else View.GONE
            expandedBox.addView(scriptBox, LinearLayout.LayoutParams(MATCH, WRAP))
            col.addView(expandedBox, LinearLayout.LayoutParams(MATCH, WRAP))
        } else {
            col.addView(body, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        addView(Grip(ctx), FrameLayout.LayoutParams(dp(20), dp(20), Gravity.END or Gravity.BOTTOM))

        val initial = text
        edit.apply {
            background = null
            setPadding(dp(10), dp(2), dp(10), dp(10))
            gravity = Gravity.TOP or Gravity.START
            minHeight = dp(if (type == NOTE) 120 else 44)
            setHintTextColor(Ui.DIM)
            setText(initial)
            setOnFocusChangeListener { _, f -> this@BlockView.invalidate(); if (f) host?.onFocus(this@BlockView) }
            setOnKeyListener { _, k, ev ->
                if (code && k == KeyEvent.KEYCODE_TAB && ev.action == KeyEvent.ACTION_DOWN) { insertTab(); true } else false
            }
        }
        code = false // Los textos antiguos se conservan como texto normal.
        applyMode()
        applyExpanded()
        edit.addTextChangedListener(watcher)

        // Arrastrar el bloque desde la barra superior (≡)
        grab.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lrx = e.rawX; lry = e.rawY
                    parent?.requestDisallowInterceptTouchEvent(true)
                    bringToFront()
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = host?.scale() ?: 1f
                    wx += (e.rawX - lrx) / s
                    wy += (e.rawY - lry) / s
                    lrx = e.rawX; lry = e.rawY
                    host?.onMoved(this)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> host?.onMoveEnd(this)
            }
            true
        }
    }

    private fun tv(t: String, f: () -> Unit) = TextView(context).apply {
        this.text = t
        setTextColor(Ui.ACCENT)
        textSize = 12f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { f() }
    }

    // ---------------- expandir / colapsar ----------------

    fun toggleExpand() {
        if (type != NOTE) return
        expanded = !expanded
        applyExpanded()
        if (expanded) {
            bringToFront()
            host?.onExpand(this, true)
            // Foco: título si está vacío, si no el cuerpo. Teclado visible.
            post {
                if (titleEdit.text.isBlank()) {
                    titleEdit.requestFocus()
                    titleEdit.setSelection(titleEdit.text.length)
                } else {
                    edit.requestFocus()
                }
                showKeyboard()
            }
        } else {
            flush()
            edit.clearFocus()
            titleEdit.clearFocus()
            hideKeyboard()
            host?.onExpand(this, false)
        }
    }

    private fun applyExpanded() {
        if (type != NOTE) return
        preview.visibility = if (expanded) View.GONE else View.VISIBLE
        expandedBox.visibility = if (expanded) View.VISIBLE else View.GONE
        btnArrow.text = if (expanded) "▲" else "▼"
        btnArrow.contentDescription = if (expanded) "Contraer nota" else "Expandir nota"
        updatePreview()
        requestLayout()
        invalidate()
    }

    private fun updatePreview() {
        if (type != NOTE) return
        val bodyText = edit.text.toString()
        preview.text = when {
            bodyText.isBlank() -> if (code) "Script vacío — toca ▼ para escribir" else "Sin contenido — toca ▼ para escribir"
            code -> "</> $bodyText"
            else -> bodyText
        }
    }

    /** Foco inicial al crear una nota nueva: título si está vacío, si no el cuerpo. */
    fun focusTitleOrBody() {
        if (type != NOTE) {
            edit.requestFocus()
            return
        }
        if (titleEdit.text.isBlank()) {
            titleEdit.requestFocus()
            titleEdit.setSelection(titleEdit.text.length)
        } else {
            edit.requestFocus()
        }
        showKeyboard()
    }

    private fun showKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        val target = if (titleEdit.hasFocus()) titleEdit else edit
        imm.showSoftInput(target, 0)
    }

    private fun hideKeyboard() {
        val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
        imm.hideSoftInputFromWindow(windowToken, 0)
    }

    // ---------------- modo normal / script ----------------

    /**
     * Solo cambia el modo de ESTE bloque y solo desde su interfaz expandida:
     * "+ Script" -> modo script; "Aa Texto" -> vuelve a modo normal.
     * Al principio todo bloque nace en modo normal (code = false).
     */
    private fun addScript() {
        if (!hasScript) {
            hasScript = true
            scriptDirty = true
            scriptBox.visibility = View.VISIBLE
            btnScript.text = "Script añadido"
            flush()
        }
        scriptEdit.requestFocus()
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(scriptEdit, 0)
    }

    private fun copyScript() {
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
            .setPrimaryClip(ClipData.newPlainText("Script", scriptEdit.text.toString()))
        Toast.makeText(context, "Script copiado", Toast.LENGTH_SHORT).show()
    }

    private fun openFullEditor() {
        val container = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(12), dp(16), dp(12)); setBackgroundColor(Ui.SURFACE) }
        fun field(hintText: String, initial: String, mono: Boolean, changed: (String) -> Unit): EditText {
            return EditText(context).apply {
                hint = hintText; setTextColor(Ui.TEXT); setHintTextColor(Ui.DIM); setText(initial)
                if (mono) typeface = Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, st: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, st: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(e: Editable) { changed(e.toString()) }
                })
            }
        }
        val fullTitle = field("Título", titleEdit.text.toString(), false) { if (titleEdit.text.toString() != it) titleEdit.setText(it) }
        fullTitle.setSingleLine(true)
        container.addView(fullTitle, LinearLayout.LayoutParams(MATCH, WRAP))
        val fullBody = field("Escribe una nota…", edit.text.toString(), false) { if (edit.text.toString() != it) edit.setText(it) }
        container.addView(fullBody, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val scriptArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val fullScript = field("Escribe tu script…", scriptEdit.text.toString(), true) { if (scriptEdit.text.toString() != it) scriptEdit.setText(it) }
        scriptArea.addView(tv("Copiar script") { copyScript() }, LinearLayout.LayoutParams(MATCH, dp(40)))
        scriptArea.addView(fullScript, LinearLayout.LayoutParams(MATCH, 0, 1f))
        scriptArea.visibility = if (hasScript) View.VISIBLE else View.GONE
        container.addView(tv("+ Agregar script") {
            if (!hasScript) { hasScript = true; scriptDirty = true; scriptBox.visibility = View.VISIBLE; btnScript.text = "Script añadido"; flush() }
            scriptArea.visibility = View.VISIBLE; fullScript.requestFocus()
        }, LinearLayout.LayoutParams(MATCH, dp(40)))
        container.addView(scriptArea, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val dialog = AlertDialog.Builder(context).setTitle("Nota completa").setView(container).setPositiveButton("Listo") { _, _ -> flush() }.create()
        dialog.setOnDismissListener { flush() }
        dialog.setOnShowListener { dialog.window?.setLayout((resources.displayMetrics.widthPixels * 0.96f).toInt(), (resources.displayMetrics.heightPixels * 0.90f).toInt()) }
        dialog.show()
    }

    /** Alterna entre modo texto (word wrap) y modo script (monoespaciado, sin wrap, scroll horizontal). */
    private fun applyMode() {
        val isCode = type == NOTE && code
        val selStart = try { edit.selectionStart } catch (_: Exception) { -1 }
        val selEnd = try { edit.selectionEnd } catch (_: Exception) { -1 }
        (edit.parent as? ViewGroup)?.removeView(edit)
        vScroll.removeAllViews()
        hScroll.removeAllViews()
        body.removeAllViews()
        tab?.let { edit.text.removeSpan(it) }
        tab = null

        edit.setTextColor(Ui.TEXT)
        if (isCode) {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            edit.setHorizontallyScrolling(true)      // siempre DESPUÉS de inputType
            edit.typeface = Typeface.MONOSPACE
            edit.textSize = 13f
            edit.hint = "// script"
            tab = TabStopSpan.Standard(max(1, (edit.paint.measureText("0") * 4).toInt()))
            // Script: scroll vertical (líneas) + horizontal (líneas largas)
            vScroll.isFillViewport = true
            vScroll.isVerticalScrollBarEnabled = false
            hScroll.isFillViewport = true
            hScroll.isHorizontalScrollBarEnabled = false
            hScroll.addView(edit, FrameLayout.LayoutParams(WRAP, WRAP))
            vScroll.addView(hScroll, FrameLayout.LayoutParams(MATCH, WRAP))
            body.addView(vScroll, FrameLayout.LayoutParams(MATCH, WRAP))
            ensureTabs(edit.text)
        } else {
            edit.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            edit.setHorizontallyScrolling(false)
            edit.typeface = if (type == NOTE) Typeface.DEFAULT else Typeface.DEFAULT_BOLD
            edit.textSize = when (type) { TITLE -> 26f; SUBTITLE -> 19f; else -> 14f }
            edit.hint = when (type) { TITLE -> "Título"; SUBTITLE -> "Subtítulo"; else -> "Escribe una nota…" }
            if (type == NOTE) {
                // Nota expandida: cuerpo con scroll vertical y altura máxima (interfaz completa)
                vScroll.isFillViewport = true
                vScroll.isVerticalScrollBarEnabled = false
                vScroll.addView(edit, FrameLayout.LayoutParams(MATCH, WRAP))
                body.addView(vScroll, FrameLayout.LayoutParams(MATCH, WRAP))
            } else {
                body.addView(edit, FrameLayout.LayoutParams(MATCH, WRAP))
            }
        }
        edit.setTextColor(if (type == SUBTITLE) Ui.GREEN else Ui.TEXT)
        if (type == NOTE) {
            btnTab.visibility = if (isCode) View.VISIBLE else View.GONE
            btnScript.text = if (hasScript) "Script añadido" else "+ Script"
            btnScript.contentDescription = "Agregar espacio para script"
        }
        if (selStart >= 0 && selEnd >= 0) {
            try { edit.setSelection(selStart.coerceAtMost(edit.text.length), selEnd.coerceAtMost(edit.text.length)) } catch (_: Exception) {}
        }
        updatePreview()
        invalidate()
    }

    private fun ensureTabs(e: Editable) {
        val sp = tab ?: return
        if (e.getSpanStart(sp) != 0 || e.getSpanEnd(sp) != e.length) {
            e.setSpan(sp, 0, e.length, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
        }
    }

    private fun insertTab() {
        val a = max(0, min(edit.selectionStart, edit.selectionEnd))
        val b = max(0, max(edit.selectionStart, edit.selectionEnd))
        edit.text.replace(a, b, "\t")
        edit.setSelection(a + 1)
    }

    /** Mantiene la indentación de la línea anterior; añade un nivel tras { [ ( : */
    private fun autoIndent(e: Editable, nlPos: Int) {
        var ls = nlPos
        while (ls > 0 && e[ls - 1] != '\n') ls--
        var i = ls
        while (i < nlPos && (e[i] == ' ' || e[i] == '\t')) i++
        var j = nlPos - 1
        while (j >= i && (e[j] == ' ' || e[j] == '\t')) j--
        val last = if (j >= i) e[j] else ' '
        val ins = e.subSequence(ls, i).toString() + (if (last in "{[(:") "\t" else "")
        if (ins.isNotEmpty()) {
            e.insert(nlPos + 1, ins)
            edit.setSelection(nlPos + 1 + ins.length)
        }
    }

    fun flush() {
        removeCallbacks(saveRun)
        if (bodyDirty) {
            bodyDirty = false
            host?.onText(this, edit.text.toString())
        }
        if (scriptDirty) {
            scriptDirty = false
            host?.onScript(this, scriptEdit.text.toString(), hasScript)
        }
        if (titleDirty) {
            titleDirty = false
            host?.onTitle(this, titleEdit.text.toString())
        }
    }

    /** true si título y cuerpo están vacíos (para decidir si borrar sin confirmar). */
    fun isBlank(): Boolean =
        if (type == NOTE) titleEdit.text.isBlank() && edit.text.isBlank() && scriptEdit.text.isBlank() else edit.text.isBlank()

    // ---- anclajes: 0 arriba, 1 derecha, 2 abajo, 3 izquierda ----
    private fun ax(side: Int): Float = when (side) { 1 -> (width - pad).toFloat(); 3 -> pad.toFloat(); else -> width / 2f }
    private fun ay(side: Int): Float = when (side) { 0 -> pad.toFloat(); 2 -> (height - pad).toFloat(); else -> height / 2f }

    private fun anchorAt(x: Float, y: Float): Int {
        val r = dpf(14f)
        for (k in 0..3) {
            val dx = x - ax(k)
            val dy = y - ay(k)
            if (dx * dx + dy * dy <= r * r) return k
        }
        return -1
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_DOWN) {
            val side = anchorAt(e.x, e.y)
            if (side >= 0) {
                linking = true
                parent?.requestDisallowInterceptTouchEvent(true)
                host?.onLinkStart(this, side)
                return true
            }
        }
        return false
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!linking) return false
        val x = wx - pad + e.x
        val y = wy - pad + e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> host?.onLinkMove(x, y)
            MotionEvent.ACTION_UP -> { linking = false; host?.onLinkEnd(x, y, true) }
            MotionEvent.ACTION_CANCEL -> { linking = false; host?.onLinkEnd(x, y, false) }
        }
        return true
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        host?.onMoved(this)     // la altura cambia al escribir/expandir: redibuja las líneas
    }

    override fun onDraw(c: Canvas) {
        val l = pad.toFloat()
        val r = (width - pad).toFloat()
        val b = (height - pad).toFloat()
        fill.color = if (type == NOTE && code) Ui.CODE_BG else Ui.SURFACE
        line.color = when {
            edit.hasFocus() || titleEdit.hasFocus() -> Ui.FOCUS
            type == TITLE -> Ui.ACCENT
            type == SUBTITLE -> Ui.GREEN
            else -> Ui.STROKE
        }
        c.drawRoundRect(l, l, r, b, rad, rad, fill)
        c.drawRoundRect(l, l, r, b, rad, rad, line)
    }

    override fun dispatchDraw(c: Canvas) {
        super.dispatchDraw(c)
        val rd = dpf(5f)
        for (k in 0..3) {
            c.drawCircle(ax(k), ay(k), rd, dotFill)
            c.drawCircle(ax(k), ay(k), rd, dotLine)
        }
    }

    /** Asa inferior derecha: cambia el ancho de la tarjeta. */
    private inner class Grip(c: Context) : View(c) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Ui.DIM; strokeWidth = dpf(1.5f); strokeCap = Paint.Cap.ROUND
        }
        private var lx = 0f

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            c.drawLine(w * 0.85f, h * 0.35f, w * 0.35f, h * 0.85f, p)
            c.drawLine(w * 0.85f, h * 0.65f, w * 0.65f, h * 0.85f, p)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lx = e.rawX
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
                MotionEvent.ACTION_MOVE -> {
                    val s = host?.scale() ?: 1f
                    val nw = max(dp(140), cardW + ((e.rawX - lx) / s).toInt())
                    if (nw != cardW) {
                        cardW = nw
                        lx = e.rawX
                        val lp = this@BlockView.layoutParams
                        if (lp != null) {
                            lp.width = cardW + 2 * pad
                            this@BlockView.requestLayout()
                        }
                        host?.onMoved(this@BlockView)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> host?.onResizeEnd(this@BlockView)
            }
            return true
        }
    }
}
