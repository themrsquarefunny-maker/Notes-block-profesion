package com.pizarra.dev

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.ScrollView
import android.widget.TextView
import com.pizarra.dev.data.Repo
import com.pizarra.dev.ui.BlockView
import com.pizarra.dev.ui.BoardView
import com.pizarra.dev.ui.MATCH
import com.pizarra.dev.ui.Ui
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {
    private lateinit var board: BoardView
    private lateinit var root: FrameLayout
    private lateinit var drawer: LinearLayout
    private lateinit var sessions: JSONArray
    private var current = "pizarra.db"
    private val d get() = resources.displayMetrics.density
    private fun dp(n: Int) = (n * d).toInt()
    private val prefs by lazy { getSharedPreferences("sessions", MODE_PRIVATE) }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        sessions = try { JSONArray(prefs.getString("list", null) ?: "[]") } catch (_: Exception) { JSONArray() }
        if (sessions.length() == 0) sessions.put(JSONObject().put("id", "pizarra.db").put("name", "Sesión principal"))
        current = prefs.getString("current", "pizarra.db") ?: "pizarra.db"
        if ((0 until sessions.length()).none { sessions.getJSONObject(it).getString("id") == current }) current = sessions.getJSONObject(0).getString("id")
        root = FrameLayout(this)
        board = BoardView(this, Repo(this, current))
        root.addView(board, FrameLayout.LayoutParams(MATCH, MATCH))
        val fab = button("+", 30f).apply {
            setTextColor(Color.parseColor("#0E1013")); elevation = dp(8).toFloat()
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
        }
        fab.setOnClickListener {
            PopupMenu(this, fab).apply {
                menu.add(0, BlockView.TITLE, 0, "Título"); menu.add(0, BlockView.SUBTITLE, 1, "Subtítulo")
                menu.add(0, BlockView.NOTE, 2, "Nota"); menu.add(0, 9, 3, "Centrar vista")
                setOnMenuItemClickListener { if (it.itemId == 9) board.recenter() else board.addBlock(it.itemId); true }; show()
            }
        }
        root.addView(fab, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.END or Gravity.BOTTOM).apply { setMargins(0, 0, dp(20), dp(24)) })
        val menu = button("☰", 24f).apply { setBackgroundColor(Ui.SURFACE); contentDescription = "Abrir sesiones" }
        root.addView(menu, FrameLayout.LayoutParams(dp(52), dp(48), Gravity.START or Gravity.TOP).apply { setMargins(dp(12), dp(12), 0, 0) })
        drawer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(16)); setBackgroundColor(Ui.SURFACE); elevation = dp(16).toFloat(); visibility = View.GONE }
        root.addView(drawer, FrameLayout.LayoutParams(dp(285), MATCH, Gravity.START))
        menu.setOnClickListener { renderDrawer(); drawer.visibility = View.VISIBLE }
        setContentView(root)
        restoreView(); board.load(); saveSessions()
    }
    private fun button(label: String, size: Float = 16f) = TextView(this).apply {
        text = label; textSize = size; setTextColor(Ui.TEXT); gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(12), dp(12))
    }
    private fun saveSessions() { prefs.edit().putString("list", sessions.toString()).putString("current", current).apply() }
    private fun currentName(): String = (0 until sessions.length()).map { sessions.getJSONObject(it) }.first { it.getString("id") == current }.getString("name")
    private fun renderDrawer() {
        drawer.removeAllViews()
        fun item(label: String, action: () -> Unit) { drawer.addView(button(label).apply { setOnClickListener { action() } }) }
        item("☰   Sesiones  ✕") { drawer.visibility = View.GONE }
        item("＋  Agregar sesión") { askName("Agregar sesión", "") { name ->
            val id = "session-${UUID.randomUUID()}.db"
            sessions.put(JSONObject().put("id", id).put("name", name)); switchTo(id)
        } }
        drawer.addView(button("SESIONES", 13f).apply { setTextColor(Ui.DIM) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (i in 0 until sessions.length()) {
            val session = sessions.getJSONObject(i); val id = session.getString("id")
            list.addView(button((if (id == current) "●  " else "○  ") + session.getString("name")).apply { setOnClickListener { switchTo(id) } })
        }
        drawer.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        item("✎  Renombrar sesión") { askName("Renombrar sesión", currentName()) { name ->
            for (i in 0 until sessions.length()) if (sessions.getJSONObject(i).getString("id") == current) sessions.getJSONObject(i).put("name", name)
            saveSessions(); renderDrawer()
        } }
        item("×  Eliminar sesión") {
            AlertDialog.Builder(this).setTitle("Eliminar sesión").setMessage("¿Eliminar «${currentName()}» y todos sus bloques? Esta acción no se puede deshacer.")
                .setNegativeButton("Cancelar", null).setPositiveButton("Eliminar") { _, _ ->
                    board.flush(); Repo(this, current).clear()
                    val remaining = JSONArray(); for (i in 0 until sessions.length()) if (sessions.getJSONObject(i).getString("id") != current) remaining.put(sessions.getJSONObject(i))
                    sessions = remaining
                    if (sessions.length() == 0) sessions.put(JSONObject().put("id", "session-${UUID.randomUUID()}.db").put("name", "Sesión nueva"))
                    switchTo(sessions.getJSONObject(0).getString("id"), false)
                }.show()
        }
    }
    private fun askName(title: String, initial: String, done: (String) -> Unit) {
        val input = EditText(this).apply { setText(initial); setSingleLine(true); hint = "Nombre de la sesión" }
        val dialog = AlertDialog.Builder(this).setTitle(title).setView(input).setNegativeButton("Cancelar", null).setPositiveButton("Guardar", null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val name = input.text.toString().trim(); if (name.isEmpty()) input.error = "Escribe un nombre" else { dialog.dismiss(); done(name) }
        } }; dialog.show()
    }
    private fun saveView() {
        val v = board.viewState(); getSharedPreferences("view:$current", MODE_PRIVATE).edit().putFloat("s", v[0]).putFloat("tx", v[1]).putFloat("ty", v[2]).apply()
    }
    private fun restoreView() {
        val p = getSharedPreferences("view:$current", MODE_PRIVATE)
        val legacy = getSharedPreferences("view", MODE_PRIVATE)
        val v = if (!p.contains("s") && current == "pizarra.db") legacy else p
        if (v.contains("s")) board.setView(v.getFloat("s", 1f), v.getFloat("tx", 0f), v.getFloat("ty", 0f))
    }
    private fun switchTo(id: String, saveOld: Boolean = true) {
        if (saveOld) { board.flush(); saveView() }
        root.removeView(board); current = id
        board = BoardView(this, Repo(this, current)); root.addView(board, 0, FrameLayout.LayoutParams(MATCH, MATCH))
        restoreView(); board.load(); saveSessions(); drawer.visibility = View.GONE
    }
    override fun onPause() { super.onPause(); board.flush(); saveView() }
    override fun onBackPressed() { if (drawer.visibility == View.VISIBLE) drawer.visibility = View.GONE else super.onBackPressed() }
}
