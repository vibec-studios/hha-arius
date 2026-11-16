package dev.henny.hha_arius

import dev.henny.hha.api.HhaAddon
import dev.henny.hha.api.HhaAddonContext
import dev.henny.hha_arius.boss.BossConfig
import dev.henny.hha_arius.gear.Gear

/**
 * HHA-Entrypoint (`"hha"`): Alles, was HHAs Addon-API anbietet, läuft hierüber: Items und Set (→ automatisches Kit
 * `/hha kit hha_arius.eirholm`), Abilities auf den HHA-Tasten, dynamische Lore und Config-Schlüssel in `/hha list`.
 */
class AriusAddon : HhaAddon {
    override fun onInitialize(context: HhaAddonContext) {
        BossConfig.register(context)
        Gear.register(context)
    }
}
