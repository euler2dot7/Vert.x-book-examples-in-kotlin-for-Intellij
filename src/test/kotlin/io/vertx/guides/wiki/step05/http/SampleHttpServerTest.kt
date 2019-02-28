package io.vertx.guides.wiki.step05.http


import io.vertx.core.Future
import io.vertx.core.Vertx
import io.vertx.core.http.HttpServer
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import io.vertx.ext.web.client.WebClient
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(VertxUnitRunner::class)
class SampleHttpServerTest {

    private lateinit var vertx: Vertx

    @Before
    fun prepare() {
        vertx = Vertx.vertx()
    }

    @After
    fun finish(context: TestContext) {
        vertx.close(context.asyncAssertSuccess())
    }

    @Test
    fun start_http_server(context: TestContext) {
        val async = context.async()

        vertx.createHttpServer().requestHandler { req -> req.response().putHeader("Content-Type", "text/plain").end("Ok") }
                .listen(8080, context.asyncAssertSuccess<HttpServer> {

                    val webClient = WebClient.create(vertx)

                    webClient.get(8080, "localhost", "/").send({ ar ->
                        if (ar.succeeded()) {
                            val response = ar.result()
                            context.assertTrue(response.headers().contains("Content-Type"))
                            context.assertEquals("text/plain", response.getHeader("Content-Type"))
                            context.assertEquals("Ok", response.body().toString())
                            async.complete()
                        } else {
                            async.resolve(Future.failedFuture(ar.cause()))
                        }
                    })
                })
    }
}