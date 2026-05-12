package com.standroid.launcher.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout

/**
 * A SwipeRefreshLayout that only intercepts downward swipes originating from
 * the top edge of the view (within [TOP_EDGE_DP] dp from the top).
 *
 * This prevents the refresh gesture from conflicting with normal vertical
 * scrolling inside a WebView whose content scrolls via inner DOM elements
 * (where webView.scrollY is always 0 from Android's perspective).
 *
 * Usage: replace <androidx.swiperefreshlayout.widget.SwipeRefreshLayout> with
 * <com.standroid.launcher.ui.TopEdgeSwipeRefreshLayout> in XML.
 */
class TopEdgeSwipeRefreshLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SwipeRefreshLayout(context, attrs) {

    companion object {
        /** Only allow pull-to-refresh when the initial touch is within this many dp from the top. */
        private const val TOP_EDGE_DP = 64f
    }

    private val topEdgePx: Float = TOP_EDGE_DP * context.resources.displayMetrics.density

    /** Y-coordinate of the most recent ACTION_DOWN event. */
    private var downY = 0f

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downY = ev.y
                // If the touch starts outside the top edge zone, never intercept.
                if (downY > topEdgePx) return false
            }
            MotionEvent.ACTION_MOVE -> {
                // If the original touch was outside the top edge zone, don't intercept.
                if (downY > topEdgePx) return false
            }
        }
        return super.onInterceptTouchEvent(ev)
    }
}
