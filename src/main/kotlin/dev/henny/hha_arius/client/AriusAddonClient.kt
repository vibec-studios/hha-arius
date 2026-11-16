package dev.henny.hha_arius.client

import dev.henny.hha.api.client.HhaClientAddon
import dev.henny.hha.api.client.HhaClientAddonContext

/** HHA-Client-Entrypoint (`"hha_client"`): Cooldown-Slots in HHAs Leiste. */
class AriusAddonClient : HhaClientAddon {
    override fun onInitializeClient(context: HhaClientAddonContext) {
        dev.henny.hha_arius.gear.client.GearClient.register(context)
    }
}
