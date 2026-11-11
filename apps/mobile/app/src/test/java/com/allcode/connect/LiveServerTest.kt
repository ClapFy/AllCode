package com.allcode.connect

import com.allcode.connect.core.asObject
import com.allcode.connect.data.Commands
import com.allcode.connect.data.ConnectionStatus
import com.allcode.connect.data.EnvironmentConnection
import com.allcode.connect.data.ModelSelection
import com.allcode.connect.data.SavedEnvironment
import com.allcode.connect.data.ThreadFeed
import com.allcode.connect.data.TimelineEntry
import com.allcode.connect.data.WorkspaceStrategy
import com.allcode.connect.data.timeline
import com.allcode.connect.data.uploadAttachmentBytes
import kotlinx.serialization.json.put
import com.allcode.connect.net.EnvironmentHttp
import com.allcode.connect.net.Pairing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/**
 * Talks to a real T3 dev server. Opt in with T3_DEV_URL plus T3_DEV_BEARER (or a fresh
 * T3_DEV_TOKEN from `t3 pair`). Uses the free Replay (dev) provider, never a paid one.
 */
class LiveServerTest {
    private val url = System.getenv("T3_DEV_URL")

    private fun bearer(): String = runBlocking {
        System.getenv("T3_DEV_BEARER")?.let { return@runBlocking it }
        val token = System.getenv("T3_DEV_TOKEN") ?: error("set T3_DEV_BEARER or T3_DEV_TOKEN")
        val target = Pairing.fromHostAndCode(url, token)
        EnvironmentHttp(target.httpBaseUrl, { null }).redeemPairingCredential(token, "AllCode live test").accessToken
    }

    @Test
    fun pairsSyncsAndAnswersAnApprovalEndToEnd() = runBlocking {
        assumeTrue(url != null)
        val bearer = bearer()
        val target = Pairing.normalizeHost(url)
        val http = EnvironmentHttp(Pairing.httpBase(target), { bearer })
        val descriptor = http.descriptor()
        assertEquals(2, descriptor.protocolVersion)

        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val connection = EnvironmentConnection(
            SavedEnvironment(descriptor.environmentId, descriptor.label, Pairing.httpBase(target), Pairing.wsBase(target), bearer, 0),
            mapOf("clientSurface" to "mobile", "clientOs" to "android"),
            scope,
        )
        try {
            connection.start()
            withTimeout(20_000) { connection.status.first { it == ConnectionStatus.Connected } }
            val shell = connection.shell.value!!
            assertTrue(shell.projects.isNotEmpty())

            // A lost network reads as disconnected at once, not after the ping timeout, and heals on its own.
            connection.drop("This phone is offline.")
            val lost = withTimeout(2_000) { connection.status.first { it != ConnectionStatus.Connected } }
            assertTrue(lost is ConnectionStatus.Retrying && lost.message == "This phone is offline.")
            withTimeout(20_000) { connection.status.first { it == ConnectionStatus.Connected } }
            withTimeout(10_000) { connection.config.first { it?.provider("devReplay") != null } }

            val threadId = UUID.randomUUID().toString()
            connection.call(
                "orchestration.launchThread",
                Commands.launchThread(
                    threadId = threadId,
                    projectId = shell.projects.first().id,
                    text = "Run the approval replay",
                    modelSelection = ModelSelection("devReplay", "approval"),
                    runtimeMode = "approval-required",
                    interactionMode = "default",
                    workspace = WorkspaceStrategy.Root(),
                ),
            )
            val feed = connection.thread(threadId).filterIsInstance<ThreadFeed.Ready>().map { it.projection }
            val waiting = withTimeout(30_000) {
                feed.first { p -> p.timeline().any { it is TimelineEntry.Approval && it.pending } }
            }
            val approval = waiting.timeline().filterIsInstance<TimelineEntry.Approval>().single { it.pending }
            val result = connection.dispatch(Commands.respondApproval(threadId, approval.requestId, "accept"))
            assertTrue(result.asObject()?.containsKey("sequence") == true)
            val done = withTimeout(30_000) {
                feed.first { p -> p.runs.any { it["status"].toString().contains("completed") } }
            }
            val entries = done.timeline()
            assertTrue(entries.any { it is TimelineEntry.Approval && !it.pending && it.decision == "accept" })
            assertTrue(entries.any { it is TimelineEntry.Assistant && it.text.isNotBlank() })

            // An image goes up through a signed URL, rides on the message, and comes back as an asset.
            val png = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==")
            val record = connection.uploadAttachmentBytes(png, "dot.png", "image/png")
            val imageThread = UUID.randomUUID().toString()
            connection.call(
                "orchestration.launchThread",
                Commands.launchThread(
                    threadId = imageThread,
                    projectId = shell.projects.first().id,
                    text = "What is in this image?",
                    modelSelection = ModelSelection("devReplay", "showcase"),
                    runtimeMode = "full-access",
                    interactionMode = "default",
                    workspace = WorkspaceStrategy.Root(),
                    attachments = listOf(record),
                ),
            )
            val withImage = withTimeout(30_000) {
                connection.thread(imageThread).filterIsInstance<ThreadFeed.Ready>().map { it.projection }
                    .first { p -> p.timeline().any { it is TimelineEntry.User && it.attachments.isNotEmpty() } }
            }
            val sent = withImage.timeline().filterIsInstance<TimelineEntry.User>().first { it.attachments.isNotEmpty() }
            val id = sent.attachments.single()["id"].toString().trim('"')
            val asset = connection.call("assets.createUrl", kotlinx.serialization.json.buildJsonObject {
                put("resource", kotlinx.serialization.json.buildJsonObject {
                    put("_tag", "attachment"); put("attachmentId", id); put("disposition", "inline")
                })
            }).asObject()!!
            val fetched = connection.http.getBytes(asset["relativeUrl"].toString().trim('"'))
            assertTrue(fetched.contentEquals(png))
        } finally {
            connection.stop()
            scope.cancel()
        }
    }
}
