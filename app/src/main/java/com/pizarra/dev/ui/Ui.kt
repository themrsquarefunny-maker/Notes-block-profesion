package com.pizarra.dev.ui

import android.view.View
import android.view.ViewGroup

const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

/** Paleta oscura minimalista. */
object Ui {
    val BG = 0xFF0E1013.toInt()
    val SURFACE = 0xFF1A1D23.toInt()
    val CODE_BG = 0xFF11141A.toInt()
    val STROKE = 0xFF2E343E.toInt()
    val GRID = 0xFF2A2F38.toInt()
    val ACCENT = 0xFF58A6FF.toInt()
    val GREEN = 0xFF7EE2A8.toInt()
    val FOCUS = 0xFFC9D1D9.toInt()
    val TEXT = 0xFFE6EDF3.toInt()
    val DIM = 0xFF8B949E.toInt()
}

fun View.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun View.dpf(v: Float): Float = v * resources.displayMetrics.density
