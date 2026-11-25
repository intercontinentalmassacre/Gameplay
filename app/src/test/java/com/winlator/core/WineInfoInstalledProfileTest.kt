package com.winlator.core

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.winlator.contents.ContentProfile
import com.winlator.contents.ContentsManager
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [35])
class WineInfoInstalledProfileTest {
    @Test
    fun multiDigitBuildAndVersionAliasesResolveTheInstalledRuntime() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val manager = ContentsManager(context)
        val expected = "proton-11.0-2-arm64ec"
        val root = ContentsManager.getContentTypeDir(context, ContentProfile.ContentType.CONTENT_TYPE_PROTON)
        val installDir = root.resolve(expected).apply { mkdirs() }
        installDir.resolve(ContentsManager.PROFILE_NAME).writeText(
            """{"type":"Proton","versionName":"11.0-2-arm64ec","versionCode":13,"description":"test","files":[]}""",
        )

        for (name in listOf("$expected-13", expected, "Proton-$expected-13")) {
            val runtime = WineInfo.fromIdentifier(context, manager, name)
            assertEquals(expected, runtime.identifier())
            assertEquals(installDir.path, runtime.path)
        }
    }
}
