package eu.kanade.tachiyomi.ui.browse.source.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.manga.components.MangaCover
import eu.kanade.presentation.util.Screen
import eu.kanade.tachiyomi.ui.manga.MangaScreen
import tachiyomi.domain.manga.interactor.GetManga
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

/**
 * Yakuyomi：探索批次擷取的「失敗清單」。列出沒抓成功的書目（封面縮圖 + 標題），點某筆 → 進該書目頁讓使用者
 * 自己更新（手動重試），返回後回到本清單逐一檢查（Voyager push/pop 天然保留本頁捲動）。
 */
class SourceFetchResultsScreen(
    private val mangaIds: List<Long>,
) : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val getManga = remember { Injekt.get<GetManga>() }
        var items by remember { mutableStateOf<List<Manga>>(emptyList()) }
        LaunchedEffect(Unit) {
            items = mangaIds.mapNotNull { getManga.await(it) }
        }
        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(MR.strings.fetch_details_failed_title),
                    navigateUp = navigator::pop,
                )
            },
        ) { contentPadding ->
            LazyColumn(contentPadding = contentPadding) {
                items(items, key = { it.id }) { manga ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { navigator.push(MangaScreen(manga.id, true)) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MangaCover.Book(
                            data = manga.thumbnailUrl,
                            modifier = Modifier.width(40.dp),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(text = manga.title)
                    }
                }
            }
        }
    }
}
