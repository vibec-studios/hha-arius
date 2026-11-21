package dev.henny.hha_arius.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** Sichtprüfung "hunt": nur mit -Darius.verify=hunt (siehe [VerifyKit]). */
class VerifyHunt : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) = VerifyKit.run(context, "hunt") {
        startStage(1)
        tick(60)
        shot("smoke")
    }
}
