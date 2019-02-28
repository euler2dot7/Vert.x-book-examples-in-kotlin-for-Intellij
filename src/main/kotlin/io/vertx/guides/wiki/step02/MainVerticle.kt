package io.vertx.guides.wiki.step02

import com.github.rjeschke.txtmark.Processor
import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.core.eventbus.DeliveryOptions
import io.vertx.core.eventbus.Message
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.core.logging.LoggerFactory
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.handler.BodyHandler
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*
import java.util.stream.Collectors

@Suppress("unused")
class MainVerticle : AbstractVerticle() {

    override fun start(startFuture: Future<Void>) {
        val dbVerticleDeployment: Future<String> = Future.future()
        vertx.deployVerticle(WikiDatabaseVerticle(), dbVerticleDeployment.completer())

        dbVerticleDeployment.compose {
            val httpVerticleDeployment: Future<String> = Future.future()
            vertx.deployVerticle(
                    HttpServerVerticle::class.java.name,
                    DeploymentOptions().setInstances(2),
                    httpVerticleDeployment.completer())
            httpVerticleDeployment
        }.setHandler({ ar ->
                    if (ar.succeeded()) {
                        startFuture.complete()
                    } else {
                        startFuture.fail(ar.cause())
                    }
                })
    }
}

internal class HttpServerVerticle : AbstractVerticle() {

    private var wikiDbQueue = "wikidb.queue"

    companion object {
        private val LOGGER = LoggerFactory.getLogger(HttpServerVerticle::class.java)
        const val CONFIG_HTTP_SERVER_PORT = "http.server.port"
        const val CONFIG_WIKIDB_QUEUE = "wikidb.queue"
        const val EMPTY_PAGE_MARKDOWN =
                """
            # A new page

            Feel-free to write in Markdown!
            """
    }

    @Throws(Exception::class)
    override fun start(startFuture: Future<Void>) {
        wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, WikiDatabaseVerticle.CONFIG_WIKIDB_QUEUE)

        val server = vertx.createHttpServer()
        val router = Router.router(vertx)
        router.get("/").handler(::indexHandler)
        router.get("/wiki/:page").handler(::pageRenderingHandler)
        router.post().handler(BodyHandler.create())
        router.post("/save").handler(::pageUpdateHandler)
        router.post("/create").handler(::pageCreateHandler)
        router.post("/delete").handler(::pageDeletionHandler)

        val portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080)
        server.requestHandler(router::accept)
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

    private val templateEngine = FreeMarkerTemplateEngine.create()

    private fun indexHandler(context: RoutingContext) {
        val options = DeliveryOptions().addHeader("action", "all-pages")
        vertx.eventBus().send<JsonObject>(wikiDbQueue, JsonObject(), options, { reply ->
            if (reply.succeeded()) {
                val body = reply.result().body()
                context.put("title", "Wiki home")
                context.put("pages", body.getJsonArray("pages").list)
                templateEngine.render(context, "templates", "/index.ftl", { ar ->
                    if (ar.succeeded()) {
                        context.response().putHeader("Content-Type", "text/html")
                        context.response().end(ar.result())
                    } else {
                        context.fail(ar.cause())
                    }
                })
            } else {
                context.fail(reply.cause())
            }
        })
    }


    private fun pageRenderingHandler(context: RoutingContext) {

        val requestedPage = context.request().getParam("page")
        val request = JsonObject().put("page", requestedPage)

        val options = DeliveryOptions().addHeader("action", "get-page")
        vertx.eventBus().send<JsonObject>(wikiDbQueue, request, options) { reply ->

            if (reply.succeeded()) {
                val body = reply.result().body()

                val found = body.getBoolean("found")
                val rawContent = body.getString("rawContent", EMPTY_PAGE_MARKDOWN)
                context.put("title", requestedPage)
                context.put("id", body.getInteger("id", -1))
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
        }
    }

    private fun pageUpdateHandler(context: RoutingContext) {

        val title = context.request().getParam("title")
        val request = JsonObject()
                .put("id", context.request().getParam("id"))
                .put("title", title)
                .put("markdown", context.request().getParam("markdown"))

        val options = DeliveryOptions()
        if ("yes" == context.request().getParam("newPage")) {
            options.addHeader("action", "create-page")
        } else {
            options.addHeader("action", "save-page")
        }

        vertx.eventBus().send<Any>(wikiDbQueue, request, options) { reply ->
            if (reply.succeeded()) {
                context.response().statusCode = 303
                context.response().putHeader("Location", "/wiki/" + title)
                context.response().end()
            } else {
                context.fail(reply.cause())
            }
        }
    }

    private fun pageCreateHandler(context: RoutingContext) {
        val pageName = context.request().getParam("name")
        var location = "/wiki/" + pageName
        if (pageName.isEmpty()) {
            location = "/"
        }
        context.response().statusCode = 303
        context.response().putHeader("Location", location)
        context.response().end()
    }

    private fun pageDeletionHandler(context: RoutingContext) {
        val id = context.request().getParam("id")
        val request = JsonObject().put("id", id)
        val options = DeliveryOptions().addHeader("action", "delete-page")
        vertx.eventBus().send<Any>(wikiDbQueue, request, options) { reply ->
            if (reply.succeeded()) {
                context.response().statusCode = 303
                context.response().putHeader("Location", "/")
                context.response().end()
            } else {
                context.fail(reply.cause())
            }
        }
    }
}

