package org.jellyfin.mobile.offline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.offline.playlist.OfflineRules
import java.util.UUID
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

class OfflineRulesTest : FunSpec({
    val minuteTicks = 60L * 10_000_000L
    val day = 1.days.inWholeMilliseconds
    fun id() = UUID.randomUUID()
    fun video(itemId: UUID, minutes: Long = 10, played: Boolean = false, isVideo: Boolean = true) =
        OfflineRules.Entry(itemId, isVideo, minutes * minuteTicks, played)

    context("wantedItems") {
        val a = id()
        val b = id()
        val c = id()
        val d = id()

        test("all unwatched videos in playlist order") {
            OfflineRules.wantedItems(listOf(video(a), video(b, played = true), video(c)), emptySet(), null, null) shouldContainExactly
                listOf(a, c)
        }

        test("excluded videos and other media types are skipped") {
            OfflineRules.wantedItems(listOf(video(a), video(b, isVideo = false), video(c)), setOf(a), null, null) shouldContainExactly
                listOf(c)
        }

        test("at most maxItems, counted after skipping") {
            OfflineRules.wantedItems(listOf(video(a, played = true), video(b), video(c), video(d)), emptySet(), 2, null) shouldContainExactly
                listOf(b, c)
        }

        test("videos longer than the runtime limit are skipped") {
            OfflineRules.wantedItems(listOf(video(a, minutes = 5), video(b, minutes = 45)), emptySet(), null, 30) shouldContainExactly
                listOf(a)
        }

        test("an item twice in the playlist is downloaded once") {
            OfflineRules.wantedItems(listOf(video(a), video(a)), emptySet(), null, null) shouldContainExactly listOf(a)
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
        fun state(played: Boolean = true, playedAt: Long? = now - 2 * day, dirty: Boolean = false, downloadedAt: Long = 0) =
            OfflineRules.WatchState(item, played, playedAt, dirty, downloadedAt)

        test("deleted once the period after watching passed") {
            OfflineRules.expiredWatched(listOf(state()), now, 1.days, null) shouldBe setOf(item)
        }

        test("kept within the period") {
            OfflineRules.expiredWatched(listOf(state(playedAt = now - day / 2)), now, 1.days, null).shouldBeEmpty()
        }

        test("a re-download of something watched long ago gets the full period") {
            OfflineRules.expiredWatched(listOf(state(playedAt = now - 30 * day, downloadedAt = now - day / 2)), now, 1.days, null)
                .shouldBeEmpty()
        }

        test("unsent watch state, unwatched, in use or 'never' keep the download") {
            OfflineRules.expiredWatched(listOf(state(dirty = true)), now, 1.days, null).shouldBeEmpty()
            OfflineRules.expiredWatched(listOf(state(played = false)), now, 1.days, null).shouldBeEmpty()
            OfflineRules.expiredWatched(listOf(state()), now, 1.days, item).shouldBeEmpty()
            OfflineRules.expiredWatched(listOf(state()), now, null, null).shouldBeEmpty()
        }

        test("immediately deletes at the next run") {
            OfflineRules.expiredWatched(listOf(state(playedAt = now)), now, 0.days, null) shouldBe setOf(item)
        }
    }
})
