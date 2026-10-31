package com.ddl.eori

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ddl.eori.ui.AppViewModelFactory
import com.ddl.eori.ui.EoriApp
import com.ddl.eori.ui.settings.SettingsViewModel
import com.ddl.eori.ui.theme.EoriTheme

/**
 * 单 Activity 入口。
 *
 * 启动路径：SplashScreen（系统级，无白屏） -> 立即 setContent 渲染首页输入框
 * -> 数据在后台通过 Flow 到达。首帧不等待数据库，保证冷启动可输入 <= 2s。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)

        // 仅在极短的时间内保持启动屏，避免人为拖慢启动
        var keepSplash = true
        splash.setKeepOnScreenCondition { keepSplash }
        window.decorView.post { keepSplash = false }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
        )

        setContent {
            // 读取深色模式偏好（DataStore 首次读取极快，读不到时跟随系统）
            val settingsViewModel: SettingsViewModel = viewModel(factory = AppViewModelFactory.Factory)
            val settings by settingsViewModel.uiState.collectAsStateWithLifecycle()
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val dark = settings.darkMode || systemDark

            EoriTheme(darkTheme = dark) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    EoriApp()
                }
            }
        }
    }
}
