/*
 * Javalin - https://javalin.io
 * Copyright 2017 David Åse
 * Licensed under Apache 2.0: https://github.com/tipsy/javalin/blob/master/LICENSE
 */

package io.javalin

import com.mashape.unirest.http.Unirest
import io.javalin.apibuilder.ApiBuilder.ws
import io.javalin.json.JavalinJson
import io.javalin.misc.SerializeableObject
import io.javalin.util.TestUtil
import io.javalin.websocket.WsContext
import org.assertj.core.api.Assertions.assertThat
import org.eclipse.jetty.websocket.api.MessageTooLargeException
import org.java_websocket.client.WebSocketClient
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ServerHandshake
import org.junit.Test
import java.net.URI
import java.util.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * This test could be better
 */
class TestWebSocket {

    data class TestLogger(val log: ArrayList<String>)

    private fun Javalin.logger(): TestLogger {
        if (this.attribute(TestLogger::class.java) == null) {
            this.attribute(TestLogger::class.java, TestLogger(ArrayList()))
        }
        return this.attribute(TestLogger::class.java)
    }

    fun contextPathJavalin() = Javalin.create().configure { it.wsContextPath = "/websocket" }

    fun javalinWithWsLogger() = Javalin.create().apply {
        this.configure { config ->
            config.wsLogger { ws ->
                ws.onConnect { ctx -> this.logger().log.add(ctx.pathParam("param") + " connected") }
                ws.onClose { ctx -> this.logger().log.add(ctx.pathParam("param") + " disconnected") }
            }
        }
    }

    fun accessManagedJavalin() = Javalin.create().apply {
        this.configure {
            it.accessManager { handler, ctx, permittedRoles ->
                this.logger().log.add("handling upgrade request ...")
                when {
                    ctx.queryParam("allowed") == "true" -> {
                        this.logger().log.add("upgrade request valid!")
                        handler.handle(ctx)
                    }
                    ctx.queryParam("exception") == "true" -> throw UnauthorizedResponse()
                    else -> this.logger().log.add("upgrade request invalid!")
                }
            }
        }.ws("/*") { ws ->
            ws.onConnect { this.logger().log.add("connected with upgrade request") }
        }
    }

    @Test
    fun `each connection receives a unique id`() = TestUtil.test(contextPathJavalin()) { app, _ ->
        app.ws("/test-websocket-1") { ws ->
            ws.onConnect { ctx -> app.logger().log.add(ctx.sessionId) }
            ws.onMessage { ctx -> app.logger().log.add(ctx.sessionId) }
            ws.onClose { ctx -> app.logger().log.add(ctx.sessionId) }
        }
        app.routes {
            ws("/test-websocket-2") { ws ->
                ws.onConnect { ctx -> app.logger().log.add(ctx.sessionId) }
                ws.onMessage { ctx -> app.logger().log.add(ctx.sessionId) }
                ws.onClose { ctx -> app.logger().log.add(ctx.sessionId) }
            }
        }

        val testClient1_1 = TestClient(app, "/websocket/test-websocket-1")
        val testClient1_2 = TestClient(app, "/websocket/test-websocket-1")
        val testClient2_1 = TestClient(app, "/websocket/test-websocket-2")

        doAndSleepWhile({ testClient1_1.connect() }, { !testClient1_1.isOpen })
        doAndSleepWhile({ testClient1_2.connect() }, { !testClient1_2.isOpen })
        doAndSleep { testClient1_1.send("A") }
        doAndSleep { testClient1_2.send("B") }
        doAndSleepWhile({ testClient1_1.close() }, { testClient1_1.isClosing })
        doAndSleepWhile({ testClient1_2.close() }, { testClient1_2.isClosing })
        doAndSleepWhile({ testClient2_1.connect() }, { !testClient2_1.isOpen })
        doAndSleepWhile({ testClient2_1.close() }, { testClient2_1.isClosing })

        // 3 clients and a lot of operations should only yield three unique identifiers for the clients
        val uniqueLog = HashSet(app.logger().log)
        assertThat(uniqueLog).hasSize(3)
        uniqueLog.forEach { id -> assertThat(uniqueLog.count { it == id }).isEqualTo(1) }
    }

