package org.jellyfin.mobile.offline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.jellyfin.mobile.offline.playback.PlayStateRules

class PlayStateRulesTest : FunSpec({
    val minute = 60L * 10_000_000L
    val longVideo = 60 * minute
    val shortVideo = 2 * minute

    test("reaching the end marks played") {
        PlayStateRules.onStop(positionTicks = 10 * minute, runTimeTicks = longVideo, finished = true, wasPlayed = false) shouldBe
            PlayStateRules.Result(played = true, positionTicks = 0)
    }

    test("stopping in the middle keeps the position") {
        PlayStateRules.onStop(positionTicks = 30 * minute, runTimeTicks = longVideo, finished = false, wasPlayed = false) shouldBe
            PlayStateRules.Result(played = false, positionTicks = 30 * minute)
    }

    test("stopping after 90 percent marks played") {
        PlayStateRules.onStop(positionTicks = 55 * minute, runTimeTicks = longVideo, finished = false, wasPlayed = false) shouldBe
            PlayStateRules.Result(played = true, positionTicks = 0)
    }

    test("stopping in the first 5 percent resets the position") {
        PlayStateRules.onStop(positionTicks = 2 * minute, runTimeTicks = longVideo, finished = false, wasPlayed = false) shouldBe
            PlayStateRules.Result(played = false, positionTicks = 0)
    }

    test("short videos count as played once started, like on the server") {
        PlayStateRules.onStop(positionTicks = minute, runTimeTicks = shortVideo, finished = false, wasPlayed = false) shouldBe
            PlayStateRules.Result(played = true, positionTicks = 0)
    }

    test("unknown run time never counts as played") {
        PlayStateRules.onStop(positionTicks = 5 * minute, runTimeTicks = 0, finished = false, wasPlayed = false) shouldBe
            PlayStateRules.Result(played = false, positionTicks = 5 * minute)
    }
})
