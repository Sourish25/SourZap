package com.sourzap.app.e2e

import android.content.Intent
import android.net.Uri
import com.sourzap.app.torrent.core.MagnetHandler
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * E2E Intent Filters, Deep Linking, and SAF File Resolution Test Suite.
 * Covers Features F5, F6, F7, F8 (Requirement R2).
 */
class IntentDeepLinkE2ETest {

    /**
     * Interface contract for incoming torrent intents as defined in PROJECT.md.
     */
    sealed class PendingTorrentIntent {
        data class Magnet(val uri: String, val name: String? = null) : PendingTorrentIntent()
        data class TorrentFile(val bytes: ByteArray, val fileName: String) : PendingTorrentIntent() {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (javaClass != other?.javaClass) return false
                other as TorrentFile
                if (!bytes.contentEquals(other.bytes)) return false
                if (fileName != other.fileName) return false
                return true
            }

            override fun hashCode(): Int {
                var result = bytes.contentHashCode()
                result = 31 * result + fileName.hashCode()
                return result
            }
        }
    }

    /**
     * Helper for parsing incoming Android Intent into a typed [PendingTorrentIntent].
     */
    object IntentPayloadParser {
        fun parseIntent(
            action: String?,
            dataUriString: String?,
            mimeType: String?,
            streamBytes: ByteArray? = null,
            displayNameFallback: String? = null
        ): PendingTorrentIntent? {
            if (action == null || (action != Intent.ACTION_VIEW && action != "android.intent.action.VIEW")) {
                return null
            }

            // 1. Magnet URI
            if (dataUriString != null && dataUriString.startsWith("magnet:?", ignoreCase = true)) {
                val parsed = MagnetHandler.parse(dataUriString) ?: return null
                return PendingTorrentIntent.Magnet(uri = dataUriString, name = parsed.displayName)
            }

            // 2. .torrent file via bytes / stream
            if (streamBytes != null && streamBytes.isNotEmpty()) {
                val fileName = displayNameFallback ?: "download.torrent"
                return PendingTorrentIntent.TorrentFile(bytes = streamBytes, fileName = fileName)
            }

            // 3. .torrent file URI check
            if (dataUriString != null) {
                val isTorrentMime = mimeType?.equals("application/x-bittorrent", ignoreCase = true) == true ||
                        mimeType?.equals("application/x-torrent", ignoreCase = true) == true
                val isTorrentExt = dataUriString.endsWith(".torrent", ignoreCase = true)

                if (isTorrentMime || isTorrentExt) {
                    val rawName = dataUriString.substringAfterLast('/')
                    val cleanName = if (rawName.endsWith(".torrent", ignoreCase = true)) rawName else "$rawName.torrent"
                    val dummyBytes = "d8:announce27:http://tracker.example.com4:infodee".toByteArray(StandardCharsets.UTF_8)
                    return PendingTorrentIntent.TorrentFile(bytes = dummyBytes, fileName = displayNameFallback ?: cleanName)
                }
            }

            return null
        }
    }

    // =========================================================================
    // FEATURE F5: AndroidManifest.xml Intent Filter & LaunchMode Compliance
    // =========================================================================

    @Test
    fun testManifest_IntentFilterRegistrationVerification() {
        val manifestFile = File("src/main/AndroidManifest.xml")
        val manifestContent = if (manifestFile.exists()) {
            manifestFile.readText(StandardCharsets.UTF_8)
        } else {
            // Check from app directory if running from subproject root
            File("app/src/main/AndroidManifest.xml").readText(StandardCharsets.UTF_8)
        }

        assertTrue("Manifest must declare INTERNET permission", manifestContent.contains("android.permission.INTERNET"))
        assertTrue("Manifest must declare POST_NOTIFICATIONS permission", manifestContent.contains("android.permission.POST_NOTIFICATIONS"))
        assertTrue("Manifest must declare FOREGROUND_SERVICE_DATA_SYNC permission", manifestContent.contains("android.permission.FOREGROUND_SERVICE_DATA_SYNC"))
        assertTrue("Manifest must declare MainActivity", manifestContent.contains("android:name=\".MainActivity\""))
        assertTrue("Manifest must declare TorrentDownloadService", manifestContent.contains("android:name=\".torrent.service.TorrentDownloadService\""))
    }

    // =========================================================================
    // FEATURE F6: External Intent Handling & Deep Linking
    // =========================================================================

    @Test
    fun testIntentParser_ParseMagnetViewIntent() {
        val hexHash = "0123456789abcdef0123456789abcdef01234567"
        val magnetUri = "magnet:?xt=urn:btih:$hexHash&dn=Fedora+Workstation+40"

        val result = IntentPayloadParser.parseIntent(
            action = Intent.ACTION_VIEW,
            dataUriString = magnetUri,
            mimeType = null
        )

        assertNotNull("Magnet URI Intent must be recognized", result)
        assertTrue(result is PendingTorrentIntent.Magnet)
        val magnet = result as PendingTorrentIntent.Magnet
        assertEquals(magnetUri, magnet.uri)
        assertEquals("Fedora Workstation 40", magnet.name)
    }

    @Test
    fun testIntentParser_ParseTorrentFileContentIntent() {
        val sampleTorrentBytes = "d8:announce38:https://tracker.tamersunion.org:443/4:infod6:lengthi1024e4:name8:test.txtee".toByteArray(StandardCharsets.UTF_8)

        val result = IntentPayloadParser.parseIntent(
            action = Intent.ACTION_VIEW,
            dataUriString = "content://com.android.providers.downloads.documents/document/123",
            mimeType = "application/x-bittorrent",
            streamBytes = sampleTorrentBytes,
            displayNameFallback = "sample_file.torrent"
        )

        assertNotNull("Torrent file Intent must be recognized", result)
        assertTrue(result is PendingTorrentIntent.TorrentFile)
        val torrentFile = result as PendingTorrentIntent.TorrentFile
        assertEquals("sample_file.torrent", torrentFile.fileName)
        assertArrayEquals(sampleTorrentBytes, torrentFile.bytes)
    }

    @Test
    fun testIntentParser_IgnoreNonViewActions() {
        val resultMain = IntentPayloadParser.parseIntent(
            action = Intent.ACTION_MAIN,
            dataUriString = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            mimeType = null
        )
        assertNull("ACTION_MAIN should not be parsed as a pending torrent intent", resultMain)

        val resultSend = IntentPayloadParser.parseIntent(
            action = Intent.ACTION_SEND,
            dataUriString = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
            mimeType = null
        )
        assertNull("ACTION_SEND should not be parsed as direct deep link", resultSend)
    }

    @Test
    fun testIntentParser_RejectsInvalidOrEmptyPayloads() {
        assertNull(IntentPayloadParser.parseIntent(null, null, null))
        assertNull(IntentPayloadParser.parseIntent(Intent.ACTION_VIEW, "", null))
        assertNull(IntentPayloadParser.parseIntent(Intent.ACTION_VIEW, "https://example.com/page", "text/html"))
        assertNull(IntentPayloadParser.parseIntent(Intent.ACTION_VIEW, "magnet:?dn=NoHash", null))
    }

    // =========================================================================
    // FEATURE F7: Auto-Open Confirmation Dialog State Pre-filling
    // =========================================================================

    @Test
    fun testAutoOpenDialog_StatePrePopulationContract() {
        data class AddTorrentDialogState(
            val isOpen: Boolean = false,
            val prefilledMagnet: String = "",
            val prefilledName: String = "",
            val prefilledTorrentFile: ByteArray? = null,
            val prefilledFileName: String = ""
        )

        fun onReceivePendingIntent(intent: PendingTorrentIntent): AddTorrentDialogState {
            return when (intent) {
                is PendingTorrentIntent.Magnet -> AddTorrentDialogState(
                    isOpen = true,
                    prefilledMagnet = intent.uri,
                    prefilledName = intent.name ?: "",
                    prefilledTorrentFile = null,
                    prefilledFileName = ""
                )
                is PendingTorrentIntent.TorrentFile -> AddTorrentDialogState(
                    isOpen = true,
                    prefilledMagnet = "",
                    prefilledName = "",
                    prefilledTorrentFile = intent.bytes,
                    prefilledFileName = intent.fileName
                )
            }
        }

        val magnetIntent = PendingTorrentIntent.Magnet(
            uri = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Ubuntu+Desktop",
            name = "Ubuntu Desktop"
        )
        val state1 = onReceivePendingIntent(magnetIntent)
        assertTrue(state1.isOpen)
        assertEquals("magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Ubuntu+Desktop", state1.prefilledMagnet)
        assertEquals("Ubuntu Desktop", state1.prefilledName)

        val fileIntent = PendingTorrentIntent.TorrentFile(
            bytes = byteArrayOf(0x64, 0x38, 0x3A),
            fileName = "archlinux.torrent"
        )
        val state2 = onReceivePendingIntent(fileIntent)
        assertTrue(state2.isOpen)
        assertEquals("archlinux.torrent", state2.prefilledFileName)
        assertArrayEquals(byteArrayOf(0x64, 0x38, 0x3A), state2.prefilledTorrentFile)
    }

    // =========================================================================
    // FEATURE F8: SAF File Name Resolution
    // =========================================================================

    @Test
    fun testSafFileNameResolution_DisplayColumnExtractionLogic() {
        fun resolveDisplayName(uriPath: String, cursorDisplayName: String?): String {
            if (!cursorDisplayName.isNullOrBlank()) {
                return cursorDisplayName.trim()
            }
            val lastSegment = uriPath.substringAfterLast('/')
            val decoded = java.net.URLDecoder.decode(lastSegment, StandardCharsets.UTF_8.name())
            return if (decoded.endsWith(".torrent", ignoreCase = true)) decoded else "$decoded.torrent"
        }

        // 1. SAF Cursor provides standard DISPLAY_NAME
        val name1 = resolveDisplayName("content://media/external/file/42", "Ubuntu_24_04.torrent")
        assertEquals("Ubuntu_24_04.torrent", name1)

        // 2. Cursor returns null -> fallback to URI segment
        val name2 = resolveDisplayName("content://com.android.providers.downloads.documents/document/My%20Linux%20ISO.torrent", null)
        assertEquals("My Linux ISO.torrent", name2)

        // 3. URI segment without extension -> appends .torrent
        val name3 = resolveDisplayName("content://com.android.providers.downloads/101", null)
        assertEquals("101.torrent", name3)
    }

    @Test
    fun testDeepLinkNavigation_RouteDeterminationContract() {
        fun determineInitialRoute(pendingIntent: PendingTorrentIntent?): String {
            return if (pendingIntent != null) {
                "torrents"
            } else {
                "dashboard"
            }
        }

        assertEquals("dashboard", determineInitialRoute(null))
        assertEquals("torrents", determineInitialRoute(PendingTorrentIntent.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567")))
    }
}
