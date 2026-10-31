package app.scalemate.health

import app.scalemate.food.*
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Loopback HTTP with synthetic credentials/responses, not a real AI provider. */
class HealthAiTransportTest {
    private data class Request(val path:String,val headers:Map<String,String>,val body:String)
    private class Loopback(val handler:(Request,OutputStream)->Unit):AutoCloseable {
        private val socket=ServerSocket(0,10,InetAddress.getByName("127.0.0.1"))
        val port get()=socket.localPort
        private val thread=Thread {
            while(!socket.isClosed)try {socket.accept().use {client->
                client.soTimeout=3000
                val input=client.getInputStream().bufferedReader()
                val path=input.readLine().split(' ')[1];val headers=mutableMapOf<String,String>()
                while(true){val line=input.readLine() ?: break;if(line.isEmpty())break;headers[line.substringBefore(':').lowercase()]=line.substringAfter(':').trim()}
                val size=headers["content-length"]?.toIntOrNull() ?: 0;val chars=CharArray(size);var read=0
                while(read<size){val n=input.read(chars,read,size-read);if(n<0)break;read+=n}
                handler(Request(path,headers,String(chars,0,read)),client.getOutputStream())
            }}catch(_:java.io.IOException){if(socket.isClosed)break}
        }.apply {isDaemon=true;start()}
        override fun close(){socket.close();thread.join(1500)}
    }
    private fun respond(out:OutputStream,body:String,status:Int=200,extra:String="") {
        val bytes=body.toByteArray();out.write("HTTP/1.1 $status Response\r\nContent-Type: text/event-stream\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n$extra\r\n".toByteArray());out.write(bytes);out.flush()
    }
    private fun service(port:Int,format:String="openai")=AiService(id="synthetic",baseUrl="http://127.0.0.1:$port/v1",model="synthetic-model",format=format)
    private fun messages()=JSONArray().put(JSONObject().put("role","user").put("content","Synthetic health protocol check"))
    private val client get()=AiClient(AiCredentialProvider {"synthetic-key"})
    @Test fun bothProviderProtocolsStreamTextThroughRealLoopbackHttp()=runBlocking {
        for(format in listOf("openai","anthropic")) {
            val payload=CompletableDeferred<JSONObject>()
            Loopback {request,out->
                assertEquals(if(format=="openai")"/v1/chat/completions" else "/v1/messages",request.path)
                assertEquals(if(format=="openai")"Bearer synthetic-key" else "synthetic-key",request.headers[if(format=="openai")"authorization" else "x-api-key"])
                payload.complete(JSONObject(request.body))
                val events=if(format=="openai")listOf("""{"choices":[{"delta":{"content":"Hello "}}]}""","""{"choices":[{"delta":{"content":"world"}}]}""","""{"choices":[{"delta":{},"finish_reason":"stop"}]}""") else listOf("""{"type":"content_block_delta","delta":{"type":"text_delta","text":"Hello "}}""","""{"type":"content_block_delta","delta":{"type":"text_delta","text":"world"}}""","""{"type":"message_stop"}""")
                respond(out,events.joinToString(""){"data: $it\n\n"})
            }.use {server->val partials=mutableListOf<String>();val result=client.streamText(service(server.port,format),messages()){partials+=it};assertEquals("Hello world",result);assertEquals(listOf("Hello ","Hello world"),partials);val body=payload.await();assertTrue(body.getBoolean("stream"));assertEquals("synthetic-model",body.getString("model"));if(format=="openai")assertTrue(body.getJSONObject("stream_options").getBoolean("include_usage"))}
        }
    }
    @Test fun streamingTransportReportsEachCumulativeTextChunk()=runBlocking {
        Loopback {_,out->
            val events=listOf(
                """{"choices":[{"delta":{"content":"{\"foods\":"}}]}""",
                """{"choices":[{"delta":{"content":"[]}"},"finish_reason":"stop"}]}"""
            )
            respond(out,events.joinToString(""){"data: $it\n\n"})
        }.use {server->
            val stages=mutableListOf<String>();val progress=mutableListOf<AiStream.Progress>()
            val result=client.streamResponse(
                service(server.port),messages(),64,5_000,{},{progress+=it},{stages+=it}
            )
            assertEquals("{\"foods\":[]}",result.text)
            assertEquals(listOf("uploading","waiting"),stages)
            assertEquals(listOf(1,2),progress.map {it.receivedChunks})
            assertEquals(listOf(9,12),progress.map {it.receivedChars})
            assertTrue(progress.all {it.estimatedOutputTokens>0})
            assertTrue(progress[1].estimatedOutputTokens>=progress[0].estimatedOutputTokens)
        }
    }
    @Test fun openAiUsageEventAfterFinishIsReportedBeforeDone()=runBlocking {
        Loopback {_,out->
            val events=listOf(
                """{"choices":[{"delta":{"content":"{\"foods\":[]}"}}]}""",
                """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
                """{"choices":[],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""",
                "[DONE]"
            )
            respond(out,events.joinToString(""){"data: $it\n\n"})
        }.use {server->
            val progress=mutableListOf<AiStream.Progress>()
            val result=client.streamResponse(
                service(server.port),messages(),64,5_000,{},{progress+=it}
            )
            assertEquals(AiUsage(10,5,15),result.usage)
            assertEquals(1,progress.last().receivedChunks)
            assertEquals(5L,progress.last().usage.outputTokens)
        }
    }
    @Test fun anthropicUsageBeforeFirstTextDoesNotCountAsATextChunk()=runBlocking {
        Loopback {_,out->
            val events=listOf(
                """{"type":"message_start","message":{"usage":{"input_tokens":10,"output_tokens":1}}}""",
                """{"type":"content_block_delta","delta":{"type":"text_delta","text":"{\"foods\":[]}"}}""",
                """{"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":5}}""",
                """{"type":"message_stop"}"""
            )
            respond(out,events.joinToString(""){"data: $it\n\n"})
        }.use {server->
            val progress=mutableListOf<AiStream.Progress>()
            val result=client.streamResponse(
                service(server.port,"anthropic"),messages(),64,5_000,{},{progress+=it}
            )
            assertEquals(AiUsage(10,5,null),result.usage)
            assertEquals(listOf(0,1,1),progress.map {it.receivedChunks})
            assertEquals(listOf(1L,1L,5L),progress.map {it.usage.outputTokens})
        }
    }
    @Test fun openAiRetriesWithoutOptionalUsageWhenCompatibilityServiceRejectsIt()=runBlocking {
        val requests=mutableListOf<JSONObject>()
        Loopback {request,out->
            requests+=JSONObject(request.body)
            if(requests.size==1) {
                respond(out,"""{"error":{"message":"Unsupported parameter: stream_options"}}""",400)
            } else {
                respond(out,"data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"foods\\\":[]}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
            }
        }.use {server->
            val progress=mutableListOf<AiStream.Progress>()
            val result=client.streamResponse(
                service(server.port),messages(),64,5_000,{},{progress+=it}
            )
            assertEquals("{\"foods\":[]}",result.text)
            assertEquals(2,requests.size)
            assertTrue(requests[0].has("stream_options"))
            assertFalse(requests[1].has("stream_options"))
            assertEquals(listOf(1),progress.map {it.receivedChunks})
        }
    }
    @Test fun redirectDoesNotForwardSyntheticCredentials()=runBlocking {
        val forwarded=AtomicInteger()
        Loopback {request,out->if(request.path=="/target"){forwarded.incrementAndGet();respond(out,"")}else respond(out,"",302,"Location: /target\r\n")}.use {server->
            try {client.streamText(service(server.port),messages()){};fail("Redirect must fail")}catch(_:FoodFailure){}
            assertEquals(0,forwarded.get())
        }
    }
    @Test fun eofWithoutACompletionEventIsRejected()=runBlocking {
        Loopback {_,out->respond(out,"data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n")}.use {server->
            try {client.streamText(service(server.port),messages()){};fail("Truncated response must fail")}catch(_:FoodFailure){}
        }
    }
    @Test fun cancellingAnActiveStreamDoesNotCompleteAReply()=runBlocking {
        val first=CompletableDeferred<Unit>();val release=CountDownLatch(1)
        Loopback {_,out->out.write("HTTP/1.0 200 OK\r\nContent-Type: text/event-stream\r\nConnection: close\r\n\r\ndata: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n".toByteArray());out.flush();release.await(1,TimeUnit.SECONDS)}.use {server->
            try {val job=launch {client.streamText(service(server.port),messages()){first.complete(Unit)};fail("Cancelled stream must not complete")};withTimeout(5000){first.await()};withTimeout(2000){job.cancelAndJoin()};assertTrue(job.isCancelled)}finally {release.countDown()}
        }
    }
}
