package com.allcode.connect

import android.content.Context
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.rememberNavController
import androidx.test.core.app.ApplicationProvider
import com.allcode.connect.core.WireJson
import com.allcode.connect.data.ConnectionStatus
import com.allcode.connect.data.EnvironmentConnection
import com.allcode.connect.data.SavedEnvironment
import com.allcode.connect.data.ServerConfigState
import com.allcode.connect.data.ThemeMode
import com.allcode.connect.data.ThreadProjection
import com.allcode.connect.data.applyShellItem
import com.allcode.connect.ui.Navigator
import com.allcode.connect.ui.home.MainScreen
import com.allcode.connect.ui.machines.MachineScreen
import com.allcode.connect.ui.newtask.NewTaskScreen
import com.allcode.connect.ui.pair.PairScreen
import com.allcode.connect.ui.settings.SettingsScreen
import com.allcode.connect.ui.theme.AllCodeTheme
import com.allcode.connect.ui.thread.ThreadScreen
import com.allcode.connect.ui.thread.ThreadViewModel
import com.allcode.connect.ui.usage.UsageScreen
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.jsonObject
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Renders real screens with fake machine state so the design can be reviewed without a device.
 * Opt in with ALLCODE_SCREENSHOTS=1; images land in app/build/screens.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val env = "env-studio"
    private val threadId = "thread-flaky"

    @Before
    fun setUp() {
        assumeTrue(System.getenv("ALLCODE_SCREENSHOTS") != null)
        // Reduced motion keeps breathing marks and timers from animating while capturing.
        Settings.Global.putFloat(ApplicationProvider.getApplicationContext<Context>().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        compose.mainClock.autoAdvance = false
    }

    private val graph: AppGraph get() = ApplicationProvider.getApplicationContext<AllCodeApp>().graph

    private fun ago(minutes: Long) = Instant.now().minus(minutes, ChronoUnit.MINUTES).toString()

    private fun thread(
        id: String, title: String, project: String, status: String, minutesAgo: Long,
        pending: String? = null, message: String? = null, branch: String? = null, error: String? = null,
        pinned: Boolean = false, prs: Int = 0, completedAgo: Long? = null, visitedAgo: Long? = null,
    ) = """{"id":"$id","projectId":"$project","title":"$title","providerInstanceId":"claudeAgent",
        "modelSelection":{"instanceId":"claudeAgent","model":"claude-opus-5-5","options":[{"id":"effort","value":"high"}]},
        "runtimeMode":"auto-accept-edits","interactionMode":"default","status":"$status",
        "branch":${branch?.let { "\"$it\"" } ?: "null"},"worktreePath":null,
        "pendingRuntimeRequest":${pending?.let { """{"id":"req-$id","kind":"$it","createdAt":"${ago(1)}","blocking":true}""" } ?: "null"},
        "latestVisibleMessage":${message?.let { """{"id":"m-$id","role":"assistant","text":"$it","updatedAt":"${ago(minutesAgo)}"}""" } ?: "null"},
        "lastError":${error?.let { "\"$it\"" } ?: "null"},
        "lineage":{"parentThreadId":null,"rootThreadId":"$id"},"pinnedAt":${if (pinned) "\"${ago(600)}\"" else "null"},
        "archivedAt":null,"snoozedUntil":null,"settledAt":null,"createdAt":"${ago(minutesAgo + 30)}","updatedAt":"${ago(minutesAgo)}",
        "latestRunCompletedAt":${completedAgo?.let { "\"${ago(it)}\"" } ?: "null"},
        "lastVisitedAt":${visitedAgo?.let { "\"${ago(it)}\"" } ?: "null"},
        "pullRequests":[${(1..prs).joinToString(",") { "{}" }}],"hasActionableProposedPlan":false,"visibleItemCount":12}"""

    private fun shell() = null.applyShellItem(
        WireJson.parseToJsonElement(
            """{"kind":"snapshot","snapshot":{"snapshotSequence":40,"projects":[
            {"id":"p-allcode","title":"AllCode","workspaceRoot":"/home/me/Coding/AllCode","updatedAt":"${ago(5)}"},
            {"id":"p-bot","title":"melonbot","workspaceRoot":"/home/me/Coding/melonbot","updatedAt":"${ago(90)}"},
            {"id":"p-site","title":"marketing-site","workspaceRoot":"/home/me/Coding/site","updatedAt":"${ago(900)}"}],
            "threads":[
            ${thread("t1", "Migrate sign-in to passkeys", "p-allcode", "running", 1, pending = "command", branch = "feat/passkeys")},
            ${thread(threadId, "Fix the flaky checkout test", "p-allcode", "running", 2, message = "Running the suite 50 times with the shared clock removed…")},
            ${thread("t3", "Port the bot core to Rust", "p-bot", "completed", 14, message = "All 48 commands are ported and the integration suite passes on the Rust build.", completedAgo = 14, visitedAgo = 60, pinned = true)},
            ${thread("t4", "Add dark mode to settings", "p-site", "completed", 130, message = "Dark mode follows the system and remembers an override.", branch = "feat/dark-settings", prs = 1, completedAgo = 130, visitedAgo = 100)},
            ${thread("t5", "Investigate the memory spike", "p-bot", "failed", 200, error = "The agent hit its usage limit; it resets at 6:00 PM.")},
            ${thread("t6", "Write release notes for 0.4", "p-site", "completed", 300, message = "Drafted notes for 0.4 with the three headline features first.", completedAgo = 300, visitedAgo = 290)}
            ],"archivedThreads":[]}}""",
        ).jsonObject,
    )

    private fun config() = ServerConfigState.from(
        WireJson.parseToJsonElement(
            """{"cwd":"/home/me","providers":[
            {"instanceId":"claudeAgent","driver":"claudeAgent","displayName":"Claude","enabled":true,"status":"ready",
             "models":[{"slug":"claude-opus-5-5","name":"Claude Opus 5.5","capabilities":{"optionDescriptors":[{"id":"effort","label":"Effort","type":"select","options":[{"id":"low","label":"Low"},{"id":"high","label":"High","isDefault":true},{"id":"max","label":"Max"}]}]}},
                       {"slug":"claude-sonnet-5-5","name":"Claude Sonnet 5.5"}],
             "usageLimits":{"windows":[{"label":"Session","usedPercent":38,"resetsAt":"${Instant.now().plus(3, ChronoUnit.HOURS)}"},{"label":"Weekly","usedPercent":71,"resetsAt":"${Instant.now().plus(2, ChronoUnit.DAYS)}"}]}},
            {"instanceId":"codex","driver":"codex","displayName":"Codex","enabled":true,"status":"ready",
             "models":[{"slug":"gpt-6.1-sol","name":"GPT-6.1-Sol"}],
             "usageLimits":{"windows":[{"label":"Session","usedPercent":12,"resetsAt":"${Instant.now().plus(4, ChronoUnit.HOURS)}"},{"label":"Weekly","usedPercent":91,"resetsAt":"${Instant.now().plus(1, ChronoUnit.DAYS)}"}]}}
            ]}""",
        ).jsonObject,
    )

    private fun machine(status: ConnectionStatus = ConnectionStatus.Connected): EnvironmentConnection {
        val existing = graph.connections.connection(env)
        if (existing != null) return existing
        val connection = EnvironmentConnection(
            SavedEnvironment(env, "studio", "http://192.0.2.10:3773/", "ws://192.0.2.10:3773/", "token", 0, "linux", "0.1.7"),
            emptyMap(),
            graph.scope,
        )
        connection.showForPreview(status, shell(), config())
        graph.connections.adoptForPreview(connection)
        return connection
    }

    private fun item(id: String, ordinal: Int, type: String, minutesAgo: Long, extra: String, status: String = "completed") =
        """{"position":0,"visibility":"local","sourceThreadId":"$threadId","sourceItemId":"$id","item":{"id":"$id","threadId":"$threadId","runId":"r1","nodeId":"n",
        "ordinal":$ordinal,"status":"$status","type":"$type","startedAt":"${ago(minutesAgo)}","completedAt":"${ago(minutesAgo)}","updatedAt":"${ago(minutesAgo)}"$extra}}"""

    private fun projection(): ThreadProjection {
        val reply = "The flake comes from a **shared fake clock**. `cart.test.ts` freezes time in `beforeAll`, so any test that runs after it inherits a frozen `Date.now()`.\\n\\nI moved the clock into each test:\\n\\n```ts\\nbeforeEach(() => vi.useFakeTimers())\\nafterEach(() => vi.useRealTimers())\\n```\\n\\nNow I want to run the suite 50 times to be sure."
        val rows = listOf(
            item("u1", 1, "user_message", 9, ""","text":"The checkout test fails about one run in five. Find out why and fix it.","attachments":[]"""),
            item("w1", 2, "reasoning", 9, ""","text":"**Looking at how the test suite shares state**","streaming":false"""),
            item("w2", 3, "command_execution", 8, ""","input":"/bin/bash -lc \"pnpm test checkout --repeat 20\"","exitCode":1,"title":"pnpm test checkout --repeat 20""""),
            item("w3", 4, "dynamic_tool", 7, ""","toolName":"read","title":"Read cart.test.ts""""),
            item("w4", 5, "file_change", 6, ""","fileName":"src/checkout/cart.test.ts","additions":12,"deletions":4,"changes":[{"path":"src/checkout/cart.test.ts","operation":"update"}]"""),
            item("a1", 6, "assistant_message", 5, ""","text":"$reply","streaming":false"""),
            item("td", 7, "todo_list", 5, ""","planId":"plan1","steps":[{"text":"Find what the flaky runs share","status":"completed"},{"text":"Isolate the fake clock per test","status":"completed"},{"text":"Run the suite 50 times","status":"running"}]"""),
            item("ap", 8, "approval_request", 1, ""","requestId":"req-1","requestKind":"command","prompt":"pnpm test checkout --repeat 50"""", status = "waiting"),
        )
        return ThreadProjection.fromSnapshot(
            WireJson.parseToJsonElement(
                """{"kind":"snapshot","snapshotSequence":20,"projection":{
                "thread":{"id":"$threadId","projectId":"p-allcode","title":"Fix the flaky checkout test","runtimeMode":"auto-accept-edits","interactionMode":"default",
                  "modelSelection":{"instanceId":"claudeAgent","model":"claude-opus-5-5","options":[{"id":"effort","value":"high"}]}},
                "runs":[{"id":"r1","status":"waiting"}],"attempts":[],"messages":[],
                "runtimeRequests":[{"id":"req-1","kind":"command","status":"pending"}],
                "plans":[],"subagents":[],"checkpoints":[],"providerTurns":[],
                "turnItems":[${rows.joinToString(",") { it.substringAfter("\"item\":").dropLast(1) }}],
                "visibleTurnItems":[${rows.joinToString(",")}]}}""",
            ).jsonObject,
        )!!
    }

    private fun shoot(name: String, dark: Boolean, content: @Composable (Navigator) -> Unit) {
        compose.setContent {
            val navigator = Navigator(rememberNavController())
            AllCodeTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) { content(navigator) }
        }
        compose.mainClock.advanceTimeBy(1_500)
        compose.onRoot().captureRoboImage("build/screens/$name-${if (dark) "dark" else "light"}.png")
    }

    @Test fun onboardingLight() = shoot("01-onboarding", false) { PairScreen(graph, null, false, {}) {} }
    @Test fun onboardingDark() = shoot("01-onboarding", true) { PairScreen(graph, null, false, {}) {} }

    @Test fun homeLight() { machine(); shoot("02-home", false) { MainScreen(graph, it) } }
    @Test fun homeDark() { machine(); shoot("02-home", true) { MainScreen(graph, it) } }

    private fun threadScreen(dark: Boolean, name: String = "03-thread", status: ConnectionStatus = ConnectionStatus.Connected) {
        machine(status)
        val model = ViewModelProvider(
            compose.activity,
            viewModelFactory { initializer { ThreadViewModel(graph, env, threadId) } },
        )["$env/$threadId", ThreadViewModel::class.java]
        model.showForPreview(projection())
        shoot(name, dark) { ThreadScreen(graph, it, env, threadId) }
    }

    @Test fun threadLight() = threadScreen(false)
    @Test fun threadDark() = threadScreen(true)

    @Test fun newTaskLight() { machine(); shoot("04-new-task", false) { NewTaskScreen(graph, it, env, "p-allcode") } }
    @Test fun machineLight() { machine(); shoot("05-machine", false) { MachineScreen(graph, it, env) } }
    @Test fun usageDark() { machine(); shoot("06-usage", true) { UsageScreen(graph, it, env) } }
    @Test fun settingsLight() = shoot("07-settings", false) { SettingsScreen(graph, it) }

    @Test fun threadReconnecting() = threadScreen(
        dark = false,
        name = "08-reconnecting",
        status = ConnectionStatus.Retrying("Couldn't open a connection to studio.", System.currentTimeMillis() + 8_000),
    )

    @Test fun logo() = shoot("09-logo", true) {
        androidx.compose.foundation.layout.Row(
            androidx.compose.ui.Modifier.background(androidx.compose.ui.graphics.Color(0xFF262624)),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.Image(
                androidx.compose.ui.res.painterResource(R.drawable.ic_launcher_foreground),
                null,
                androidx.compose.ui.Modifier.size(216.dp),
            )
            com.allcode.connect.ui.components.AllCodeMark(140.dp)
        }
    }
}
