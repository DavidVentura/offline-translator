package dev.davidv.translator.overlayChrome

import android.content.Context
import android.graphics.Color
import android.view.View
import dev.davidv.translator.Language

interface OverlayMenuHost {
  fun addDismissLayer(view: View)

  fun addMenuView(view: View)

  fun addPickerView(view: View)

  fun removeMenuChild(view: View)
}

class OverlayMenuManager(
  private val context: Context,
  private val dpToPx: (Int) -> Int,
  private val host: OverlayMenuHost,
) {
  private var dismissLayer: View? = null
  private var menuView: View? = null

  fun showDotsMenu(menuItems: List<Pair<String, () -> Unit>>) {
    dismiss()

    val dismissView =
      View(context).apply {
        setBackgroundColor(Color.TRANSPARENT)
        setOnClickListener { dismiss() }
      }
    host.addDismissLayer(dismissView)
    dismissLayer = dismissView

    val menuContainer = OverlayChromeFactory.createMenuContainer(context, dpToPx)
    for ((label, action) in menuItems) {
      OverlayChromeFactory.addMenuItem(context, menuContainer, dpToPx, label) {
        dismiss()
        action()
      }
    }
    host.addMenuView(menuContainer)
    menuView = menuContainer
  }

  fun showLanguagePicker(
    isSource: Boolean,
    availableLangs: List<Language>,
    onPick: (Language?) -> Unit,
  ) {
    dismiss()

    val dismissView =
      View(context).apply {
        setBackgroundColor(Color.parseColor("#80000000"))
        setOnClickListener { dismiss() }
      }
    host.addDismissLayer(dismissView)
    dismissLayer = dismissView

    val picker =
      OverlayChromeFactory.createLanguagePicker(
        context = context,
        dpToPx = dpToPx,
        isSource = isSource,
        availableLangs = availableLangs,
      ) { language ->
        onPick(language)
        dismiss()
      }
    host.addPickerView(picker)
    menuView = picker
  }

  fun dismiss() {
    dismissLayer?.let {
      host.removeMenuChild(it)
      dismissLayer = null
    }
    menuView?.let {
      host.removeMenuChild(it)
      menuView = null
    }
  }
}
