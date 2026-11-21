package com.motioncontroller.ui.i18n

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember

/**
 * Compose 接线：文案经 [LocalUiStrings] 注入整棵 UI 树（由 MainActivity 提供）。
 *
 * 持久化：语言模式存 SharedPreferences（文件 `motion_controller_ui`、键 `ui_language`），
 * 直接用 Activity/Context 读写，不经过 ControllerService。
 */
val LocalUiStrings: ProvidableCompositionLocal<UiStrings> = compositionLocalOf {
    uiStringsFor(LanguageMode.SYSTEM)
}

/** 取当前界面文案（跟随 CompositionLocal 提供的语言模式）。 */
@Composable
fun rememberUiStrings(): UiStrings = LocalUiStrings.current

/** 语言模式持久化（键 `ui_language`，文件 `motion_controller_ui`）。 */
object LanguagePreferences {
    const val PREFS_FILE: String = "motion_controller_ui"
    const val KEY_UI_LANGUAGE: String = "ui_language"

    fun read(context: Context): LanguageMode =
        LanguageMode.fromStored(
            context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
                .getString(KEY_UI_LANGUAGE, null),
        )

    fun write(context: Context, mode: LanguageMode) {
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_UI_LANGUAGE, mode.name)
            .apply()
    }
}
