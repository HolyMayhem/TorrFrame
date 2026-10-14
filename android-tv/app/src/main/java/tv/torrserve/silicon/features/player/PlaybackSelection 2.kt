package tv.torrserve.silicon.features.player

import tv.torrserve.silicon.core.models.LibraryItem
import tv.torrserve.silicon.core.models.TorrentFile

fun preferredVideo(item: LibraryItem?, files: List<TorrentFile>): TorrentFile? =
    (if (item?.inProgress == true) files.firstOrNull { it.id == item.lastFileId } else null)
        ?: files.singleOrNull()
