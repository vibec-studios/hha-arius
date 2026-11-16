package dev.henny.hha_arius.gametest

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext

/** Reaching this test requires successful main and client entrypoint initialization. */
class StartupTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use {
            context.waitTicks(40)
            context.runOnClient<RuntimeException> { client ->
                check(client.world != null) { "Client world did not load" }
                check(client.player != null) { "Client player did not load" }
            }
        }
    }
}
