package com.allcode.connect

import com.allcode.connect.core.WireJson
import com.allcode.connect.data.ShellState
import com.allcode.connect.data.ThreadProjection
import com.allcode.connect.data.TimelineEntry
import com.allcode.connect.data.apply
import com.allcode.connect.data.applyShellItem
import com.allcode.connect.data.timeline
import com.allcode.connect.data.unwrapShell
import com.allcode.connect.net.Pairing
import com.allcode.connect.net.PairingInputException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    private fun json(text: String): JsonObject = WireJson.parseToJsonElement(text).jsonObject

    @Test
    fun pairingAcceptsEveryLinkShapeT3Prints() {
        val cases = mapOf(
            "http://localhost:5733/pair#token=ABC" to Triple("ABC", "http://localhost:5733/", "ws://localhost:5733/"),
            "t3code://pair?pairingUrl=http%3A%2F%2F100.1.2.3%3A3773%2Fpair%23token%3DXYZ" to
                Triple("XYZ", "http://100.1.2.3:3773/", "ws://100.1.2.3:3773/"),
            "t3code-dev://pair?pairingUrl=https%3A%2F%2Fbox.tail.ts.net%2Fpair%3Ftoken%3DQ1" to
                Triple("Q1", "https://box.tail.ts.net/", "wss://box.tail.ts.net/"),
            "https://app.t3.codes/pair?host=192.168.1.5%3A3773&token=HT&label=Desk" to
                Triple("HT", "http://192.168.1.5:3773/", "ws://192.168.1.5:3773/"),
        )
        cases.forEach { (input, expected) ->
            val target = Pairing.parseLink(input)
            assertEquals(input, expected, Triple(target.credential, target.httpBaseUrl, target.wsBaseUrl))
        }
        assertThrows(PairingInputException::class.java) { Pairing.parseLink("http://localhost:3773/pair") }
        assertThrows(PairingInputException::class.java) { Pairing.parseLink("t3code://other?pairingUrl=x") }
    }

    @Test
    fun hostAndCodeDefaultsToHttpForLanAndHttpsForPublicNames() {
        assertEquals("http://192.168.1.20:3773/", Pairing.fromHostAndCode("192.168.1.20:3773", "C").httpBaseUrl)
        assertEquals("http://devbox:3773/", Pairing.fromHostAndCode("devbox:3773", "C").httpBaseUrl)
        assertEquals("https://t3.example.com/", Pairing.fromHostAndCode("t3.example.com", "C").httpBaseUrl)
        assertEquals("wss://t3.example.com/", Pairing.fromHostAndCode("https://t3.example.com/x?y", "C").wsBaseUrl)
    }

    @Test
    fun shellEnrichmentSnapshotOnlyPatchesProjectIdentity() {
        val first = null.applyShellItem(json("""{"kind":"snapshot","snapshot":{"snapshotSequence":5,
            "projects":[{"id":"p","title":"P","workspaceRoot":"/p"}],
            "threads":[{"id":"t","projectId":"p","title":"T","status":"running"}],"archivedThreads":[]}}"""))!!
        val enriched = first.applyShellItem(json("""{"kind":"snapshot","resolvedRepositoryIdentityRoots":["/p"],"snapshot":{"snapshotSequence":5,
            "projects":[{"id":"p","title":"P","workspaceRoot":"/p","repositoryIdentity":{"displayName":"org/p"}}],
            "threads":[],"archivedThreads":[]}}"""))!!
        assertEquals(1, enriched.threads.size)
        assertEquals("org/p", enriched.projects.single().repositoryName)

        val stale = enriched.applyShellItem(json("""{"kind":"thread.removed","sequence":5,"location":"active","threadId":"t"}"""))!!
        assertEquals("events at or below the snapshot are skipped", 1, stale.threads.size)
        val archived = enriched.applyShellItem(json("""{"kind":"thread.updated","sequence":6,"location":"archive",
            "thread":{"id":"t","projectId":"p","title":"T","status":"idle","archivedAt":"2026-01-01T00:00:00Z"}}"""))!!
        assertTrue(archived.threads.isEmpty())
        assertEquals(setOf("t"), archived.archived.keys)
    }

    private fun item(id: String, ordinal: Long, type: String, extra: String = "") =
        """{"id":"$id","threadId":"th","runId":"r1","nodeId":"n","ordinal":$ordinal,"status":"completed","type":"$type"$extra}"""

    private fun projection(rows: List<String>, runs: String = """[{"id":"r1","status":"running"}]"""): ThreadProjection {
        val visible = rows.joinToString(",") { """{"position":0,"visibility":"local","sourceThreadId":"th","sourceItemId":${WireJson.parseToJsonElement(it).jsonObject["id"]},"item":$it}""" }
        return ThreadProjection.fromSnapshot(json("""{"kind":"snapshot","snapshotSequence":10,"projection":{
            "thread":{"id":"th"},"runs":$runs,"attempts":[],"messages":[],"runtimeRequests":[],"plans":[],"subagents":[],
            "checkpoints":[],"providerTurns":[],"turnItems":[${rows.joinToString(",")}],"visibleTurnItems":[$visible]}}"""))!!
    }

    private fun turnItemEvent(sequence: Int, item: String) =
        json("""{"kind":"event","sequence":$sequence,"event":{"type":"turn-item.updated","threadId":"th","payload":$item}}""")

    @Test
    fun turnItemsInsertByOrdinalAndStreamInPlace() {
        val base = projection(listOf(item("u", 1, "user_message", ""","text":"hi""""), item("a", 3, "assistant_message", ""","text":"Hel","streaming":true""")))
        val inserted = base.apply(turnItemEvent(11, item("c", 2, "command_execution", ""","input":"ls"""")))
        assertEquals(listOf("u", "c", "a"), inserted.visible.map { it.sourceItemId })
        val streamed = inserted.apply(turnItemEvent(12, item("a", 3, "assistant_message", ""","text":"Hello","streaming":false""")))
            .apply(json("""{"kind":"event","sequence":13,"event":{"type":"run.updated","threadId":"th","payload":{"id":"r1","status":"completed"}}}"""))
            .apply(turnItemEvent(14, item("k", 4, "checkpoint")))
        assertEquals(listOf("u", "c", "a", "k"), streamed.visible.map { it.sourceItemId })
        // A checkpoint alone renders nothing, rather than an empty "Worked" line.
        val entries = streamed.timeline()
        assertEquals(listOf("User", "Work", "Assistant"), entries.map { it::class.simpleName })
        assertEquals("Hello", (entries.last() as TimelineEntry.Assistant).text)
        assertEquals("a replayed event is ignored", streamed, streamed.apply(turnItemEvent(14, item("x", 9, "reasoning"))))
    }

    @Test
    fun rolledBackRunsDisappearFromTheTranscript() {
        val base = projection(listOf(item("u", 1, "user_message", ""","text":"hi"""")))
        val rolledBack = base.apply(json("""{"kind":"event","sequence":11,"event":{"type":"run.updated","threadId":"th",
            "payload":{"id":"r1","status":"rolled_back"}}}"""))
        assertTrue(rolledBack.visible.isEmpty())
    }

    @Test
    fun unifiedDiffParsesFilesHunksAndLineNumbers() {
        val files = com.allcode.connect.ui.diff.parseUnifiedDiff(
            """
            diff --git a/src/a.kt b/src/a.kt
            index 1..2 100644
            --- a/src/a.kt
            +++ b/src/a.kt
            @@ -10,3 +10,3 @@ fun main() {
             keep
            -old
            +new
            diff --git a/img.png b/img.png
            Binary files a/img.png and b/img.png differ
            diff --git a/new.txt b/new.txt
            new file mode 100644
            --- /dev/null
            +++ b/new.txt
            @@ -0,0 +1,2 @@
            +one
            +two
            """.trimIndent(),
        )
        assertEquals(listOf("src/a.kt", "img.png", "new.txt"), files.map { it.path })
        assertEquals(1 to 1, files[0].additions to files[0].deletions)
        assertEquals(listOf(null, 10, 11, null), files[0].lines.map { it.oldNo })
        assertEquals(listOf(null, 10, null, 11), files[0].lines.map { it.newNo })
        assertTrue(files[1].binary)
        assertEquals(2 to 0, files[2].additions to files[2].deletions)
    }

    @Test
    fun composerSuggestionsReplaceOnlyTheTokenBeingTyped() {
        val file = com.allcode.connect.ui.thread.ComposerSuggestion("a.kt", "src/a.kt", "@src/a.kt ")
        assertEquals("fix @src/a.kt ", com.allcode.connect.ui.thread.applySuggestion("fix @sr", file))
        assertEquals('/' to "rev", com.allcode.connect.ui.thread.activeToken("/rev"))
        assertNull(com.allcode.connect.ui.thread.activeToken("/review now"))
        assertNull(com.allcode.connect.ui.thread.activeToken("email me@ "))
    }

    @Test
    fun shellWrappersUnwrapToTheCommand() {
        assertEquals("pwd && ls", unwrapShell("/bin/bash -lc \"pwd && ls\""))
        assertEquals("echo 'x'", unwrapShell("bash -c \"echo 'x'\""))
        assertEquals("git status", unwrapShell("git status"))
        assertNull(null)
    }
}
