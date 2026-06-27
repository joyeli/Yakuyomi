package eu.kanade.tachiyomi.ui.more

import androidx.compose.animation.graphics.res.animatedVectorResource
import androidx.compose.animation.graphics.res.rememberAnimatedVectorPainter
import androidx.compose.animation.graphics.vector.AnimatedImageVector
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.currentOrThrow
import cafe.adriel.voyager.navigator.tab.LocalTabNavigator
import cafe.adriel.voyager.navigator.tab.TabOptions
import eu.kanade.core.preference.asState
import eu.kanade.domain.base.BasePreferences
import eu.kanade.presentation.more.MoreScreen
import eu.kanade.presentation.util.Tab
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.translation.TranslationManager
import eu.kanade.tachiyomi.ui.category.CategoryScreen
import eu.kanade.tachiyomi.ui.download.DownloadQueueScreen
import eu.kanade.tachiyomi.ui.home.HomeScreen
import eu.kanade.tachiyomi.ui.setting.SettingsScreen
import eu.kanade.tachiyomi.ui.stats.StatsScreen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import mihon.feature.support.SupportUsScreen
import tachiyomi.core.common.util.lang.launchIO
import tachiyomi.domain.translation.service.TranslationPreferences
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

data object MoreTab : Tab {

    override val options: TabOptions
        @Composable
        get() {
            val isSelected = LocalTabNavigator.current.current.key == key
            val image = AnimatedImageVector.animatedVectorResource(R.drawable.anim_more_enter)
            return TabOptions(
                index = 4u,
                title = stringResource(MR.strings.label_more),
                icon = rememberAnimatedVectorPainter(image, isSelected),
            )
        }

    override suspend fun onReselect(navigator: Navigator) {
        navigator.push(SettingsScreen())
    }

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val scope = rememberCoroutineScope()
        val screenModel = rememberScreenModel { MoreScreenModel() }
        val downloadQueueState by screenModel.downloadQueueState.collectAsState()
        MoreScreen(
            downloadQueueStateProvider = { downloadQueueState },
            downloadedOnly = screenModel.downloadedOnly,
            onDownloadedOnlyChange = { screenModel.downloadedOnly = it },
            incognitoMode = screenModel.incognitoMode,
            onIncognitoModeChange = { screenModel.incognitoMode = it },
            translationMasterEnabled = screenModel.translationMasterEnabled,
            onTranslationMasterChange = { screenModel.setTranslationMaster(it) },
            onClickDownloadQueue = { navigator.push(DownloadQueueScreen) },
            // Yakuyomi：「更新」分頁已從導覽列移除 → 從這裡切換過去（翻譯佇列改成導覽列分頁）。
            onClickUpdates = { scope.launch { HomeScreen.openTab(HomeScreen.Tab.Updates) } },
            onClickCategories = { navigator.push(CategoryScreen()) },
            onClickStats = { navigator.push(StatsScreen()) },
            onClickDataAndStorage = { navigator.push(SettingsScreen(SettingsScreen.Destination.DataAndStorage)) },
            onClickSettings = { navigator.push(SettingsScreen()) },
            onClickSupport = { navigator.push(SupportUsScreen()) },
            onClickAbout = { navigator.push(SettingsScreen(SettingsScreen.Destination.About)) },
        )
    }
}

private class MoreScreenModel(
    private val downloadManager: DownloadManager = Injekt.get(),
    private val translationManager: TranslationManager = Injekt.get(),
    preferences: BasePreferences = Injekt.get(),
    translationPreferences: TranslationPreferences = Injekt.get(),
) : ScreenModel {

    var downloadedOnly by preferences.downloadedOnly.asState(screenModelScope)
    var incognitoMode by preferences.incognitoMode.asState(screenModelScope)

    // Yakuyomi：翻譯總開關快捷切換（與翻譯設定頁綁同一 pref、連動）；set 後觸發 manager 副作用（停/續 + 引擎）。
    var translationMasterEnabled by translationPreferences.translationMasterEnabled.asState(screenModelScope)
    fun setTranslationMaster(enabled: Boolean) {
        translationMasterEnabled = enabled
        translationManager.onMasterEnabledChanged(enabled)
    }

    private var _downloadQueueState: MutableStateFlow<DownloadQueueState> = MutableStateFlow(DownloadQueueState.Stopped)
    val downloadQueueState: StateFlow<DownloadQueueState> = _downloadQueueState.asStateFlow()

    private var _translationQueueState: MutableStateFlow<TranslationQueueState> =
        MutableStateFlow(TranslationQueueState.Stopped)
    val translationQueueState: StateFlow<TranslationQueueState> = _translationQueueState.asStateFlow()

    init {
        // Handle running/paused status change and queue progress updating
        screenModelScope.launchIO {
            combine(
                downloadManager.isDownloaderRunning,
                downloadManager.queueState,
            ) { isRunning, downloadQueue -> Pair(isRunning, downloadQueue.size) }
                .collectLatest { (isDownloading, downloadQueueSize) ->
                    val pendingDownloadExists = downloadQueueSize != 0
                    _downloadQueueState.value = when {
                        !pendingDownloadExists -> DownloadQueueState.Stopped
                        !isDownloading -> DownloadQueueState.Paused(downloadQueueSize)
                        else -> DownloadQueueState.Downloading(downloadQueueSize)
                    }
                }
        }
        // 翻譯佇列狀態（與下載各自獨立）
        screenModelScope.launchIO {
            combine(
                translationManager.queueState,
                translationManager.isPaused,
            ) { queue, paused -> Pair(queue.size, paused) }
                .collectLatest { (size, paused) ->
                    _translationQueueState.value = when {
                        size == 0 -> TranslationQueueState.Stopped
                        paused -> TranslationQueueState.Paused(size)
                        else -> TranslationQueueState.Translating(size)
                    }
                }
        }
    }
}

sealed interface DownloadQueueState {
    data object Stopped : DownloadQueueState
    data class Paused(val pending: Int) : DownloadQueueState
    data class Downloading(val pending: Int) : DownloadQueueState
}

sealed interface TranslationQueueState {
    data object Stopped : TranslationQueueState
    data class Paused(val pending: Int) : TranslationQueueState
    data class Translating(val pending: Int) : TranslationQueueState
}
