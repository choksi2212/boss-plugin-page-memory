package ai.rever.boss.plugin.dynamic.pagemem

import ai.rever.boss.plugin.api.PanelComponentWithUI
import ai.rever.boss.plugin.api.PanelInfo
import androidx.compose.runtime.Composable
import com.arkivanov.decompose.ComponentContext

/**
 * The Page Memory panel component.
 *
 * Wraps [PageMemoryViewModel] and exposes its content. The component does
 * NOT keep a long-lived observer on browser-tab events; [ActiveTabWatcher]
 * owns that. The component is a passive UI surface; [refreshRecent] is
 * called once when the panel first opens.
 */
class PageMemoryComponent(
    ctx: ComponentContext,
    override val panelInfo: PanelInfo,
    private val store: PageMemoryStore,
    private val activeTabsProvider: ai.rever.boss.plugin.api.ActiveTabsProvider?,
) : PanelComponentWithUI, ComponentContext by ctx {

    private val viewModel = PageMemoryViewModel(store, activeTabsProvider)

    init {
        // Load the recent list once when the panel first opens.
        viewModel.refreshRecent()
    }

    @Composable
    override fun Content() {
        PageMemoryContent(viewModel = viewModel)
    }
}
