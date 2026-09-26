package com.loosewire.lightious.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InvidiousApiTest {
    @Test
    fun `paired channel feed preserves release timestamps and partial failures without pagination`() = runTest {
        val transport = FakeTransport(getResponse = { url, parameters ->
            assertEquals("https://invidious.example/api/lightious/v1/channel-feed", url)
            assertTrue(parameters.isEmpty())
            InvidiousHttpResponse(
                200,
                """
                {
                  "videos":[
                    {"videoId":"dQw4w9WgXcQ","authorId":"$CHANNEL_ID","published":"1720000000"},
                    {"videoId":"aqz-KE-bpKQ","authorId":"$CHANNEL_ID","published":1720000001,"isUpcoming":true},
                    {"videoId":"m8KnrXli-bA","authorId":"$CHANNEL_ID","premiereTimestamp":1720000002},
                    {"videoId":"9bZkp7q19f0","authorId":"$CHANNEL_ID","isShort":true}
                  ],
                  "failedChannelIds":["$CHANNEL_ID"]
                }
                """.trimIndent(),
            )
        })
        InvidiousApi("https://invidious.example", deviceBearer = DEVICE_BEARER, transport = transport).use { api ->
            val page = api.channelFeed().getOrThrow()

            assertEquals(listOf("dQw4w9WgXcQ", "aqz-KE-bpKQ", "m8KnrXli-bA"), page.videos.map(VideoSummary::videoId))
            assertEquals(1720000000L, page.videos.first().published)
            assertFalse(page.videos.first().isUpcoming)
            assertTrue(page.videos[1].isUpcoming)
            assertTrue(page.videos[2].isUpcoming)
            assertEquals(listOf(CHANNEL_ID), page.failedChannelIds)
            assertEquals("Bearer $DEVICE_BEARER", transport.requests.single().headers["Authorization"])
        }
    }

    @Test
    fun `channel feed requires pairing and reports malformed or failed responses`() = runTest {
        val unused = FakeTransport(getResponse = { _, _ -> error("Unpaired feed must not send a request.") })
        InvidiousApi("https://invidious.example", transport = unused).use { api ->
            assertTrue(api.channelFeed().isFailure)
            assertTrue(unused.requests.isEmpty())
        }
        listOf(
            InvidiousHttpResponse(200, "{}"),
            InvidiousHttpResponse(200, """{"videos":[],"failedChannelIds":["invalid"]}"""),
            InvidiousHttpResponse(503, "Unavailable"),
        ).forEach { response ->
            InvidiousApi(
                "https://invidious.example",
                deviceBearer = DEVICE_BEARER,
                transport = FakeTransport(getResponse = { _, _ -> response }),
            ).use { api -> assertTrue(api.channelFeed().isFailure) }
        }
        InvidiousApi(
            "https://invidious.example",
            deviceBearer = DEVICE_BEARER,
            transport = FakeTransport(getResponse = { _, _ -> throw CancellationException("leave feed") }),
        ).use { api -> assertFailsWith<CancellationException> { api.channelFeed() } }
    }

    @Test
    fun `search ignores non-video results and accepts flexible numbers`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ ->
                check(url.endsWith("/api/v1/search"))
                InvidiousHttpResponse(
                    200,
                    """
                    [
                      {"type":"channel","author":"Channel"},
                      {"type":"shortVideo","videoId":"aqz-KE-bpKQ","title":"A Short"},
                      {"type":"video","videoId":"m8KnrXli-bA","title":"Flagged Short","isShort":true},
                      {
                        "type":"video",
                        "videoId":"dQw4w9WgXcQ",
                        "title":"A video",
                        "author":"An author",
                        "authorId":"$CHANNEL_ID",
                        "lengthSeconds":"213",
                        "viewCount":1234,
                        "publishedText":"2 days ago",
                        "liveNow":"false"
                      }
                    ]
                    """.trimIndent(),
                )
            },
        )
        val api = InvidiousApi("invidious.example", proxyMedia = true, transport = transport)

        val videos = api.search("test").getOrThrow()

        assertEquals(1, videos.size)
        assertEquals(213, videos.single().lengthSeconds)
        assertEquals(1234, videos.single().viewCount)
        assertFalse(videos.single().liveNow)
        assertEquals(CHANNEL_ID, videos.single().authorId)
        assertEquals("video", transport.requests.single().parameters["type"])
        assertTrue(transport.requests.single().headers.isEmpty())
        api.close()
    }

    @Test
    fun `channel videos map author ids and continuation pages`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, parameters ->
                check(url.endsWith("/api/v1/channels/$CHANNEL_ID/videos"))
                assertEquals("newest", parameters["sort_by"])
                assertEquals("next-page", parameters["continuation"])
                InvidiousHttpResponse(
                    200,
                    """
                    {
                      "videos":[{
                        "type":"video",
                        "videoId":"dQw4w9WgXcQ",
                        "title":"A channel video",
                        "author":"A channel",
                        "authorId":"$CHANNEL_ID"
                      },{
                        "type":"shortVideo",
                        "videoId":"aqz-KE-bpKQ",
                        "title":"A Short",
                        "authorId":"$CHANNEL_ID"
                      },{
                        "type":"video",
                        "videoId":"m8KnrXli-bA",
                        "title":"A flagged Short",
                        "authorId":"$CHANNEL_ID",
                        "isShort":true
                      }],
                      "continuation":"page-three"
                    }
                    """.trimIndent(),
                )
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val page = api.channelVideos(CHANNEL_ID, "next-page").getOrThrow()

        assertEquals(listOf("dQw4w9WgXcQ"), page.videos.map(VideoSummary::videoId))
        assertEquals(CHANNEL_ID, page.videos.single().authorId)
        assertEquals("page-three", page.continuation)
        api.close()
    }

    @Test
    fun `channel videos reject non-canonical id without a request`() = runTest {
        val transport = FakeTransport(getResponse = { url, _ -> error("Unexpected GET: $url") })
        val api = InvidiousApi("https://invidious.example", transport = transport)

        assertTrue(api.channelVideos("not-a-channel").isFailure)
        assertTrue(transport.requests.isEmpty())
        api.close()
    }

    @Test
    fun `paired channel search is scoped and maps a next page`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, parameters ->
                check(url.endsWith("/api/lightious/v1/channels/$CHANNEL_ID/search"))
                assertEquals("sleep stories", parameters["q"])
                assertEquals("2", parameters["page"])
                InvidiousHttpResponse(
                    200,
                    """
                    {
                      "videos":[{
                        "type":"video",
                        "videoId":"dQw4w9WgXcQ",
                        "title":"A channel video",
                        "author":"A channel",
                        "authorId":"$CHANNEL_ID"
                      },{
                        "type":"video",
                        "videoId":"aqz-KE-bpKQ",
                        "title":"A different channel",
                        "authorId":"UCBJycsmduvYEL83R_U4JriQ"
                      },{
                        "type":"shortVideo",
                        "videoId":"m8KnrXli-bA",
                        "title":"A Short",
                        "authorId":"$CHANNEL_ID"
                      }],
                      "nextPage":3
                    }
                    """.trimIndent(),
                )
            },
        )
        val api = InvidiousApi(
            baseUrl = "https://invidious.example",
            deviceBearer = DEVICE_BEARER,
            transport = transport,
        )

        val result = api.channelSearch(CHANNEL_ID, "  sleep stories  ", page = 2).getOrThrow()

        assertEquals(listOf("dQw4w9WgXcQ"), result.videos.map(VideoSummary::videoId))
        assertEquals(3, result.nextPage)
        assertEquals("Bearer $DEVICE_BEARER", transport.requests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `channel search rejects unpaired malformed and oversized requests before transport`() = runTest {
        val transport = FakeTransport(getResponse = { url, _ -> error("Unexpected GET: $url") })
        val unpaired = InvidiousApi("https://invidious.example", transport = transport)
        val paired = InvidiousApi(
            baseUrl = "https://invidious.example",
            deviceBearer = DEVICE_BEARER,
            transport = transport,
        )

        assertTrue(unpaired.channelSearch(CHANNEL_ID, "test").isFailure)
        assertTrue(paired.channelSearch("not-a-channel", "test").isFailure)
        assertTrue(paired.channelSearch(CHANNEL_ID, "   ").isFailure)
        assertTrue(paired.channelSearch(CHANNEL_ID, "x".repeat(257)).isFailure)
        assertTrue(paired.channelSearch(CHANNEL_ID, "test", page = 0).isFailure)
        assertTrue(transport.requests.isEmpty())
        unpaired.close()
        paired.close()
    }

    @Test
    fun `Shorts URLs are ignored without requesting search or video details`() = runTest {
        val transport = FakeTransport(getResponse = { url, _ -> error("Unexpected GET: $url") })
        val api = InvidiousApi("https://invidious.example", transport = transport)
        val shortsUrl = "https://www.youtube.com/shorts/dQw4w9WgXcQ"

        assertTrue(api.search(shortsUrl).getOrThrow().isEmpty())
        assertTrue(api.video(shortsUrl).isFailure)
        assertTrue(transport.requests.isEmpty())
        api.close()
    }

    @Test
    fun `video details reject an explicitly flagged Short`() = runTest {
        val transport = FakeTransport(getResponse = { _, _ ->
            InvidiousHttpResponse(
                200,
                """{"videoId":"dQw4w9WgXcQ","title":"A Short","isShort":true}""",
            )
        })
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val result = api.video("dQw4w9WgXcQ")

        assertTrue(result.isFailure)
        assertEquals(SHORTS_BLOCKED_MESSAGE, result.exceptionOrNull()?.message)
        api.close()
    }

    @Test
    fun `account feed sends bearer token and combines unique feed videos`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, parameters ->
                check(url.endsWith("/api/v1/auth/feed"))
                assertEquals("1", parameters["page"])
                assertEquals("40", parameters["max_results"])
                InvidiousHttpResponse(
                    200,
                    """
                    {
                      "notifications":[
                        {"type":"video","videoId":"dQw4w9WgXcQ","title":"New upload"}
                      ],
                      "videos":[
                        {"type":"video","videoId":"dQw4w9WgXcQ","title":"Duplicate"},
                        {"type":"video","videoId":"aqz-KE-bpKQ","title":"Older upload"}
                      ]
                    }
                    """.trimIndent(),
                )
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val videos = api.accountFeed(AUTH_TOKEN).getOrThrow()

        assertEquals(listOf("dQw4w9WgXcQ", "aqz-KE-bpKQ"), videos.map(VideoSummary::videoId))
        assertEquals("Bearer $AUTH_TOKEN", transport.requests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `paired content routes use device bearer and lightious api paths`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, parameters ->
                when {
                    url.endsWith("/api/lightious/v1/popular") -> InvidiousHttpResponse(200, "[]")
                    url.endsWith("/api/lightious/v1/search") -> {
                        assertEquals("kittens", parameters["q"])
                        InvidiousHttpResponse(200, "[]")
                    }
                    url.endsWith("/api/lightious/v1/feed") -> InvidiousHttpResponse(
                        200,
                        """{"videos":[{"type":"video","videoId":"dQw4w9WgXcQ","title":"A video"}]}""",
                    )
                    url.endsWith("/api/lightious/v1/history") -> InvidiousHttpResponse(
                        200,
                        """["dQw4w9WgXcQ"]""",
                    )
                    url.endsWith("/api/lightious/v1/channels/$CHANNEL_ID/videos") ->
                        InvidiousHttpResponse(
                            200,
                            """{"videos":[{"type":"video","videoId":"dQw4w9WgXcQ","title":"A video","authorId":"$CHANNEL_ID"}]}""",
                        )
                    url.endsWith("/api/lightious/v1/channels/$CHANNEL_ID/search") ->
                        InvidiousHttpResponse(200, """{"videos":[]}""")
                    url.endsWith("/api/lightious/v1/videos/dQw4w9WgXcQ") ->
                        InvidiousHttpResponse(200, VIDEO_DETAILS_JSON)
                    else -> error("Unexpected URL: $url")
                }
            },
            postResponse = { url, _ ->
                check(url.endsWith("/api/lightious/v1/history/dQw4w9WgXcQ"))
                InvidiousHttpResponse(204, "")
            },
        )
        val api = InvidiousApi(
            baseUrl = "https://invidious.example",
            proxyMedia = true,
            deviceBearer = DEVICE_BEARER,
            transport = transport,
        )

        api.popular().getOrThrow()
        api.search("kittens").getOrThrow()
        api.accountFeed("").getOrThrow()
        api.accountHistoryIds("", maxResults = 5).getOrThrow()
        api.channelVideos(CHANNEL_ID).getOrThrow()
        api.channelSearch(CHANNEL_ID, "kittens").getOrThrow()
        api.video("dQw4w9WgXcQ").getOrThrow()
        api.markWatched("", "dQw4w9WgXcQ").getOrThrow()

        assertTrue(transport.requests.all { request -> request.url.contains("/api/lightious/v1/") })
        assertTrue(transport.requests.all { request -> request.headers["Authorization"] == "Bearer $DEVICE_BEARER" })
        assertEquals(
            emptyMap(),
            transport.requests.first { request -> request.url.endsWith("/api/lightious/v1/videos/dQw4w9WgXcQ") }.parameters,
        )
        assertEquals("Bearer $DEVICE_BEARER", transport.postRequests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `account history sends bearer token clamps limit and filters invalid ids`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, parameters ->
                check(url.endsWith("/api/v1/auth/history"))
                assertEquals("1", parameters["page"])
                assertEquals("100", parameters["max_results"])
                InvidiousHttpResponse(
                    200,
                    """["dQw4w9WgXcQ","invalid","dQw4w9WgXcQ","aqz-KE-bpKQ"]""",
                )
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val ids = api.accountHistoryIds(AUTH_TOKEN, maxResults = 1_000).getOrThrow()

        assertEquals(listOf("dQw4w9WgXcQ", "aqz-KE-bpKQ"), ids)
        assertEquals("Bearer $AUTH_TOKEN", transport.requests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `mark watched extracts video id and posts with bearer token`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ -> error("Unexpected GET: $url") },
            postResponse = { url, _ ->
                check(url.endsWith("/api/v1/auth/history/dQw4w9WgXcQ"))
                InvidiousHttpResponse(204, "")
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        api.markWatched(AUTH_TOKEN, "https://www.youtube.com/watch?v=dQw4w9WgXcQ").getOrThrow()

        assertEquals(1, transport.postRequests.size)
        assertEquals("Bearer $AUTH_TOKEN", transport.postRequests.single().headers["Authorization"])
        api.close()
    }

    @Test
    fun `public calls never add an authorization header`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ ->
                when {
                    url.endsWith("/api/v1/popular") -> InvidiousHttpResponse(200, "[]")
                    url.endsWith("/api/v1/search") -> InvidiousHttpResponse(200, "[]")
                    url.endsWith("/api/v1/videos/dQw4w9WgXcQ") ->
                        InvidiousHttpResponse(200, VIDEO_DETAILS_JSON)
                    else -> error("Unexpected URL: $url")
                }
            },
        )
        val api = InvidiousApi("https://invidious.example", transport = transport)

        api.popular().getOrThrow()
        api.search("kittens").getOrThrow()
        api.video("dQw4w9WgXcQ").getOrThrow()

        assertEquals(3, transport.requests.size)
        assertTrue(transport.requests.all { request -> "Authorization" !in request.headers })
        api.close()
    }

    @Test
    fun `video maps relative media and selects 720p plus adaptive audio`() = runTest {
        val transport = FakeTransport(getResponse = { url, parameters ->
            check(url.endsWith("/api/v1/videos/dQw4w9WgXcQ"))
            assertEquals("true", parameters["local"])
            InvidiousHttpResponse(200, VIDEO_DETAILS_JSON)
        })
        val api = InvidiousApi("https://invidious.example/", proxyMedia = true, transport = transport)

        val details = api.video("https://youtu.be/dQw4w9WgXcQ").getOrThrow()

        assertEquals(
            "https://invidious.example/videoplayback?itag=22",
            assertIs<VideoPlaybackSource.Single>(details.watchSource).stream.url,
        )
        assertEquals("https://invidious.example/videoplayback?itag=140", details.audioUrl)
        assertEquals(720, details.selection.progressive?.height)
        assertEquals(128_000, details.selection.adaptiveAudio?.bitrate)
        assertEquals(CHANNEL_ID, details.summary.authorId)
        api.close()
    }

    @Test
    fun `video maps adaptive pair and selects nested original audio metadata`() = runTest {
        val transport = FakeTransport(getResponse = { _, _ ->
            InvidiousHttpResponse(200, MULTILINGUAL_ADAPTIVE_VIDEO_JSON)
        })
        val api = InvidiousApi("https://invidious.example", transport = transport)

        val details = api.video("m8KnrXli-bA").getOrThrow()

        val source = assertIs<VideoPlaybackSource.Separate>(details.watchSource)
        assertEquals("https://invidious.example/videoplayback?itag=136", source.video.url)
        assertEquals("en-US.4", source.audio.audioTrackId)
        assertEquals("en-US", source.audio.audioLanguage)
        assertEquals(AudioContentType.ORIGINAL, source.audio.audioContent)
        assertEquals(128_000, source.audio.bitrate)
        api.close()
    }

    @Test
    fun `probe validates public stats without touching protected content`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ ->
                check(url.endsWith("/api/v1/stats"))
                InvidiousHttpResponse(
                    200,
                    """{"software":{"name":"invidious","version":"test","branch":"lightious"}}""",
                )
            },
        )
        val api = InvidiousApi("https://invidious.example", proxyMedia = true, transport = transport)

        val probe = api.probe()

        assertTrue(probe.reachable)
        assertTrue(probe.apiAvailable)
        assertFalse(probe.playbackAvailable)
        assertEquals(200, probe.apiStatusCode)
        assertTrue(transport.requests.single().parameters.isEmpty())
        assertTrue(transport.requests.single().headers.isEmpty())
        assertTrue(transport.byteRequests.isEmpty())
        api.close()
    }

    @Test
    fun `probe rejects a successful response that is not Invidious stats`() = runTest {
        val api = InvidiousApi(
            baseUrl = "https://example.com",
            transport = FakeTransport(
                getResponse = { _, _ -> InvidiousHttpResponse(200, """{"status":"ok"}""") },
            ),
        )

        val probe = api.probe()

        assertTrue(probe.reachable)
        assertFalse(probe.apiAvailable)
        assertEquals("The server did not identify itself as Invidious.", probe.message)
        api.close()
    }

    @Test
    fun `paired probe uses authenticated video and media routes without public fallback`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ ->
                when {
                    url.endsWith("/api/lightious/v1/videos/dQw4w9WgXcQ") ->
                        InvidiousHttpResponse(200, VIDEO_DETAILS_JSON)
                    url.contains("/api/v1/") -> error("Unexpected public fallback: $url")
                    else -> error("Unexpected URL: $url")
                }
            },
            byteResponse = OneByteResponse(
                statusCode = 206,
                receivedByte = true,
                rangeSupported = true,
            ),
        )
        val api = InvidiousApi(
            baseUrl = "https://invidious.example",
            proxyMedia = true,
            deviceBearer = DEVICE_BEARER,
            transport = transport,
        )

        val probe = api.probeAuthorized("dQw4w9WgXcQ")

        assertTrue(probe.successful)
        assertTrue(transport.requests.all { request -> request.url.contains("/api/lightious/v1/") })
        assertTrue(transport.requests.all { request -> request.headers["Authorization"] == "Bearer $DEVICE_BEARER" })
        api.close()
    }

    @Test
    fun `paired probe can confirm authenticated connectivity without probing search`() = runTest {
        val transport = FakeTransport(
            getResponse = { url, _ -> error("Unexpected authenticated GET: $url") },
        )
        val api = InvidiousApi(
            baseUrl = "https://invidious.example",
            proxyMedia = true,
            deviceBearer = DEVICE_BEARER,
            transport = transport,
        )

        val probe = api.probeAuthorized()

        assertTrue(probe.apiAvailable)
        assertTrue(!probe.playbackAvailable)
        assertTrue(transport.requests.isEmpty())
        api.close()
    }

    @Test
    fun `probe distinguishes an HTTP API failure from network failure`() = runTest {
        val httpApi = InvidiousApi(
            baseUrl = "https://invidious.example",
            proxyMedia = true,
            transport = FakeTransport(getResponse = { _, _ -> InvidiousHttpResponse(503, "unavailable") }),
        )
        val networkApi = InvidiousApi(
            baseUrl = "https://offline.example",
            proxyMedia = true,
            transport = FakeTransport(getResponse = { _, _ -> throw IllegalStateException("offline") }),
        )

        val httpProbe = httpApi.probe()
        val networkProbe = networkApi.probe()

        assertTrue(httpProbe.reachable)
        assertFalse(httpProbe.apiAvailable)
        assertEquals(503, httpProbe.apiStatusCode)
        assertFalse(networkProbe.reachable)
        assertFalse(networkProbe.apiAvailable)
        httpApi.close()
        networkApi.close()
    }

    @Test
    fun `search and probe propagate coroutine cancellation`() = runTest {
        val searchApi = InvidiousApi(
            baseUrl = "https://invidious.example",
            proxyMedia = true,
            transport = FakeTransport(getResponse = { _, _ -> throw CancellationException("leave search") }),
        )
        val probeApi = InvidiousApi(
            baseUrl = "https://invidious.example",
            proxyMedia = true,
            transport = FakeTransport(getResponse = { _, _ -> throw CancellationException("leave setup") }),
        )

        assertFailsWith<CancellationException> { searchApi.search("test") }
        assertFailsWith<CancellationException> { probeApi.probe() }
        searchApi.close()
        probeApi.close()
    }

    private data class Request(
        val url: String,
        val parameters: Map<String, String>,
        val headers: Map<String, String>,
    )

    private data class PostRequest(
        val url: String,
        val headers: Map<String, String>,
    )

    private class FakeTransport(
        private val getResponse: suspend (String, Map<String, String>) -> InvidiousHttpResponse,
        private val postResponse: suspend (String, Map<String, String>) -> InvidiousHttpResponse =
            { url, _ -> error("Unexpected POST: $url") },
        private val byteResponse: OneByteResponse = OneByteResponse(206, true, true),
    ) : InvidiousHttpTransport {
        val requests = mutableListOf<Request>()
        val postRequests = mutableListOf<PostRequest>()
        val byteRequests = mutableListOf<String>()

        override suspend fun get(
            url: String,
            parameters: Map<String, String>,
            headers: Map<String, String>,
        ): InvidiousHttpResponse {
            requests += Request(url, parameters, headers)
            return getResponse(url, parameters)
        }

        override suspend fun post(
            url: String,
            headers: Map<String, String>,
        ): InvidiousHttpResponse {
            postRequests += PostRequest(url, headers)
            return postResponse(url, headers)
        }

        override suspend fun readOneByte(url: String): OneByteResponse {
            byteRequests += url
            return byteResponse
        }

        override fun close() = Unit
    }

    private companion object {
        const val AUTH_TOKEN =
            "{\"session\":\"v1:test-session\",\"scopes\":[\"GET:feed\",\"GET:history\",\"POST:history/*\"],\"signature\":\"test-signature\"}"
        const val CHANNEL_ID = "UCXuqSBlHAE6Xw-yeJA0Tunw"
        const val DEVICE_BEARER = "lpt_device_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

        val VIDEO_DETAILS_JSON = """
            {
              "videoId":"dQw4w9WgXcQ",
              "title":"A video",
              "author":"An author",
              "authorId":"$CHANNEL_ID",
              "lengthSeconds":213,
              "viewCount":"1234",
              "publishedText":"2 days ago",
              "liveNow":false,
              "description":"Description",
              "formatStreams":[
                {
                  "url":"/videoplayback?itag=37",
                  "itag":"37",
                  "type":"video/mp4; codecs=\"avc1.640028, mp4a.40.2\"",
                  "qualityLabel":"1080p",
                  "height":1080,
                  "bitrate":5000000
                },
                {
                  "url":"/videoplayback?itag=22",
                  "itag":22,
                  "type":"video/mp4; codecs=\"avc1.64001F, mp4a.40.2\"",
                  "qualityLabel":"720p",
                  "height":"720",
                  "bitrate":2000000
                }
              ],
              "adaptiveFormats":[
                {
                  "url":"/videoplayback?itag=140",
                  "itag":"140",
                  "type":"audio/mp4; codecs=\"mp4a.40.2\"",
                  "audioQuality":"AUDIO_QUALITY_MEDIUM",
                  "bitrate":128000
                },
                {
                  "url":"/videoplayback?itag=251",
                  "itag":"251",
                  "type":"audio/webm; codecs=\"opus\"",
                  "audioQuality":"AUDIO_QUALITY_MEDIUM",
                  "bitrate":"160000"
                }
              ]
            }
        """.trimIndent()

        val MULTILINGUAL_ADAPTIVE_VIDEO_JSON = """
            {
              "videoId":"m8KnrXli-bA",
              "title":"The 9 Bar Fallacy",
              "author":"Lance Hedrick",
              "adaptiveFormats":[
                {
                  "url":"/videoplayback?itag=136",
                  "itag":136,
                  "type":"video/mp4; codecs=\"avc1.4d401f\"",
                  "qualityLabel":"720p",
                  "height":720,
                  "bitrate":2000000
                },
                {
                  "url":"/videoplayback?itag=140&xtags=acont%3Ddubbed%3Alang%3Dfr",
                  "itag":140,
                  "type":"audio/mp4; codecs=\"mp4a.40.2\"",
                  "bitrate":192000,
                  "audioTrack":{
                    "id":"fr.3",
                    "displayName":"French",
                    "audioIsDefault":false
                  }
                },
                {
                  "url":"/videoplayback?itag=140&xtags=acont%3Ddubbed%3Alang%3Dfr",
                  "itag":140,
                  "type":"audio/mp4; codecs=\"mp4a.40.2\"",
                  "bitrate":128000,
                  "audioTrack":{
                    "id":"en-US.4",
                    "displayName":"English (US) original",
                    "audioIsDefault":true
                  }
                }
              ]
            }
        """.trimIndent()
    }
}
