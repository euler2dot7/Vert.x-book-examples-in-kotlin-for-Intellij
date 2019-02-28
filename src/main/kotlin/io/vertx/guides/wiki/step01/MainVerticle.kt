package io.vertx.guides.wiki.step01

import com.github.rjeschke.txtmark.Processor
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine
import java.util.*
import java.util.stream.Collectors


@Suppress("unused")
class MainVerticle : AbstractVerticle() {

    private lateinit var dbClient: JDBCClient

    private fun prepareDatabase(): Future<Void> {
        val future: Future<Void> = Future.future()

        dbClient = JDBCClient.createShared(vertx, JsonObject()
                .put("url", "jdbc:hsqldb:file:db/wiki")
                .put("driver_class", "org.hsqldb.jdbcDriver")
                .put("max_pool_size", 30))

        dbClient.getConnection {
            if (it.failed()) {
                LOGGER.error("Could not open a database connection", it.cause())
                future.fail(it.cause())
            } else {
                val connection = it.result()
                connection.execute(SQL_CREATE_PAGES_TABLE, { create ->
                    connection.close()
                    if (create.failed()) {
                        LOGGER.error("Database preparation error", create.cause())
                        future.fail(create.cause())
                    } else {
                        future.complete()
                    }
                })
            }
        }
        return future
    }


    private val templateEngine = FreeMarkerTemplateEngine.create()

    private fun indexHandler(context: RoutingContext) {
        dbClient.getConnection({ car ->
            if (car.succeeded()) {
                val connection = car.result()
                connection.query(SQL_ALL_PAGES, { res ->
                    connection.close()
                    if (res.succeeded()) {
                        val pages = res.result().results.stream()
                                .map { json -> json.getString(0) }
                                .sorted().collect(Collectors.toList())

                        context.put("title", "Wiki home")
                        context.put("pages", pages)
                        templateEngine.render(context, "templates", "/index.ftl", { ar ->

                            if (ar.succeeded()) {
                                context.response().putHeader("Content-Type", "text/html")
                                context.response().end(ar.result())
                            } else {
                                context.fail(ar.cause())
                            }
                        });
                    } else {
                        context.fail(res.cause())
                    }
                })
            } else {
                context.fail(car.cause())
            }
        })
    }

    val EMPTY_PAGE_MARKDOWN =
            """# A new page
  Feel-free to write in Markdown!"""

    private fun pageRenderingHandler(context: RoutingContext) {
        val page = context.request().getParam("page")

        dbClient.getConnection { car ->
            if (car.succeeded()) {
                val connection = car.result()
                connection.queryWithParams(SQL_GET_PAGE, JsonArray().add(page)) { fetch ->
                    connection.close()
                    if (fetch.succeeded()) {
                        val row = fetch.result().results
                                .stream()
                                .findFirst()
                                .orElseGet { JsonArray().add(-1).add(EMPTY_PAGE_MARKDOWN) }
                        val id = row.getInteger(0)
                        val rawContent = row.getString(1)

                        context.put("title", page)
                        context.put("id", id)
                        context.put("newPage", if (fetch.result().results.size == 0) "yes" else "no")
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
                        context.fail(fetch.cause())
                    }
                }
            } else {
                context.fail(car.cause())
            }
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

    fun pageUpdateHandler(context: RoutingContext) {
        val id = context.request().getParam("id")
        val title = context.request().getParam("title")
        val markdown = context.request().getParam("markdown")
        val newPage = "yes" == context.request().getParam("newPage")

        dbClient.getConnection { car ->
            if (car.succeeded()) {
                val connection = car.result()
                val sql = if (newPage) SQL_CREATE_PAGE else SQL_SAVE_PAGE
                val params = JsonArray()
                if (newPage) {
                    params.add(title).add(markdown)
                } else {
                    params.add(markdown).add(id)
                }
                connection.updateWithParams(sql, params) { res ->
                    connection.close()
                    if (res.succeeded()) {
                        context.response().statusCode = 303
                        context.response().putHeader("Location", "/wiki/" + title)
                        context.response().end()
                    } else {
                        context.fail(res.cause())
                    }
                }
            } else {
                context.fail(car.cause())
            }
        }
    }

    private fun startHttpServer(): Future<Void> {
        val future: Future<Void> = Future.future()
        val server = vertx.createHttpServer()

        val router = Router.router(vertx)
        router.get("/").handler(::indexHandler)
        router.get("/wiki/:page").handler(::pageRenderingHandler)
        router.post().handler(BodyHandler.create())
        router.post("/save").handler(::pageUpdateHandler)
        router.post("/create").handler(::pageCreateHandler)
        router.post("/delete").handler(::pageDeletionHandler)

        server.requestHandler(router::accept)
                .listen(8080, { ar ->
                    if (ar.succeeded()) {
                        LOGGER.info("HTTP server running on port 8080")
                        future.complete();
                    } else {
                        LOGGER.error("Could not start a HTTP server", ar.cause())
                        future.fail(ar.cause())
                    }
                })

        return future
    }

    private fun pageDeletionHandler(context: RoutingContext) {
        val id = context.request().getParam("id")
        dbClient.getConnection { car ->
            if (car.succeeded()) {
                val connection = car.result()
                connection.updateWithParams(SQL_DELETE_PAGE, JsonArray().add(id)) { res ->
                    connection.close()
                    if (res.succeeded()) {
                        context.response().statusCode = 303
                        context.response().putHeader("Location", "/")
                        context.response().end()
                    } else {
                        context.fail(res.cause())
                    }
                }
            } else {
                context.fail(car.cause())
            }
        }
    }

    @Throws(Exception::class)
    override fun start(startFuture: Future<Void>) {
        val steps = prepareDatabase().compose { startHttpServer() }
        steps.setHandler(startFuture.completer())
    }

    companion object {
        const val SQL_CREATE_PAGES_TABLE = "create table if not exists Pages (Id integer identity primary key, Name varchar(255) unique, Content clob)"
        const val SQL_GET_PAGE = "select Id, Content from Pages where Name = ?"
        const val SQL_CREATE_PAGE = "insert into Pages values (NULL, ?, ?)"
        const val SQL_SAVE_PAGE = "update Pages set Content = ? where Id = ?"
        const val SQL_ALL_PAGES = "select Name from Pages"
        const val SQL_DELETE_PAGE = "delete from Pages where Id = ?"
        val LOGGER = LoggerFactory.getLogger(MainVerticle::class.java)
    }
}
