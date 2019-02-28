package io.vertx.guides.wiki.step04.http

import com.github.rjeschke.txtmark.Processor
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine
import io.vertx.guides.wiki.step04.database.WikiDatabaseService
import io.vertx.guides.wiki.step04.database.createProxy
import org.slf4j.LoggerFactory
import java.util.*

class HttpServerVerticle : AbstractVerticle() {

    private val templateEngine = FreeMarkerTemplateEngine.create()

    private var dbService: WikiDatabaseService? = null

    @Throws(Exception::class)
    override fun start(startFuture: Future<Void>) {

        val wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue") // <1>
        dbService = createProxy(vertx, wikiDbQueue)

        val server = vertx.createHttpServer()

        val router = Router.router(vertx)
        router.get("/").handler(::indexHandler)
        router.get("/wiki/:page").handler(::pageRenderingHandler)
        router.post().handler(BodyHandler.create())
        router.post("/save").handler(::pageUpdateHandler)
        router.post("/create").handler(::pageCreateHandler)
        router.post("/delete").handler(::pageDeletionHandler)

        val portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080)!!
        server
                .requestHandler { router.accept(it) }
                .listen(portNumber) { ar ->
                    if (ar.succeeded()) {
                        LOGGER.info("HTTP server running on port " + portNumber)
                        startFuture.complete()
                    } else {
                        LOGGER.error("Could not start a HTTP server", ar.cause())
                        startFuture.fail(ar.cause())
                    }
                }
    }

    // tag::db-service-calls[]
    private fun indexHandler(context: RoutingContext) {
        dbService?.fetchAllPages(Handler { reply ->
            if (reply.succeeded()) {
                context.put("title", "Wiki home")
                context.put("pages", reply.result().getList())
                templateEngine.render(context, "templates", "/index.ftl") { ar ->
                    if (ar.succeeded()) {
                        context.response().putHeader("Content-Type", "text/html")
                        context.response().end(ar.result())
                    } else {
                        context.fail(ar.cause())
                    }
                }
            } else {
                context.fail(reply.cause())
            }
        })
    }

    private fun pageRenderingHandler(context: RoutingContext) {
        val requestedPage = context.request().getParam("page")
        dbService?.fetchPage(requestedPage, Handler { reply ->
            if (reply.succeeded()) {

                val payLoad = reply.result()
                val found = payLoad.getBoolean("found") ?: false
                val rawContent = payLoad.getString("rawContent", EMPTY_PAGE_MARKDOWN)
                context.put("title", requestedPage)
                context.put("id", payLoad.getInteger("id", -1))
                context.put("newPage", if (found) "no" else "yes")
                context.put("rawContent", rawContent)
                context.put("content", Processor.process(rawContent))
                context.put("timestamp", Date().toString())

                templateEngine.render(context, "templates", "/page.ftl") { ar ->
                    if (ar.succeeded()) {
                        context.response().putHeader("Content-Type", "text/html")
                        context.response().end(ar.result())
                    } else {
                        context.fail(ar.cause())
                    }
                }

            } else {
                context.fail(reply.cause())
            }
        })
    }

    private fun pageUpdateHandler(context: RoutingContext) {
        val title = context.request().getParam("title")

        val handler: Handler<AsyncResult<Void>> = Handler { reply ->
            if (reply.succeeded()) {
                context.response().statusCode = 303
                context.response().putHeader("Location", "/wiki/" + title)
                context.response().end()
            } else {
                context.fail(reply.cause())
            }
        }

        val markdown = context.request().getParam("markdown")
        if ("yes" == context.request().getParam("newPage")) {
            dbService!!.createPage(title, markdown, handler)
        } else {
            dbService!!.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler)
        }
    }

    private fun pageCreateHandler(context: RoutingContext) {
        val pageName = context.request().getParam("name")
        var location = "/wiki/" + pageName!!
        if (pageName.isEmpty()) {
            location = "/"
        }
        context.response().statusCode = 303
        context.response().putHeader("Location", location)
        context.response().end()
    }

    private fun pageDeletionHandler(context: RoutingContext) {
        dbService?.deletePage(Integer.valueOf(context.request().getParam("id")), Handler { reply ->
            if (reply.succeeded()) {
                context.response().statusCode = 303
                context.response().putHeader("Location", "/")
                context.response().end()
            } else {
                context.fail(reply.cause())
            }
        })
    }

    companion object {

        const val CONFIG_HTTP_SERVER_PORT = "http.server.port"
        const val CONFIG_WIKIDB_QUEUE = "wikidb.queue"

        private val LOGGER = LoggerFactory.getLogger(HttpServerVerticle::class.java)

        private val EMPTY_PAGE_MARKDOWN = "# A new page\n" +
                "\n" +
                "Feel-free to write in Markdown!\n"
    }
}
