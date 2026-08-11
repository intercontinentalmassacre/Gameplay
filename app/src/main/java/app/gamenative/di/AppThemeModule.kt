package app.gamenative.di

import app.gamenative.PrefManager
import app.gamenative.enums.AppTheme
import com.materialkolor.PaletteStyle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Referenced from https://github.com/fvilarino/App-Theme-Compose-Sample
 */

interface IAppTheme {
    val themeFlow: StateFlow<AppTheme>
    var currentTheme: AppTheme
    val paletteFlow: StateFlow<PaletteStyle>
    var currentPalette: PaletteStyle
    val customThemeEnabledFlow: StateFlow<Boolean>
    val customThemeJsonFlow: StateFlow<String>
    fun setCustomTheme(json: String, enabled: Boolean)
    fun setCustomThemeEnabled(enabled: Boolean)
    fun clearCustomTheme()
}

class AppThemeImpl : IAppTheme {

    override val themeFlow: MutableStateFlow<AppTheme> = MutableStateFlow(PrefManager.appTheme)

    override var currentTheme: AppTheme by AppThemeDelegate()

    override val paletteFlow: MutableStateFlow<PaletteStyle> = MutableStateFlow(PrefManager.appThemePalette)

    override var currentPalette: PaletteStyle by AppPaletteDelegate()

    override val customThemeEnabledFlow: MutableStateFlow<Boolean> = MutableStateFlow(PrefManager.customThemeEnabled)
    override val customThemeJsonFlow: MutableStateFlow<String> = MutableStateFlow(PrefManager.customThemeJson)

    override fun setCustomTheme(json: String, enabled: Boolean) {
        PrefManager.customThemeJson = json
        PrefManager.customThemeEnabled = enabled
        customThemeJsonFlow.value = json
        customThemeEnabledFlow.value = enabled
    }

    override fun setCustomThemeEnabled(enabled: Boolean) {
        val safeEnabled = enabled && customThemeJsonFlow.value.isNotBlank()
        PrefManager.customThemeEnabled = safeEnabled
        customThemeEnabledFlow.value = safeEnabled
    }

    override fun clearCustomTheme() {
        PrefManager.customThemeEnabled = false
        PrefManager.customThemeJson = ""
        customThemeEnabledFlow.value = false
        customThemeJsonFlow.value = ""
    }

    inner class AppThemeDelegate : ReadWriteProperty<Any, AppTheme> {

        override fun getValue(thisRef: Any, property: KProperty<*>): AppTheme = PrefManager.appTheme

        override fun setValue(thisRef: Any, property: KProperty<*>, value: AppTheme) {
            themeFlow.value = value
            PrefManager.appTheme = value
        }
    }

    inner class AppPaletteDelegate : ReadWriteProperty<Any, PaletteStyle> {

        override fun getValue(thisRef: Any, property: KProperty<*>): PaletteStyle = PrefManager.appThemePalette

        override fun setValue(thisRef: Any, property: KProperty<*>, value: PaletteStyle) {
            paletteFlow.value = value
            PrefManager.appThemePalette = value
        }
    }
}

@InstallIn(SingletonComponent::class)
@Module
class AppThemeModule {
    @Provides
    @Singleton
    fun provideAppTheme(): IAppTheme = AppThemeImpl()
}
