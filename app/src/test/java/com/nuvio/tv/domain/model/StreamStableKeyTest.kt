package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class StreamStableKeyTest {

    @Test
    fun `resolved debrid URL does not change row identity`() {
        val pending = stream(
            url = null,
            clientResolve = StreamClientResolve(
                type = "debrid",
                service = "realdebrid",
                infoHash = "ABC123",
                fileIdx = 4,
                filename = "episode.mkv",
                torrentName = null,
                magnetUri = null,
                sources = null,
                mediaType = null,
                mediaId = null,
                mediaOnlyId = null,
                title = null,
                season = 1,
                episode = 2,
                serviceIndex = null,
                serviceExtension = null,
                isCached = true,
                stream = null
            )
        )

        assertEquals(
            pending.stableKey(),
            pending.copy(url = "https://cdn.example/temporary-token").stableKey()
        )
    }

    @Test
    fun `plain URL streams retain distinct identities`() {
        assertNotEquals(
            stream(url = "https://example.test/one").stableKey(),
            stream(url = "https://example.test/two").stableKey()
        )
    }

    private fun stream(
        url: String?,
        clientResolve: StreamClientResolve? = null
    ) = Stream(
        name = "Stream",
        title = null,
        description = null,
        url = url,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Addon",
        addonLogo = null,
        clientResolve = clientResolve
    )
}
