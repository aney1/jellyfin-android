package org.jellyfin.mobile.offline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.offline.playback.PlayStateRules

class PlayStateRulesTest : FunSpec({
    val minute = 60L * 10_000_000L
    val longVideo = 60 * minute
    val shortVideo = 2 * minute

    fun stop(positionTicks: Long, runTimeTicks: Long = longVideo, finished: Boolean = false) =
        PlayStateRules.onStop(positionTicks, runTimeTicks, finished, wasPlayed = false)

    fun result(played: Boolean, positionTicks: Long) = PlayStateRules.Result(played, positionTicks)

    test("reaching the end marks played") {
        stop(positionTicks = 10 * minute, finished = true) shouldBe result(played = true, positionTicks = 0)
    }

    test("stopping in the middle keeps the position") {
        stop(positionTicks = 30 * minute) shouldBe result(played = false, positionTicks = 30 * minute)
    }

    test("stopping after 90 percent marks played") {
        stop(positionTicks = 55 * minute) shouldBe result(played = true, positionTicks = 0)
    }

    test("stopping in the first 5 percent resets the position") {
        stop(positionTicks = 2 * minute) shouldBe result(played = false, positionTicks = 0)
    }

    test("short videos count as played once started, like on the server") {
        stop(positionTicks = minute, runTimeTicks = shortVideo) shouldBe result(played = true, positionTicks = 0)
    }

    test("unknown run time never counts as played") {
        stop(positionTicks = 5 * minute, runTimeTicks = 0) shouldBe result(played = false, positionTicks = 5 * minute)
    }
})
