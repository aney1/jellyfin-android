package org.jellyfin.mobile.offline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.offline.playlist.OfflineRules
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class OfflineRulesTest : FunSpec({
    val minuteTicks = 60L * 10_000_000L
    val day = 1.days.inWholeMilliseconds
    fun id() = UUID.randomUUID()
    fun video(itemId: UUID, minutes: Long = 10, played: Boolean = false, isVideo: Boolean = true) =
        OfflineRules.Entry(itemId, isVideo, minutes * minuteTicks, played)

    fun wanted(
        entries: List<OfflineRules.Entry>,
        excluded: Set<UUID> = emptySet(),
        maxItems: Int? = null,
        maxMinutes: Int? = null,
    ) = OfflineRules.wantedItems(entries, excluded, maxItems, maxMinutes)

    context("wantedItems") {
        val a = id()
        val b = id()
        val c = id()
        val d = id()

        test("all unwatched videos in playlist order") {
            wanted(listOf(video(a), video(b, played = true), video(c))) shouldContainExactly listOf(a, c)
        }

        test("excluded videos and other media types are skipped") {
            val entries = listOf(video(a), video(b, isVideo = false), video(c))
            wanted(entries, excluded = setOf(a)) shouldContainExactly listOf(c)
        }

        test("at most maxItems, counted after skipping") {
            val entries = listOf(video(a, played = true), video(b), video(c), video(d))
            wanted(entries, maxItems = 2) shouldContainExactly listOf(b, c)
        }

        test("videos longer than the runtime limit are skipped") {
            val entries = listOf(video(a, minutes = 5), video(b, minutes = 45))
            wanted(entries, maxMinutes = 30) shouldContainExactly listOf(a)
        }

        test("an item twice in the playlist is downloaded once") {
            wanted(listOf(video(a), video(a))) shouldContainExactly listOf(a)
        }
    }

    context("leftPlaylists") {
        val playlist = id()
        val other = id()
        val item = id()
        val now = 10 * day
        val grace = 10.minutes

        test("removed after missing longer than the grace period") {
            OfflineRules.leftPlaylists(
                origins = mapOf(item to listOf(OfflineRules.Origin(playlist, now - grace.inWholeMilliseconds))),
                knownSnapshots = mapOf(playlist to emptySet()),
                now = now,
                grace = grace,
                itemInUse = null,
            ) shouldBe setOf(item)
        }

        test("kept while missing for less than the grace period (one bad server response)") {
            OfflineRules.leftPlaylists(
                origins = mapOf(item to listOf(OfflineRules.Origin(playlist, now - 1000))),
                knownSnapshots = mapOf(playlist to emptySet()),
                now = now,
                grace = grace,
                itemInUse = null,
            ).shouldBeEmpty()
        }

        test("kept while still in another known playlist") {
            OfflineRules.leftPlaylists(
                origins = mapOf(item to listOf(OfflineRules.Origin(playlist, 0))),
                knownSnapshots = mapOf(playlist to emptySet(), other to setOf(item)),
                now = now,
                grace = grace,
                itemInUse = null,
            ).shouldBeEmpty()
        }

        test("kept while one of its playlists was never refreshed") {
            OfflineRules.leftPlaylists(
                origins = mapOf(item to listOf(OfflineRules.Origin(playlist, 0), OfflineRules.Origin(other, null))),
                knownSnapshots = mapOf(playlist to emptySet()),
                now = now,
                grace = grace,
                itemInUse = null,
            ).shouldBeEmpty()
        }

        test("the item in use is kept") {
            OfflineRules.leftPlaylists(
                origins = mapOf(item to listOf(OfflineRules.Origin(playlist, 0))),
                knownSnapshots = mapOf(playlist to emptySet()),
                now = now,
                grace = grace,
                itemInUse = item,
            ).shouldBeEmpty()
        }
    }

    context("expiredWatched") {
        val item = id()
        val now = 10 * day
        fun state(
            played: Boolean = true,
            playedAt: Long? = now - 2 * day,
            dirty: Boolean = false,
            downloadedAt: Long = now - 5 * day,
        ) = OfflineRules.WatchState(item, played, playedAt, dirty, downloadedAt)

        fun expired(
            state: OfflineRules.WatchState,
            deleteAfter: Duration? = 1.days,
            countFrom: Long = 0,
            inUse: UUID? = null,
        ) = OfflineRules.expiredWatched(listOf(state), now, deleteAfter, countFrom, inUse)

        test("deleted once the period after watching passed") {
            expired(state()) shouldBe setOf(item)
        }

        test("kept within the period") {
            expired(state(playedAt = now - day / 2)).shouldBeEmpty()
        }

        test("watching before the download doesn't count, a re-download to watch again is kept") {
            expired(state(playedAt = now - 30 * day, downloadedAt = now - 2 * day)).shouldBeEmpty()
        }

        test("watching before auto delete was enabled doesn't count") {
            expired(state(), countFrom = now - day).shouldBeEmpty()
        }

        test("unsent watch state, unwatched, in use or 'never' keep the download") {
            expired(state(dirty = true)).shouldBeEmpty()
            expired(state(played = false)).shouldBeEmpty()
            expired(state(), inUse = item).shouldBeEmpty()
            expired(state(), deleteAfter = null).shouldBeEmpty()
        }

        test("'right after watching' deletes at the next run") {
            expired(state(playedAt = now), deleteAfter = 0.days) shouldBe setOf(item)
        }
    }
})
