package dev.henny.hha_arius.boss

import dev.henny.hha_arius.HhaArius
import net.minecraft.server.network.ServerPlayerEntity

/** Vergibt Fortschritte `hha_arius:<id>` (alle offenen Kriterien), falls es sie gibt. */
object Advancements {
    fun grant(player: ServerPlayerEntity, id: String) {
        val server = player.entityWorld.server ?: return
        val entry = server.advancementLoader.get(HhaArius.id(id)) ?: return
        val progress = player.advancementTracker.getProgress(entry)
        for (criterion in progress.unobtainedCriteria.toList()) player.advancementTracker.grantCriterion(entry, criterion)
    }
}
