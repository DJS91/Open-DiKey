package com.sphy.airconcontroller.ui

import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.sphy.airconcontroller.R
import com.sphy.airconcontroller.storage.AppSettings
import com.sphy.airconcontroller.storage.ThemeStyle

/**
 * Base for every screen. Applies the style chosen in Settings (Classic or Modern) before the layout
 * is inflated, and in Classic puts the cabin photo behind the content.
 * Photo CSS equivalent: `opacity: 0.2` on a `blur(5px)` image over the window color.
 * Blur is baked into [R.drawable.bg_interior]; alpha comes from [R.dimen.bg_photo_alpha], which is
 * lower in the light theme because the photo is dark. Modern uses a solid background instead.
 */
open class OpenDiKeyActivity : AppCompatActivity() {
    private var appliedStyle = ThemeStyle.CLASSIC

    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate so the right theme is in place when views are inflated.
        appliedStyle = AppSettings(this).themeStyle
        setTheme(ThemeManager.themeFor(appliedStyle))
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        // The style can be changed on another screen (Settings) while this one waits in the back stack.
        if (AppSettings(this).themeStyle != appliedStyle) recreate()
    }

    override fun setContentView(layoutResID: Int) {
        setContentView(layoutInflater.inflate(layoutResID, null))
    }

    override fun setContentView(view: View?) {
        val frame = FrameLayout(this)
        if (appliedStyle == ThemeStyle.CLASSIC) {
            val bg = ImageView(this).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.bg_interior)
                alpha = resources.getFloat(R.dimen.bg_photo_alpha)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
            frame.addView(
                bg,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        frame.addView(
            view,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        // Screens that include view_back_button_end get a right-hand back button for right-hand-drive cars.
        view?.findViewById<View>(R.id.backButtonEnd)?.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }
        super.setContentView(frame)
    }
}