class WikiDatabaseVerticle : AbstractVerticle() {
    private enum class SqlQuery {
        CREATE_PAGES_TABLE,
        ALL_PAGES,
        GET_PAGE,
        CREATE_PAGE,
        SAVE_PAGE,
        DELETE_PAGE
    }

    private var sqlQueries: HashMap<SqlQuery, String> = HashMap()

    companion object {
        const val CONFIG_WIKIDB_JDBC_URL = "wikidb.jdbc.url"
        const val CONFIG_WIKIDB_JDBC_DRIVER_CLASS = "wikidb.jdbc.driver_class"
        const val CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE = "wikidb.jdbc.max_pool_size"
        const val CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE = "wikidb.sqlqueries.resource.file"
        const val CONFIG_WIKIDB_QUEUE = "wikidb.queue"

        val LOGGER = LoggerFactory.getLogger(WikiDatabaseVerticle::class.java)
    }

    @Throws(IOException::class)
    private fun loadSqlQueries() {

        val queriesFile = config().getString(CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE)
        val queriesInputStream: InputStream
        queriesInputStream = if (queriesFile != null) {
            FileInputStream(queriesFile)
        } else {
            javaClass.getResourceAsStream("/db-queries.properties")
        }

        val queriesProps = Properties()
        queriesProps.load(queriesInputStream)
        queriesInputStream.close()

        sqlQueries[SqlQuery.CREATE_PAGES_TABLE] = queriesProps.getProperty("create-pages-table")
        sqlQueries[SqlQuery.ALL_PAGES] = queriesProps.getProperty("all-pages")
        sqlQueries[SqlQuery.GET_PAGE] = queriesProps.getProperty("get-page")
        sqlQueries[SqlQuery.CREATE_PAGE] = queriesProps.getProperty("create-page")
        sqlQueries[SqlQuery.SAVE_PAGE] = queriesProps.getProperty("save-page")
        sqlQueries[SqlQuery.DELETE_PAGE] = queriesProps.getProperty("delete-page")
    }

    private lateinit var dbClient: JDBCClient

