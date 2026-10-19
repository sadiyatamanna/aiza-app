package com.example

import com.facebook.react.ReactActivity
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.defaults.DefaultReactActivityDelegate

/**
 * Main Activity for Aegis React Native Android app.
 */
class MainActivity : ReactActivity() {

    override fun getMainComponentName(): String = "aegis-app"

    override fun createReactActivityDelegate(): ReactActivityDelegate =
        DefaultReactActivityDelegate(this, mainComponentName, false)
}
