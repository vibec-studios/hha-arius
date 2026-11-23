package dev.henny.hha_arius.client.fx

import dev.henny.hha_arius.net.SceneSoundS2C
import net.minecraft.client.MinecraftClient
import net.minecraft.client.sound.PositionedSoundInstance
import net.minecraft.sound.SoundCategory
import net.minecraft.sound.SoundEvent
import net.minecraft.util.math.Vec3d
import net.minecraft.util.math.random.Random
import kotlin.math.max

/**
 * Szenenklänge ([SceneSoundS2C]) am Hörer. Minecraft dämpft Klänge linear bis 16 × Lautstärke um die **Kamera**; in
 * den Szenen filmt die HHA-Kamera aber oft weit weg vom Körper der Spieler (Bergspaltung: Kamera rund 290 Blöcke von der
 * Quelle, Hörweite rund 210). Solche Klänge waren stumm. Hier wird eine zu ferne Quelle auf der Linie Kamera → Quelle bis
 * auf [PULL] der Hörweite herangerückt: Richtung und Raumklang bleiben, der Klang ist mindestens zu 1 − [PULL] hörbar.
 * Nahe Quellen spielen unverändert an ihrem Ort.
 */
object SceneSound {

    /** Anteil der Hörweite, bis zu dem eine ferne Quelle an die Kamera heranrückt. */
    private const val PULL = 0.4

    fun play(p: SceneSoundS2C, client: MinecraftClient) {
        if (client.world == null) return
        val cam = client.gameRenderer.camera.cameraPos
        val source = Vec3d(p.x, p.y, p.z)
        val reach = 16.0 * max(p.volume, 1f) * PULL
        val off = source.subtract(cam)
        val d = off.length()
        val at = if (d > reach) cam.add(off.multiply(reach / d)) else source
        client.soundManager.play(
            PositionedSoundInstance(SoundEvent.of(p.sound), SoundCategory.HOSTILE, p.volume, p.pitch, Random.create(), at.x, at.y, at.z),
        )
    }
}
