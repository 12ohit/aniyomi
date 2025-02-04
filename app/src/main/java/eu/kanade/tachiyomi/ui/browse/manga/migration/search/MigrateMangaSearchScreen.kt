package eu.kanade.tachiyomi.ui.browse.manga.migration.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.util.fastForEachIndexed
import cafe.adriel.voyager.core.model.ScreenModel
import cafe.adriel.voyager.core.model.rememberScreenModel
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import eu.kanade.domain.category.manga.interactor.GetMangaCategories
import eu.kanade.domain.category.manga.interactor.SetMangaCategories
import eu.kanade.domain.entries.manga.interactor.UpdateManga
import eu.kanade.domain.entries.manga.model.Manga
import eu.kanade.domain.entries.manga.model.MangaUpdate
import eu.kanade.domain.entries.manga.model.hasCustomCover
import eu.kanade.domain.items.chapter.interactor.GetChapterByMangaId
import eu.kanade.domain.items.chapter.interactor.SyncChaptersWithSource
import eu.kanade.domain.items.chapter.interactor.UpdateChapter
import eu.kanade.domain.items.chapter.model.toChapterUpdate
import eu.kanade.domain.track.manga.interactor.GetMangaTracks
import eu.kanade.domain.track.manga.interactor.InsertMangaTrack
import eu.kanade.presentation.browse.manga.MigrateMangaSearchScreen
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.core.preference.Preference
import eu.kanade.tachiyomi.core.preference.PreferenceStore
import eu.kanade.tachiyomi.data.cache.MangaCoverCache
import eu.kanade.tachiyomi.data.track.EnhancedMangaTrackService
import eu.kanade.tachiyomi.data.track.TrackManager
import eu.kanade.tachiyomi.source.MangaSource
import eu.kanade.tachiyomi.source.manga.MangaSourceManager
import eu.kanade.tachiyomi.source.model.SChapter
import eu.kanade.tachiyomi.ui.browse.manga.migration.MangaMigrationFlags
import eu.kanade.tachiyomi.ui.entries.manga.MangaScreen
import eu.kanade.tachiyomi.util.lang.launchIO
import eu.kanade.tachiyomi.util.lang.launchUI
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import java.util.Date

class MigrateSearchScreen(private val mangaId: Long) : Screen {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val screenModel = rememberScreenModel { MigrateSearchScreenModel(mangaId = mangaId) }
        val state by screenModel.state.collectAsState()

        MigrateMangaSearchScreen(
            navigateUp = navigator::pop,
            state = state,
            getManga = { source, manga ->
                screenModel.getManga(source = source, initialManga = manga)
            },
            onChangeSearchQuery = screenModel::updateSearchQuery,
            onSearch = screenModel::search,
            onClickSource = {
                if (!screenModel.incognitoMode.get()) {
                    screenModel.lastUsedSourceId.set(it.id)
                }
                navigator.push(MangaSourceSearchScreen(state.manga!!, it.id, state.searchQuery))
            },
            onClickItem = { screenModel.setDialog(MigrateMangaSearchDialog.Migrate(it)) },
            onLongClickItem = { navigator.push(MangaScreen(it.id, true)) },
        )

