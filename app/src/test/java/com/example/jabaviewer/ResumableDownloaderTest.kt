package com.example.jabaviewer

import com.example.jabaviewer.data.remote.ResumableDownloader
import com.example.jabaviewer.data.remote.TimeProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertArrayEquals
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class ResumableDownloaderTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var server: MockWebServer
    private lateinit var downloader: ResumableDownloader

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        downloader = ResumableDownloader(OkHttpClient(), TimeProvider { 0L })
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun download_writesFullBody() = runTest {
        val payload = "hello world".encodeToByteArray()
        server.enqueue(MockResponse().setResponseCode(200).setBody(Buffer().write(payload)))

        val tempFile = tempFolder.newFile("full.part")
        downloader.download(
            url = server.url("/full").toString(),
            tempFile = tempFile,
            onProgress = { _ -> },
            isStopped = { false },
        )

        assertArrayEquals(payload, tempFile.readBytes())
    }

    @Test
    fun download_resumesWhenServerSupportsRange() = runTest {
        val payload = "abcdef".encodeToByteArray()
        val tempFile = tempFolder.newFile("resume.part")
        tempFile.writeBytes(payload.copyOfRange(0, 3))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range == "bytes=3-") {
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 3-5/6")
                        .setBody(Buffer().write(payload.copyOfRange(3, payload.size)))
                } else {
                    MockResponse().setResponseCode(200).setBody(Buffer().write(payload))
                }
            }
        }

        downloader.download(
            url = server.url("/resume").toString(),
            tempFile = tempFile,
            onProgress = { _ -> },
            isStopped = { false },
        )

        assertArrayEquals(payload, tempFile.readBytes())
    }

    @Test
    fun download_restartsWhenRangeDoesNotMatch() = runTest {
        val payload = "abcdef".encodeToByteArray()
        val tempFile = tempFolder.newFile("mismatch.part")
        tempFile.writeBytes(payload.copyOfRange(0, 3))

        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val range = request.getHeader("Range")
                return if (range != null) {
                    MockResponse()
                        .setResponseCode(206)
                        .setHeader("Content-Range", "bytes 0-5/6")
                        .setBody(Buffer().write(payload))
                } else {
                    MockResponse()
                        .setResponseCode(200)
                        .setBody(Buffer().write(payload))
                }
            }
        }

        downloader.download(
            url = server.url("/mismatch").toString(),
            tempFile = tempFile,
            onProgress = { _ -> },
            isStopped = { false },
        )

        assertArrayEquals(payload, tempFile.readBytes())
    }
}
