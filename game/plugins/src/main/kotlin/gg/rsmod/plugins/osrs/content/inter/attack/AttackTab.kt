package gg.rsmod.plugins.osrs.content.inter.attack

import gg.rsmod.game.model.entity.Player
import gg.rsmod.plugins.osrs.api.helper.setVarp

/**
 * @author Tom <rspsmods@gmail.com>
 */
object AttackTab {

    const val ATTACK_STYLE_VARP = 43
    const val AUTO_RETALIATE_VARP = 172

    const val ENERGY_VARP = 300
    const val ENABLED_VARP = 301

    fun setEnergy(p: Player, amount: Int) {
        check(amount in 0..100)
        p.setVarp(ENERGY_VARP, amount * 10)
    }
}