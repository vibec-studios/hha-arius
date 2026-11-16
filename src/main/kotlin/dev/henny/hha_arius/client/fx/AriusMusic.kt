package dev.henny.hha_arius.client.fx

import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.MovingSoundInstance
import net.minecraft.client.sound.SoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.Identifier
import net.minecraft.util.math.random.Random

/** Kampfmusik in Dauerschleife mit weichem Überblenden; Vanilla-Musik pausiert so lange. */
object AriusMusic {

    private class Loop(event: SoundEvent) : MovingSoundInstance(event, SoundCategory.MUSIC, Random.create()) {
        var target = 1f
        var fadeTicks = 20

        init {
            repeat = true
            repeatDelay = 0
            relative = true
            attenuationType = SoundInstance.AttenuationType.NONE
            volume = 0.01f
        }

        override fun tick() {
            val step = 1f / fadeTicks.coerceAtLeast(1)
            volume = if (volume < target) (volume + step).coerceAtMost(target) else (volume - step).coerceAtLeast(target)
            if (target <= 0f && volume <= 0.001f) setDone()
        }

        fun stopNow() = setDone()
    }

    private var current: Loop? = null
    private var currentId: String = ""

    fun handle(event: String, fade: Int) {
        val client = MinecraftClient.getInstance()
        if (event == currentId && current != null) return
        current?.let { it.target = 0f; it.fadeTicks = fade.coerceAtLeast(1) }
        current = null
        currentId = event
        if (event.isEmpty()) return
        val loop = Loop(SoundEvent.of(Identifier.of(event)))
        loop.fadeTicks = fade.coerceAtLeast(1)
        current = loop
        client.soundManager.play(loop)
    }

    fun tick() {
        if (current != null) MinecraftClient.getInstance().musicTracker.stop()
    }

    fun clear() {
        current?.stopNow()
        current = null
        currentId = ""
    }
}