        when (val dialog = state.dialog) {
            null -> {}
            is MigrateMangaSearchDialog.Migrate -> {
                MigrateMangaDialog(
                    oldManga = state.manga!!,
                    newManga = dialog.manga,
                    screenModel = rememberScreenModel { MigrateMangaDialogScreenModel() },
                    onDismissRequest = { screenModel.setDialog(null) },
                    onClickTitle = {
                        navigator.push(MangaScreen(dialog.manga.id, true))
                    },
                    onPopScreen = {
                        if (navigator.lastItem is MangaScreen) {
                            val lastItem = navigator.lastItem
                            navigator.popUntil { navigator.items.contains(lastItem) }
                            navigator.push(MangaScreen(dialog.manga.id))
                        } else {
                            navigator.replace(MangaScreen(dialog.manga.id))
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun MigrateMangaDialog(
    oldManga: Manga,
    newManga: Manga,
    screenModel: MigrateMangaDialogScreenModel,
    onDismissRequest: () -> Unit,
    onClickTitle: () -> Unit,
    onPopScreen: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val activeFlags = remember { MangaMigrationFlags.getEnabledFlagsPositions(screenModel.migrateFlags.get()) }
    val items = remember {
        MangaMigrationFlags.titles(oldManga)
            .map { context.getString(it) }
            .toList()
    }
    val selected = remember {
        mutableStateListOf(*List(items.size) { i -> activeFlags.contains(i) }.toTypedArray())
    }
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = {
            Text(text = stringResource(R.string.migration_dialog_what_to_include))
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
            ) {
                items.forEachIndexed { index, title ->
                    val onChange: () -> Unit = {
                        selected[index] = !selected[index]
                    }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onChange),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected[index], onCheckedChange = { onChange() })
                        Text(text = title)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                TextButton(onClick = {
                    onClickTitle()
                    onDismissRequest()
                },) {
                    Text(text = stringResource(R.string.action_show_manga))
                }
                Spacer(modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launchIO {
                        screenModel.migrateManga(oldManga, newManga, false)
                        launchUI {
                            onPopScreen()
                        }
                    }
                },) {
                    Text(text = stringResource(R.string.copy))
                }
                TextButton(onClick = {
                    scope.launchIO {
                        val selectedIndices = mutableListOf<Int>()
                        selected.fastForEachIndexed { i, b -> if (b) selectedIndices.add(i) }
                        val newValue = MangaMigrationFlags.getFlagsFromPositions(selectedIndices.toTypedArray())
                        screenModel.migrateFlags.set(newValue)
                        screenModel.migrateManga(oldManga, newManga, true)
                        launchUI {
                            onPopScreen()
                        }
                    }
                },) {
                    Text(text = stringResource(R.string.migrate))
                }
            }
        },
    )
}

class MigrateMangaDialogScreenModel(
    private val sourceManager: MangaSourceManager = Injekt.get(),
    private val updateManga: UpdateManga = Injekt.get(),
    private val getChapterByMangaId: GetChapterByMangaId = Injekt.get(),
    private val syncChaptersWithSource: SyncChaptersWithSource = Injekt.get(),
    private val updateChapter: UpdateChapter = Injekt.get(),
    private val getCategories: GetMangaCategories = Injekt.get(),
    private val setMangaCategories: SetMangaCategories = Injekt.get(),
    private val getTracks: GetMangaTracks = Injekt.get(),
    private val insertTrack: InsertMangaTrack = Injekt.get(),
    private val coverCache: MangaCoverCache = Injekt.get(),
    private val preferenceStore: PreferenceStore = Injekt.get(),
) : ScreenModel {

    val migrateFlags: Preference<Int> by lazy {
        preferenceStore.getInt("migrate_flags", Int.MAX_VALUE)
    }

    private val enhancedServices by lazy { Injekt.get<TrackManager>().services.filterIsInstance<EnhancedMangaTrackService>() }

    suspend fun migrateManga(oldManga: Manga, newManga: Manga, replace: Boolean) {
        val source = sourceManager.get(newManga.source) ?: return
        val prevSource = sourceManager.get(oldManga.source)

        try {
            val chapters = source.getChapterList(newManga.toSManga())

            migrateMangaInternal(
                oldSource = prevSource,
                newSource = source,
                oldManga = oldManga,
                newManga = newManga,
                sourceChapters = chapters,
                replace = replace,
            )
        } catch (e: Throwable) {
        }
    }

    private suspend fun migrateMangaInternal(
        oldSource: MangaSource?,
        newSource: MangaSource,
        oldManga: Manga,
        newManga: Manga,
        sourceChapters: List<SChapter>,
        replace: Boolean,
    ) {
        val flags = migrateFlags.get()

        val migrateChapters = MangaMigrationFlags.hasChapters(flags)
        val migrateCategories = MangaMigrationFlags.hasCategories(flags)
        val migrateTracks = MangaMigrationFlags.hasTracks(flags)
        val migrateCustomCover = MangaMigrationFlags.hasCustomCover(flags)

        try {
            syncChaptersWithSource.await(sourceChapters, newManga, newSource)
        } catch (e: Exception) {
            // Worst case, chapters won't be synced
        }

        // Update chapters read, bookmark and dateFetch
        if (migrateChapters) {
            val prevMangaChapters = getChapterByMangaId.await(oldManga.id)
            val mangaChapters = getChapterByMangaId.await(newManga.id)

            val maxChapterRead = prevMangaChapters
                .filter { it.read }
                .maxOfOrNull { it.chapterNumber }

            val updatedMangaChapters = mangaChapters.map { mangaChapter ->
                var updatedChapter = mangaChapter
                if (updatedChapter.isRecognizedNumber) {
                    val prevChapter = prevMangaChapters
                        .find { it.isRecognizedNumber && it.chapterNumber == updatedChapter.chapterNumber }

                    if (prevChapter != null) {
                        updatedChapter = updatedChapter.copy(
                            dateFetch = prevChapter.dateFetch,
                            bookmark = prevChapter.bookmark,
                        )
                    }

                    if (maxChapterRead != null && updatedChapter.chapterNumber <= maxChapterRead) {
                        updatedChapter = updatedChapter.copy(read = true)
                    }
                }

                updatedChapter
            }

            val chapterUpdates = updatedMangaChapters.map { it.toChapterUpdate() }
            updateChapter.awaitAll(chapterUpdates)
        }

        // Update categories
        if (migrateCategories) {
            val categoryIds = getCategories.await(oldManga.id).map { it.id }
            setMangaCategories.await(newManga.id, categoryIds)
        }

        // Update track
        if (migrateTracks) {
            val tracks = getTracks.await(oldManga.id).mapNotNull { track ->
                val updatedTrack = track.copy(mangaId = newManga.id)

                val service = enhancedServices
                    .firstOrNull { it.isTrackFrom(updatedTrack, oldManga, oldSource) }

                if (service != null) {
                    service.migrateTrack(updatedTrack, newManga, newSource)
                } else {
                    updatedTrack
                }
            }
            insertTrack.awaitAll(tracks)
        }

        if (replace) {
            updateManga.await(MangaUpdate(oldManga.id, favorite = false, dateAdded = 0))
        }

        // Update custom cover (recheck if custom cover exists)
        if (migrateCustomCover && oldManga.hasCustomCover()) {
            @Suppress("BlockingMethodInNonBlockingContext")
            coverCache.setCustomCoverToCache(newManga, coverCache.getCustomCoverFile(oldManga.id).inputStream())
        }

        updateManga.await(
            MangaUpdate(
                id = newManga.id,
                favorite = true,
                chapterFlags = oldManga.chapterFlags,
                viewerFlags = oldManga.viewerFlags,
                dateAdded = if (replace) oldManga.dateAdded else Date().time,
            ),
        )
    }
}
