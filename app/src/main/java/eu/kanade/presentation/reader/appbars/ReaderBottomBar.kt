package eu.kanade.presentation.reader.appbars

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.ImportContacts
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.ui.reader.setting.ReaderOrientation
import eu.kanade.tachiyomi.ui.reader.setting.ReadingMode
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource

@Composable
fun ReaderBottomBar(
    readingMode: ReadingMode,
    onClickReadingMode: () -> Unit,
    orientation: ReaderOrientation,
    onClickOrientation: () -> Unit,
    cropEnabled: Boolean,
    onClickCropBorder: () -> Unit,
    onClickSettings: () -> Unit,
    // Yakuyomi：reader 內章節清單鈕。
    onClickChapterList: () -> Unit,
    modifier: Modifier = Modifier,
    // Yakuyomi：對開模式才有的「位移」鈕（非空才顯示）。
    onClickShiftDoublePage: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .pointerInput(Unit) {},
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClickReadingMode) {
            Icon(
                painter = painterResource(readingMode.iconRes),
                contentDescription = stringResource(MR.strings.viewer),
            )
        }

        if (onClickShiftDoublePage != null) {
            IconButton(onClick = onClickShiftDoublePage) {
                Icon(
                    imageVector = Icons.Outlined.ImportContacts,
                    contentDescription = stringResource(MR.strings.action_shift_double_page),
                )
            }
        }

        IconButton(onClick = onClickOrientation) {
            Icon(
                imageVector = orientation.icon,
                contentDescription = stringResource(MR.strings.rotation_type),
            )
        }

        IconButton(onClick = onClickCropBorder) {
            Icon(
                painter = painterResource(if (cropEnabled) R.drawable.ic_crop_24dp else R.drawable.ic_crop_off_24dp),
                contentDescription = stringResource(MR.strings.pref_crop_borders),
            )
        }

        IconButton(onClick = onClickChapterList) {
            Icon(
                imageVector = Icons.AutoMirrored.Outlined.List,
                contentDescription = stringResource(MR.strings.reader_chapter_list),
            )
        }

        IconButton(onClick = onClickSettings) {
            Icon(
                imageVector = Icons.Outlined.Settings,
                contentDescription = stringResource(MR.strings.action_settings),
            )
        }
    }
}
