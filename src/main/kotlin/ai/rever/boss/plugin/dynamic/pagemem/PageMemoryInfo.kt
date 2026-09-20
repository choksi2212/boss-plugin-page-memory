package ai.rever.boss.plugin.dynamic.pagemem

import ai.rever.boss.plugin.api.Panel
import ai.rever.boss.plugin.api.Panel.Companion.bottom
import ai.rever.boss.plugin.api.Panel.Companion.left
import ai.rever.boss.plugin.api.PanelId
import ai.rever.boss.plugin.api.PanelInfo
import compose.icons.FeatherIcons
import compose.icons.feathericons.Book

/**
 * Describes the Page Memory panel: id, sidebar icon, default slot.
 *
 * Lives on the left bottom slot so it sits beside Git Status (priority 14)
 * and below the heavier workspace panels. Priority 68 keeps it after the
 * panels that load first and the user is most likely to open by default.
 */
object PageMemoryInfo : PanelInfo {
    override val id = PanelId("page-memory", 68)
    override val displayName = "Page Memory"
    override val icon = FeatherIcons.Book
    override val defaultSlotPosition: Panel = left.bottom
}