    @Test
    fun `general integration test`() = TestUtil.test(contextPathJavalin()) { app, _ ->
        val userUsernameMap = LinkedHashMap<WsContext, Int>()
        val atomicInteger = AtomicInteger()
        app.ws("/test-websocket-1") { ws ->
            ws.onConnect { ctx ->
                userUsernameMap[ctx] = atomicInteger.getAndIncrement()
                app.logger().log.add(userUsernameMap[ctx].toString() + " connected")
            }
            ws.onMessage { ctx ->
                val message = ctx.message()
                app.logger().log.add(userUsernameMap[ctx].toString() + " sent '" + message + "' to server")
                userUsernameMap.forEach { client, _ -> doAndSleep { client.send("Server sent '" + message + "' to " + userUsernameMap[client]) } }
            }
            ws.onClose { ctx -> app.logger().log.add(userUsernameMap[ctx].toString() + " disconnected") }
        }
        app.routes {
            ws("/test-websocket-2") { ws ->
                ws.onConnect { app.logger().log.add("Connected to other endpoint") }
                ws.onClose { _ -> app.logger().log.add("Disconnected from other endpoint") }
            }
        }

        val testClient1_1 = TestClient(app, "/websocket/test-websocket-1")
        val testClient1_2 = TestClient(app, "/websocket/test-websocket-1")
        val testClient2_1 = TestClient(app, "/websocket/test-websocket-2")

        doAndSleepWhile({ testClient1_1.connect() }, { !testClient1_1.isOpen })
        doAndSleepWhile({ testClient1_2.connect() }, { !testClient1_2.isOpen })
        doAndSleep { testClient1_1.send("A") }
        doAndSleep { testClient1_2.send("B") }
        doAndSleepWhile({ testClient1_1.close() }, { testClient1_1.isClosing })
        doAndSleepWhile({ testClient1_2.close() }, { testClient1_2.isClosing })
        doAndSleepWhile({ testClient2_1.connect() }, { !testClient2_1.isOpen })
        doAndSleepWhile({ testClient2_1.close() }, { testClient2_1.isClosing })
        assertThat(app.logger().log).containsExactlyInAnyOrder(
                "0 connected",
                "1 connected",
                "0 sent 'A' to server",
                "Server sent 'A' to 0",
                "Server sent 'A' to 1",
                "1 sent 'B' to server",
                "Server sent 'B' to 0",
                "Server sent 'B' to 1",
                "0 disconnected",
                "1 disconnected",
                "Connected to other endpoint",
                "Disconnected from other endpoint"
        )
    }

    @Test
    fun `receive and send json messages`() = TestUtil.test(contextPathJavalin()) { app, _ ->
        val clientMessage = SerializeableObject().apply { value1 = "test1"; value2 = "test2" }
        val clientMessageJson = JavalinJson.toJson(clientMessage)

        val serverMessage = SerializeableObject().apply { value1 = "echo1"; value2 = "echo2" }
        val serverMessageJson = JavalinJson.toJson(serverMessage)

        var receivedJson: String? = null
        var receivedMessage: SerializeableObject? = null
        app.ws("/message") { ws ->
            ws.onMessage { ctx ->
                receivedJson = ctx.message()
                receivedMessage = ctx.message<SerializeableObject>()
                ctx.send(serverMessage)
            }
        }

        val testClient = TestClient(app, "/websocket/message")
        doAndSleepWhile({ testClient.connect() }, { !testClient.isOpen })
        doAndSleep { testClient.send(clientMessageJson) }

        assertThat(receivedJson).isEqualTo(clientMessageJson)
        assertThat(receivedMessage).isNotNull
        assertThat(receivedMessage!!.value1).isEqualTo(clientMessage.value1)
        assertThat(receivedMessage!!.value2).isEqualTo(clientMessage.value2)
        assertThat(app.logger().log.last()).isEqualTo(serverMessageJson)
    }

    @Test
    fun `binary messages`() = TestUtil.test(contextPathJavalin()) { app, _ ->
        val byteDataToSend1 = (0 until 4096).shuffled().map { it.toByte() }.toByteArray()
        val byteDataToSend2 = (0 until 4096).shuffled().map { it.toByte() }.toByteArray()

        val receivedBinaryData = mutableListOf<ByteArray>()
        app.ws("/binary") { ws ->
            ws.onBinaryMessage { ctx ->
                receivedBinaryData.add(ctx.data.toByteArray())
            }
        }

        val testClient = TestClient(app, "/websocket/binary")

        doAndSleepWhile({ testClient.connect() }, { !testClient.isOpen })
        doAndSleep { testClient.send(byteDataToSend1) }
        doAndSleep { testClient.send(byteDataToSend2) }
        doAndSleepWhile({ testClient.close() }, { testClient.isClosing })

        assertThat(receivedBinaryData).containsExactlyInAnyOrder(byteDataToSend1, byteDataToSend2)
    }

