package to.eyed.spettro.chat

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.clerk.api.Clerk
import to.eyed.spettro.chat.data.AppContainer
import to.eyed.spettro.chat.engine.AgentNotifications

class SpettroChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The key is injected at build time (see app/build.gradle.kts); a build
        // without one can still browse as a signed-in user via a cached ep_ key,
        // but cannot start a new sign-in.
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            Clerk.initialize(this, publishableKey = BuildConfig.CLERK_PUBLISHABLE_KEY)
        }
        AgentNotifications.createChannels(this)
        // The engine notifies (completion, needs-input) only while no activity
        // is on screen; this observer is the single source of that fact.
        ProcessLifecycleOwner.get().lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                AppContainer.get(this@SpettroChatApp).engine.appVisible = true
            }

            override fun onStop(owner: LifecycleOwner) {
                AppContainer.get(this@SpettroChatApp).engine.appVisible = false
            }
        })
    }

    companion object {
        val isClerkConfigured: Boolean get() = BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()
    }
}
