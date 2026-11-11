package com.allcode.connect.ui.home

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.MergeType
import androidx.compose.material.icons.rounded.Forum
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.allcode.connect.AppGraph
import com.allcode.connect.data.ThreadPhase
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.components.Divider
import com.allcode.connect.ui.components.Haptics
import com.allcode.connect.ui.components.pressable
import com.allcode.connect.ui.machines.MachinesTab
import com.allcode.connect.ui.prs.PullRequestsTab
import com.allcode.connect.ui.theme.AllCode

private data class Tab(val label: String, val icon: ImageVector)

private val TABS = listOf(
    Tab("Tasks", Icons.Rounded.Forum),
    Tab("Pull requests", Icons.Rounded.MergeType),
    Tab("Machines", Icons.Rounded.Computer),
)

@Composable
fun MainScreen(graph: AppGraph, navigator: Navigator) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val threads by graph.connections.threads.collectAsStateWithLifecycle(emptyList())
    val needsYou = remember(threads) { threads.count { it.thread.phase == ThreadPhase.NeedsYou && !it.thread.archived && !it.thread.isSubagent } }
    val view = LocalView.current

    BackHandler(enabled = tab != 0) { tab = 0 }

    Column(Modifier.fillMaxSize().background(AllCode.colors.paper)) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                0 -> TasksTab(graph, navigator)
                1 -> PullRequestsTab(graph, navigator)
                else -> MachinesTab(graph, navigator)
            }
        }
        Divider()
        Row(Modifier.fillMaxWidth().navigationBarsPadding().height(56.dp)) {
            TABS.forEachIndexed { index, item ->
                val selected = tab == index
                val tint = if (selected) AllCode.colors.ink else AllCode.colors.inkFaint
                Column(
                    Modifier.weight(1f).fillMaxHeight().pressable(haptic = false) {
                        if (tab != index && Haptics.enabled) view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        tab = index
                    },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Box {
                        Icon(item.icon, item.label, Modifier.size(21.dp), tint = tint)
                        if (index == 0 && needsYou > 0) {
                            Text(
                                "$needsYou",
                                style = AllCode.type.monoSmall.copy(fontSize = 10.sp, lineHeight = 12.sp),
                                color = AllCode.colors.onClay,
                                modifier = Modifier.align(Alignment.TopEnd).offset(x = 10.dp, y = (-4).dp)
                                    .clip(RoundedCornerShape(4.dp)).background(AllCode.colors.clay).padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                    Spacer(Modifier.height(3.dp))
                    Text(item.label.lowercase(), style = AllCode.type.monoSmall, color = tint)
                }
            }
        }
    }
}
