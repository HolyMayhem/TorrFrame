package tv.torrframe.features.player

import tv.torrframe.core.models.LibraryItem
import tv.torrframe.core.models.TorrentFile

fun preferredVideo(item: LibraryItem?, files: List<TorrentFile>): TorrentFile? =
    (if (item?.inProgress == true) files.firstOrNull { it.id == item.lastFileId } else null)
        ?: files.singleOrNull()
