package dev.henny.hha_arius.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** Sichtprüfung "dragon": nur mit -Darius.verify=dragon (siehe [VerifyKit]). */
class VerifyDragon : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) = VerifyKit.run(context, "dragon") {
        startStage(1)
        tick(60)
        shot("smoke")
    }
}
