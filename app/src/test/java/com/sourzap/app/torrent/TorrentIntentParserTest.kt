package com.sourzap.app.torrent

import android.content.Intent
import android.net.Uri
import com.sourzap.app.torrent.core.TorrentIntentParser
import com.sourzap.app.torrent.model.PendingTorrentIntent
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * Unit test suite for [TorrentIntentParser] and [PendingTorrentIntent] deep linking logic.
 * Tests Features F5, F6, F7, and F8.
 */
class TorrentIntentParserTest {

    // =========================================================================
    // 1. Magnet URI Parsing & Display Name Extraction
    // =========================================================================

    @Test
    fun testParseMagnet_StandardHexHashAndDisplayName() {
        val magnetUri = "magnet:?xt=urn:btih:da39a3ee5e6b4b0d3255bfef95601890afd80709&dn=Ubuntu+24.04+LTS"
        val result = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = magnetUri,
            mimeType = null
        )

        assertNotNull("Magnet URI must be parsed", result)
        assertTrue(result is PendingTorrentIntent.Magnet)
        val magnet = result as PendingTorrentIntent.Magnet
        assertEquals(magnetUri, magnet.uri)
        assertEquals("Ubuntu 24.04 LTS", magnet.name)
    }

    @Test
    fun testParseMagnet_Base32HashAndTrackers() {
        val magnetUri = "magnet:?xt=urn:btih:MFRGGZDFMZTWQ2LKNNWG23TPOBYXE43U&dn=Arch+Linux&tr=udp%3A%2F%2Ftracker.opentrackr.org%3A1337"
        val result = TorrentIntentParser.parseData(
            action = "android.intent.action.VIEW",
            dataUriString = magnetUri,
            mimeType = null
        )

        assertNotNull("Base32 magnet URI must be parsed", result)
        assertTrue(result is PendingTorrentIntent.Magnet)
        val magnet = result as PendingTorrentIntent.Magnet
        assertEquals(magnetUri, magnet.uri)
        assertEquals("Arch Linux", magnet.name)
    }

    @Test
    fun testParseMagnet_WithoutDisplayName() {
        val magnetUri = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"
        val result = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = magnetUri,
            mimeType = null
        )

        assertNotNull(result)
        assertTrue(result is PendingTorrentIntent.Magnet)
        val magnet = result as PendingTorrentIntent.Magnet
        assertEquals(magnetUri, magnet.uri)
        assertNull(magnet.name)
    }

    @Test
    fun testParseMagnet_WhitespaceTrimming() {
        val paddedMagnet = "   \n\t magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Padded   \n"
        val result = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = paddedMagnet,
            mimeType = null
        )

        assertNotNull(result)
        assertTrue(result is PendingTorrentIntent.Magnet)
        val magnet = result as PendingTorrentIntent.Magnet
        assertEquals("Padded", magnet.name)
    }

    // =========================================================================
    // 2. Torrent File Bytes and Stream Parsing
    // =========================================================================

    @Test
    fun testParseTorrentFile_WithDirectBytes() {
        val testBytes = "d8:announce30:http://tracker.example.com/ann4:infod6:lengthi512ee".toByteArray(StandardCharsets.UTF_8)
        val result = TorrentIntentParser.parseData(
            action = Intent.ACTION_VIEW,
            dataUriString = null,
            mimeType = "application/x-bittorrent",
            streamBytes = testBytes,
            displayNameFallback = "test_linux.torrent"
        )

        assertNotNull(result)
        assertTrue(result is PendingTorrentIntent.TorrentFile)
        val file = result as PendingTorrentIntent.TorrentFile
        assertEquals("test_linux.torrent", file.fileName)
        assertArrayEquals(testBytes, file.bytes)
    }

    @Test
    fun testParseTorrentFile_MimeTypesRecognition() {
        val mimeTypes = listOf(
            "application/x-bittorrent",
            "application/x-torrent",
            "application/octet-stream",
            "APPLICATION/X-BITTORRENT"
        )

        for (mime in mimeTypes) {
            val result = TorrentIntentParser.parseData(
                action = Intent.ACTION_VIEW,
                dataUriString = "content://media/external/files/100",
                mimeType = mime,
                displayNameFallback = "download.torrent"
            )
            assertNotNull("MIME type $mime must be accepted", result)
            assertTrue(result is PendingTorrentIntent.TorrentFile)
        }
    }

    @Test
    fun testParseTorrentFile_ExtensionBasedRecognition() {
        val extensions = listOf(
            "file:///storage/emulated/0/Download/ubuntu-24.04.torrent",
            "content://downloads/my_file.TORRENT",
            "content://downloads/document/archive.iso.torrent"
        )

        for (uri in extensions) {
            val result = TorrentIntentParser.parseData(
                action = Intent.ACTION_VIEW,
                dataUriString = uri,
                mimeType = null
            )
            assertNotNull("URI $uri with .torrent extension must be accepted", result)
            assertTrue(result is PendingTorrentIntent.TorrentFile)
        }
    }

    // =========================================================================
    // 3. Action and Payload Validation
    // =========================================================================

    @Test
    fun testParseIntent_NonViewActionsRejected() {
        val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567"

        assertNull("ACTION_MAIN must be rejected", TorrentIntentParser.parseData(Intent.ACTION_MAIN, magnet, null))
        assertNull("ACTION_SEND must be rejected", TorrentIntentParser.parseData(Intent.ACTION_SEND, magnet, null))
        assertNull("ACTION_EDIT must be rejected", TorrentIntentParser.parseData(Intent.ACTION_EDIT, magnet, null))
        assertNull("null action must be rejected", TorrentIntentParser.parseData(null, magnet, null))
    }

    @Test
    fun testParseIntent_CorruptedOrInvalidDataRejected() {
        // Invalid magnet links
        assertNull(TorrentIntentParser.parseData(Intent.ACTION_VIEW, "magnet:?", null))
        assertNull(TorrentIntentParser.parseData(Intent.ACTION_VIEW, "magnet:?dn=NoHashHere", null))
        assertNull(TorrentIntentParser.parseData(Intent.ACTION_VIEW, "magnet:?xt=urn:sha1:invalid", null))
        assertNull(TorrentIntentParser.parseData(Intent.ACTION_VIEW, "http://example.com/page.html", "text/html"))
        assertNull(TorrentIntentParser.parseData(Intent.ACTION_VIEW, null, null))
        assertNull(TorrentIntentParser.parseData(Intent.ACTION_VIEW, "", null))
    }

    // =========================================================================
    // 4. SAF Display Name Resolution
    // =========================================================================

    @Test
    fun testResolveDisplayNameFromPath_Matrix() {
        // 1. Cursor provided name overrides path
        assertEquals(
            "custom_name.torrent",
            TorrentIntentParser.resolveDisplayNameFromPath("content://media/1", "custom_name.torrent")
        )

        // 2. URI path with url-encoded spaces
        assertEquals(
            "Debian Linux 12.torrent",
            TorrentIntentParser.resolveDisplayNameFromPath("content://downloads/Debian%20Linux%2012.torrent", null)
        )

        // 3. URI path without .torrent extension appends .torrent
        assertEquals(
            "document_42.torrent",
            TorrentIntentParser.resolveDisplayNameFromPath("content://downloads/document_42", null)
        )

        // 4. Whitespace trimming
        assertEquals(
            "cleaned.torrent",
            TorrentIntentParser.resolveDisplayNameFromPath("content://downloads/1", "  cleaned.torrent  ")
        )
    }

    // =========================================================================
    // 5. PendingTorrentIntent Model Contracts
    // =========================================================================

    @Test
    fun testPendingTorrentIntent_EqualityAndHashCode() {
        val bytes1 = byteArrayOf(0x01, 0x02, 0x03)
        val bytes2 = byteArrayOf(0x01, 0x02, 0x03)
        val bytes3 = byteArrayOf(0x01, 0x02, 0x04)

        val file1 = PendingTorrentIntent.TorrentFile(bytes1, "test.torrent")
        val file2 = PendingTorrentIntent.TorrentFile(bytes2, "test.torrent")
        val file3 = PendingTorrentIntent.TorrentFile(bytes3, "test.torrent")
        val file4 = PendingTorrentIntent.TorrentFile(bytes1, "other.torrent")

        assertEquals(file1, file2)
        assertEquals(file1.hashCode(), file2.hashCode())
        assertFalse(file1 == file3)
        assertFalse(file1 == file4)

        val magnet1 = PendingTorrentIntent.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "Name")
        val magnet2 = PendingTorrentIntent.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "Name")
        val magnet3 = PendingTorrentIntent.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "Other")

        assertEquals(magnet1, magnet2)
        assertEquals(magnet1.hashCode(), magnet2.hashCode())
        assertFalse(magnet1 == magnet3)
    }

    // =========================================================================
    // 6. Deep Link Routing State Simulation
    // =========================================================================

    @Test
    fun testPendingTorrentIntent_StateFlowEmissionAndConsumption() {
        val stateFlow = MutableStateFlow<PendingTorrentIntent?>(null)
        assertNull(stateFlow.value)

        // Incoming magnet intent arrives
        val magnetIntent = PendingTorrentIntent.Magnet("magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567", "Fedora")
        stateFlow.value = magnetIntent
        assertEquals(magnetIntent, stateFlow.value)

        // Navigation determination
        val targetRoute = if (stateFlow.value != null) "torrents" else "dashboard"
        assertEquals("torrents", targetRoute)

        // TorrentScreen consumes and clears intent
        stateFlow.value = null
        assertNull(stateFlow.value)
    }
}
