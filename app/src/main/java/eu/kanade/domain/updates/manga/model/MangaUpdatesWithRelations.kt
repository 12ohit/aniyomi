package eu.kanade.domain.updates.manga.model

import eu.kanade.domain.entries.manga.model.MangaCover

data class MangaUpdatesWithRelations(
    val mangaId: Long,
    val mangaTitle: String,
    val chapterId: Long,
    val chapterName: String,
    val scanlator: String?,
    val read: Boolean,
    val bookmark: Boolean,
    val sourceId: Long,
    val dateFetch: Long,
    val coverData: MangaCover,
)
