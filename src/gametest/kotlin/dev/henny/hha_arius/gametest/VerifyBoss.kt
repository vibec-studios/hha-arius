package dev.henny.hha_arius.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** Sichtprüfung "boss": nur mit -Darius.verify=boss (siehe [VerifyKit]). */
class VerifyBoss : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) = VerifyKit.run(context, "boss") {
        startStage(1)
        tick(60)
        shot("smoke")
    }
}
