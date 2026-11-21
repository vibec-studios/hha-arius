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
                // Seit 1.10.0: Selbsttest des Jagdwegs (durchgehender Boden, Kopffreiheit, keine Stufen > 0,6).
                val issues = dev.henny.hha_arius.arena.RouteCheck.plan(20)
                check(issues.isEmpty()) { "Jagdweg unterbrochen: " + issues.joinToString("; ") }
            }
        }
    }
}
