package org.buran.runtime

import android.app.AlertDialog
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView

/** Touch and keyboard controls used by the standalone player. */
internal class GameSessionMenu(
    root: FrameLayout,
    onToggle: (Boolean) -> Unit,
    onTogglePerformanceHud: () -> Unit,
    onToggleMangoHud: () -> Unit,
    onSetFpsLimit: (Int) -> Unit,
    onStop: () -> Unit,
) {
    private val resume = Button(root.context).apply {
        text = "Продолжить"
        setOnClickListener { onToggle(false) }
    }
    private val performanceHud = Button(root.context).apply {
        visibility = View.GONE
        setOnClickListener { onTogglePerformanceHud() }
    }
    private val mangoHud = Button(root.context).apply {
        visibility = View.GONE
        setOnClickListener { onToggleMangoHud() }
    }
    private var currentFpsLimit = 0
    private val fpsLimit = Button(root.context).apply {
        visibility = View.GONE
        setOnClickListener {
            val choices = ((listOf(0) + (15..120 step 5)) + currentFpsLimit).distinct().sorted()
            AlertDialog.Builder(context)
                .setTitle("Ограничение FPS")
                .setSingleChoiceItems(choices.map(::fpsLimitLabel).toTypedArray(), choices.indexOf(currentFpsLimit)) { dialog, index ->
                    onSetFpsLimit(choices[index])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }
    private val panel = FrameLayout(root.context).apply {
        setBackgroundColor(0x99000000.toInt())
        isClickable = true
        visibility = View.GONE
        addView(ScrollView(context).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(resume)
                addView(performanceHud)
                addView(mangoHud)
                addView(fpsLimit)
                addView(Button(context).apply {
                    text = "Завершить игру"
                    setOnClickListener { onStop() }
                })
            })
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
    }

    init {
        root.addView(panel, FrameLayout.LayoutParams(-1, -1))
    }

    fun display(available: Boolean, open: Boolean) {
        panel.visibility = if (available && open) View.VISIBLE else View.GONE
        if (panel.visibility == View.VISIBLE) resume.requestFocus()
    }

    fun setMetrics(
        singleScreen: Boolean, performanceEnabled: Boolean, mangoAvailable: Boolean, mangoEnabled: Boolean, limit: Int,
    ) {
        performanceHud.visibility = if (singleScreen) View.VISIBLE else View.GONE
        performanceHud.text = if (performanceEnabled) "Выключить показатели" else "Включить показатели"
        mangoHud.visibility = if (singleScreen && mangoAvailable) View.VISIBLE else View.GONE
        mangoHud.text = if (mangoEnabled) "Выключить MangoHud" else "Включить MangoHud"
        currentFpsLimit = limit
        fpsLimit.visibility = if (singleScreen) View.VISIBLE else View.GONE
        fpsLimit.text = "Ограничение FPS · ${fpsLimitLabel(limit)}"
    }

    private fun fpsLimitLabel(limit: Int) = if (limit == 0) "Выкл." else limit.toString()
}