    @Test
    fun `routing and pathParams() work`() = TestUtil.test(contextPathJavalin()) { app, _ ->
        app.ws("/params/:1") { ws -> ws.onConnect { ctx -> app.logger().log.add(ctx.pathParam("1")) } }
        app.ws("/params/:1/test/:2/:3") { ws -> ws.onConnect { ctx -> app.logger().log.add(ctx.pathParam("1") + " " + ctx.pathParam("2") + " " + ctx.pathParam("3")) } }
        app.ws("/*") { ws -> ws.onConnect { _ -> app.logger().log.add("catchall") } } // this should not be triggered since all calls match more specific handlers
        TestClient(app, "/websocket/params/one").connectAndDisconnect()
        TestClient(app, "/websocket/params/%E2%99%94").connectAndDisconnect()
        TestClient(app, "/websocket/params/another/test/long/path").connectAndDisconnect()
        assertThat(app.logger().log).containsExactlyInAnyOrder("one", "♔", "another long path")
        assertThat(app.logger().log).doesNotContain("catchall")
    }

    @Test
    fun `websocket 404 works`() = TestUtil.test { app, _ ->
        val response = Unirest.get("http://localhost:" + app.port() + "/invalid-path")
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Host", "localhost:" + app.port())
                .header("Sec-WebSocket-Key", "SGVsbG8sIHdvcmxkIQ==")
                .header("Sec-WebSocket-Version", "13")
                .asString()
        assertThat(response.body).containsSequence("WebSocket handler not found")
    }

    @Test
    fun `headers and host are available in session`() = TestUtil.test { app, _ ->
        app.ws("/websocket") { ws ->
            ws.onConnect { ctx -> app.logger().log.add("Header: " + ctx.header("Test")!!) }
            ws.onClose { ctx -> app.logger().log.add("Closed connection from: " + ctx.host()!!) }
        }
        TestClient(app, "/websocket", mapOf("Test" to "HeaderParameter")).connectAndDisconnect()
        assertThat(app.logger().log).containsExactlyInAnyOrder("Header: HeaderParameter", "Closed connection from: localhost")
    }

    @Test
    fun `extracting path information works`() = TestUtil.test { app, _ ->
        var matchedPath = ""
        var pathParam = ""
        var queryParam = ""
        var queryParams = listOf<String>()
        app.ws("/websocket/:channel") { ws ->
            ws.onConnect { ctx ->
                matchedPath = ctx.matchedPath()
                pathParam = ctx.pathParam("channel")
                queryParam = ctx.queryParam("qp")!!
                queryParams = ctx.queryParams("qps")
            }
        }
        TestClient(app, "/websocket/channel-one?qp=just-a-qp&qps=1&qps=2").connectAndDisconnect()
        assertThat(matchedPath).isEqualTo("/websocket/:channel")
        assertThat(pathParam).isEqualTo("channel-one")
        assertThat(queryParam).isEqualTo("just-a-qp")
        assertThat(queryParams).contains("1", "2")
    }

    @Test
    fun `routing and path-params case sensitive works`() = TestUtil.test { app, _ ->
        app.ws("/pAtH/:param") { ws -> ws.onConnect { ctx -> app.logger().log.add(ctx.pathParam("param")) } }
        app.ws("/other-path/:param") { ws -> ws.onConnect { ctx -> app.logger().log.add(ctx.pathParam("param")) } }
        TestClient(app, "/PaTh/my-param").connectAndDisconnect()
        TestClient(app, "/other-path/My-PaRaM").connectAndDisconnect()
        assertThat(app.logger().log).doesNotContain("my-param")
        assertThat(app.logger().log).contains("My-PaRaM")
    }

    @Test
    fun `web socket logging works`() = TestUtil.test(javalinWithWsLogger()) { app, _ ->
        app.ws("/path/:param") {}
        TestClient(app, "/path/0").connectAndDisconnect()
        TestClient(app, "/path/1").connectAndDisconnect()
        assertThat(app.logger().log).containsExactlyInAnyOrder(
                "0 connected",
                "1 connected",
                "0 disconnected",
                "1 disconnected"
        )
    }

