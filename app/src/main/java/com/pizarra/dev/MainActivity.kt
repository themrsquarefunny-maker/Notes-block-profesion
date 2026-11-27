package com.pizarra.dev

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import com.pizarra.dev.data.Repo
import com.pizarra.dev.ui.BlockView
import com.pizarra.dev.ui.BoardView
import com.pizarra.dev.ui.MATCH
import com.pizarra.dev.ui.Ui

class MainActivity : Activity() {

    private lateinit var board: BoardView

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val prefs = getSharedPreferences("view", MODE_PRIVATE)
        board = BoardView(this, Repo(this))
        if (prefs.contains("s")) {
            board.setView(prefs.getFloat("s", 1f), prefs.getFloat("tx", 0f), prefs.getFloat("ty", 0f))
        }

        val d = resources.displayMetrics.density
        val size = (56 * d).toInt()

        val fab = TextView(this).apply {
            text = "+"
            textSize = 30f
            setTextColor(Color.parseColor("#0E1013"))
            gravity = Gravity.CENTER
            elevation = 8 * d
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Ui.ACCENT) }
        }
        fab.setOnClickListener {
            val pm = PopupMenu(this, fab)
            pm.menu.add(0, BlockView.TITLE, 0, "Título")
            pm.menu.add(0, BlockView.SUBTITLE, 1, "Subtítulo")
            pm.menu.add(0, BlockView.NOTE, 2, "Nota")
            pm.menu.add(0, 9, 3, "Centrar vista")
            pm.setOnMenuItemClickListener {
                if (it.itemId == 9) board.recenter() else board.addBlock(it.itemId)
                true
            }
            pm.show()
        }

        val root = FrameLayout(this)
        root.addView(board, FrameLayout.LayoutParams(MATCH, MATCH))
        root.addView(
            fab,
            FrameLayout.LayoutParams(size, size, Gravity.END or Gravity.BOTTOM).apply {
                setMargins(0, 0, (20 * d).toInt(), (24 * d).toInt())
            }
        )
        setContentView(root)
        board.load()
    }

    override fun onPause() {
        super.onPause()
        board.flush()
        val v = board.viewState()
        getSharedPreferences("view", MODE_PRIVATE).edit()
            .putFloat("s", v[0]).putFloat("tx", v[1]).putFloat("ty", v[2]).apply()
    }
}
