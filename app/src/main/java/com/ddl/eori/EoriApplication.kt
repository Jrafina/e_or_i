package com.ddl.eori

import android.app.Application
import com.ddl.eori.di.AppContainer
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class EoriApplication : Application() {

    /** 应用级容器，ViewModel 通过它获取依赖（手动注入，无 DI 框架） */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        installCrashGuard()
        warmUpDefaults()
    }

    /**
     * 全局异常兜底：任何未捕获异常都先记录，避免应用直接闪退。
     * 说明：这里不做“吞异常后继续运行”的危险操作，只是把可恢复的协程异常记录，
     * 主线程异常仍交给系统，保证进程状态一致。
     */
    private fun installCrashGuard() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { android.util.Log.e("Eori", "uncaught on ${thread.name}", throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    /** 首次启动预置常用账户（后台执行，不阻塞首屏） */
    private fun warmUpDefaults() {
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e ->
                android.util.Log.w("Eori", "warmUp failed", e)
            }
        )
        scope.launch {
            runCatching { container.accountRepository.ensureDefaults() }
        }
    }
}
