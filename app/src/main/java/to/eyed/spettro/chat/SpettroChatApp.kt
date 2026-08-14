package to.eyed.spettro.chat

import android.app.Application
import com.clerk.api.Clerk

class SpettroChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // The key is injected at build time (see app/build.gradle.kts); a build
        // without one can still browse as a signed-in user via a cached ep_ key,
        // but cannot start a new sign-in.
        if (BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()) {
            Clerk.initialize(this, publishableKey = BuildConfig.CLERK_PUBLISHABLE_KEY)
        }
    }

    companion object {
        val isClerkConfigured: Boolean get() = BuildConfig.CLERK_PUBLISHABLE_KEY.isNotBlank()
    }
}
