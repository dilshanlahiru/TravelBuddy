package com.lahiru.travelbuddy.ui.comms

import android.content.Context
import android.util.AttributeSet
import android.widget.Button

class MicButton @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.buttonStyle,
) : Button(context, attrs, defStyleAttr) {
    override fun performClick(): Boolean = super.performClick()
}