    override fun start(startFuture: Future<Void>) {

        /*
         * Note: this uses blocking APIs, but data is small...
         */
        loadSqlQueries()

        dbClient = JDBCClient.createShared(vertx, JsonObject()
                .put("url", config().getString(CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:file:db/wiki"))
                .put("driver_class", config().getString(CONFIG_WIKIDB_JDBC_DRIVER_CLASS, "org.hsqldb.jdbcDriver"))
                .put("max_pool_size", config().getInteger(CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 30)))

        dbClient.getConnection({ ar ->
            if (ar.failed()) {
                LOGGER.error("Could not open a database connection", ar.cause())
                startFuture.fail(ar.cause())
            } else {
                val connection = ar.result()
                connection.execute(sqlQueries[SqlQuery.CREATE_PAGES_TABLE], { create ->
                    connection.close()
                    if (create.failed()) {
                        LOGGER.error("Database preparation error", create.cause())
                        startFuture.fail(create.cause())
                    } else {
                        vertx.eventBus().consumer(config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue"), this::onMessage)
                        startFuture.complete()
                    }
                })
            }
        })
    }

    enum class ErrorCodes {
        NO_ACTION_SPECIFIED,
        BAD_ACTION,
        DB_ERROR
    }

    fun onMessage(message: Message<JsonObject>) {

        if (!message.headers().contains("action")) {
            LOGGER.error("No action header specified for message with headers {} and body {}",
                    message.headers(), message.body().encodePrettily())
            message.fail(ErrorCodes.NO_ACTION_SPECIFIED.ordinal, "No action header specified")
            return
        }
        val action = message.headers().get("action")

        when (action) {
            "all-pages" -> fetchAllPages(message)
            "get-page" -> fetchPage(message)
            "create-page" -> createPage(message)
            "save-page" -> savePage(message)
            "delete-page" -> deletePage(message)
            else -> message.fail(ErrorCodes.BAD_ACTION.ordinal, "Bad action: " + action)
        }
    }

    private fun fetchAllPages(message: Message<JsonObject>) {
        dbClient.query(sqlQueries[SqlQuery.ALL_PAGES]) { res ->
            if (res.succeeded()) {
                val pages = res.result()
                        .results
                        .stream()
                        .map { json -> json.getString(0) }
                        .sorted()
                        .collect(Collectors.toList())
                message.reply(JsonObject().put("pages", JsonArray(pages)))
            } else {
                reportQueryError(message, res.cause())
            }
        }
    }

    private fun fetchPage(message: Message<JsonObject>) {
        val requestedPage = message.body().getString("page")
        val params = JsonArray().add(requestedPage)

        dbClient.queryWithParams(sqlQueries[SqlQuery.GET_PAGE], params) { fetch ->
            if (fetch.succeeded()) {
                val response = JsonObject()
                val resultSet = fetch.result()
                if (resultSet.numRows == 0) {
                    response.put("found", false)
                } else {
                    response.put("found", true)
                    val row = resultSet.results[0]
                    response.put("id", row.getInteger(0))
                    response.put("rawContent", row.getString(1))
                }
                message.reply(response)
            } else {
                reportQueryError(message, fetch.cause())
            }
        }
    }

    private fun createPage(message: Message<JsonObject>) {
        val request = message.body()
        val data = JsonArray()
                .add(request.getString("title"))
                .add(request.getString("markdown"))

        dbClient.updateWithParams(sqlQueries[SqlQuery.CREATE_PAGE], data) { res ->
            if (res.succeeded()) {
                message.reply("ok")
            } else {
                reportQueryError(message, res.cause())
            }
        }
    }

    private fun savePage(message: Message<JsonObject>) {
        val request = message.body()
        val data = JsonArray()
                .add(request.getString("markdown"))
                .add(request.getString("id"))

        dbClient.updateWithParams(sqlQueries[SqlQuery.SAVE_PAGE], data) { res ->
            if (res.succeeded()) {
                message.reply("ok")
            } else {
                reportQueryError(message, res.cause())
            }
        }
    }

    private fun deletePage(message: Message<JsonObject>) {
        val data = JsonArray().add(message.body().getString("id"))

        dbClient.updateWithParams(sqlQueries[SqlQuery.DELETE_PAGE], data) { res ->
            if (res.succeeded()) {
                message.reply("ok")
            } else {
                reportQueryError(message, res.cause())
            }
        }
    }

    private fun reportQueryError(message: Message<JsonObject>, cause: Throwable) {
        LOGGER.error("Database query error", cause)
        message.fail(ErrorCodes.DB_ERROR.ordinal, cause.message)
    }
}