    @Test
    fun `dev logging works for web sockets`() = TestUtil.test(Javalin.create().enableDevLogging()) { app, _ ->
        app.ws("/path/:param") {}
        TestClient(app, "/path/0").connectAndDisconnect()
        TestClient(app, "/path/1?test=banana&hi=1&hi=2").connectAndDisconnect()
        assertThat(app.logger().log.size).isEqualTo(0)
    }

    @Test
    fun `queryParamMap does not throw`() = TestUtil.test { app, _ ->
        app.ws("/*") { ws ->
            ws.onConnect { ctx ->
                ctx.queryParamMap()
                app.logger().log.add("call succeeded")
            }
        }

        TestClient(app, "/path/0").connectAndDisconnect()
        assertThat(app.logger().log).contains("call succeeded")
    }

    @Test
    fun `custom WebSocketServletFactory works`() {
        var err: Throwable? = Exception("Bang")
        val maxTextSize = 1
        val textToSend = "This text is far too long."
        val expectedMessage = "Text message size [${textToSend.length}] exceeds maximum size [$maxTextSize]"
        val app = Javalin.create().configure {
            it.wsFactoryConfig { wsFactory ->
                wsFactory.policy.maxTextMessageSize = maxTextSize
            }
        }.ws("/ws") { ws ->
            ws.onError { ctx -> err = ctx.error }
        }.start(0)

        val testClient = TestClient(app, "/ws")
        doAndSleepWhile({ testClient.connect() }, { !testClient.isOpen })
        doAndSleep { testClient.send(textToSend) }
        doAndSleepWhile({ testClient.close() }, { testClient.isClosing })
        app.stop()

        assertThat(err!!.message).isEqualTo(expectedMessage)
        assertThat(err).isExactlyInstanceOf(MessageTooLargeException::class.java)
    }

    @Test
    fun `accessmanager rejects invalid request`() = TestUtil.test(accessManagedJavalin()) { app, _ ->
        TestClient(app, "/").connectAndDisconnect()
        assertThat(app.logger().log.size).isEqualTo(2)
        assertThat(app.logger().log).containsExactlyInAnyOrder("handling upgrade request ...", "upgrade request invalid!")
    }

    @Test
    fun `accessmanager accepts valid request`() = TestUtil.test(accessManagedJavalin()) { app, _ ->
        TestClient(app, "/?allowed=true").connectAndDisconnect()
        assertThat(app.logger().log.size).isEqualTo(3)
        assertThat(app.logger().log).containsExactlyInAnyOrder("handling upgrade request ...", "upgrade request valid!", "connected with upgrade request")
    }

    @Test
    fun `accessmanager doesn't crash on exception`() = TestUtil.test(accessManagedJavalin()) { app, _ ->
        TestClient(app, "/?exception=true").connectAndDisconnect()
        assertThat(app.logger().log.size).isEqualTo(1)
    }

    @Test
    fun `cookies work`() = TestUtil.test { app, _ ->
        app.ws("/cookies") { ws ->
            ws.onConnect { ctx ->
                app.logger().log.add(ctx.cookie("name")!!)
                app.logger().log.add("cookieMapSize:${ctx.cookieMap().size}")
            }
        }
        TestClient(app, "/cookies", mapOf("Cookie" to "name=value; name2=value2; name3=value3")).connectAndDisconnect()
        assertThat(app.logger().log).containsExactly("value", "cookieMapSize:3")
    }

    // ********************************************************************************************
    // Helpers
    // ********************************************************************************************

    internal inner class TestClient(var app: Javalin, path: String, headers: Map<String, String> = emptyMap()) : WebSocketClient(URI.create("ws://localhost:" + app.port() + path), Draft_6455(), headers, 0) {

        override fun onOpen(serverHandshake: ServerHandshake) {}
        override fun onClose(i: Int, s: String, b: Boolean) {}
        override fun onError(e: Exception) {}
        override fun onMessage(s: String) {
            app.logger().log.add(s)
        }

        fun connectAndDisconnect() {
            doAndSleepWhile({ this.connect() }, { !this.isOpen })
            doAndSleepWhile({ this.close() }, { this.isClosing })
        }
    }

    private fun doAndSleepWhile(slowFunction: () -> Unit, conditionFunction: () -> Boolean) {
        val startTime = System.currentTimeMillis()
        slowFunction.invoke()
        while (conditionFunction.invoke() && System.currentTimeMillis() < startTime + 250) {
            Thread.sleep(2)
        }
    }

    private fun doAndSleep(func: () -> Unit) = func.invoke().also { Thread.sleep(50) }

}
