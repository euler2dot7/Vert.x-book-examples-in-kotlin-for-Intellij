package io.vertx.guides.wiki.step07.http

import com.github.rjeschke.txtmark.Processor
import io.vertx.core.AbstractVerticle
import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.http.HttpServerOptions
import io.vertx.core.json.JsonObject
import io.vertx.core.net.JksOptions
import io.vertx.ext.auth.shiro.ShiroAuth
import io.vertx.ext.web.Router
import io.vertx.ext.web.RoutingContext
import io.vertx.ext.web.client.WebClient
import io.vertx.ext.web.client.WebClientOptions
import io.vertx.ext.web.codec.BodyCodec
import io.vertx.ext.web.templ.FreeMarkerTemplateEngine
import io.vertx.guides.wiki.step06.database.WikiDatabaseService
import io.vertx.guides.wiki.step06.database.createProxy
import org.slf4j.LoggerFactory
import java.util.*
import java.util.stream.Collectors
import io.vertx.ext.auth.shiro.ShiroAuthRealmType
import io.vertx.ext.auth.shiro.ShiroAuthOptions
import io.vertx.ext.web.handler.*
import io.vertx.ext.web.sstore.LocalSessionStore


class HttpServerVerticle : AbstractVerticle() {

    private val templateEngine = FreeMarkerTemplateEngine.create()

    private lateinit var dbService: WikiDatabaseService
    private lateinit var webClient: WebClient

    @Throws(Exception::class)
    override fun start(startFuture: Future<Void>) {

        val wikiDbQueue = config().getString(CONFIG_WIKIDB_QUEUE, "wikidb.queue") // <1>
        dbService = createProxy(vertx, wikiDbQueue)

        webClient = WebClient.create(vertx, WebClientOptions()
                .setSsl(true)
                .setUserAgent("vert-x3"))

        val server = vertx.createHttpServer(HttpServerOptions()
                .setSsl(true)
                .setKeyStoreOptions(JksOptions()
                        .setPath("server-keystore.jks")
                        .setPassword("secret")))

        val auth = ShiroAuth.create(vertx, ShiroAuthOptions()
                .setType(ShiroAuthRealmType.PROPERTIES)
                .setConfig(JsonObject()
                        .put("properties_path", "classpath:wiki-users.properties")))


        val router = Router.router(vertx)

        router.route().handler(CookieHandler.create())
        router.route().handler(BodyHandler.create())
        router.route().handler(SessionHandler.create(LocalSessionStore.create(vertx)))
        router.route().handler(UserSessionHandler.create(auth))  // <1>

        val authHandler = RedirectAuthHandler.create(auth, "/login") // <2>
        router.route("/").handler(authHandler)  // <3>
        router.route("/wiki/*").handler(authHandler)
        router.route("/action/*").handler(authHandler)

        router.get("/").handler(this::indexHandler)
        router.get("/wiki/:page").handler(this::pageRenderingHandler)
        router.post("/action/save").handler(this::pageUpdateHandler)
        router.post("/action/create").handler(this::pageCreateHandler)
        router.get("/action/backup").handler(this::backupHandler)
        router.post("/action/delete").handler(this::pageDeletionHandler)
        // end::shiro-routes[]

        // tag::shiro-login[]
        router.get("/login").handler(this::loginHandler)
        router.post("/login-auth").handler(FormLoginHandler.create(auth))  // <1>

        router.get("/logout").handler({ context ->
            context.clearUser()  // <2>
            context.response()
                    .setStatusCode(302)
                    .putHeader("Location", "/")
                    .end()
        })

        val apiRouter = Router.router(vertx)
        apiRouter.get("/pages").handler(::apiRoot)
        apiRouter.get("/pages/:id").handler(::apiGetPage)
        apiRouter.post().handler(BodyHandler.create())
        apiRouter.post("/pages").handler(::apiCreatePage)
        apiRouter.put().handler(BodyHandler.create())
        apiRouter.put("/pages/:id").handler(::apiUpdatePage)
        apiRouter.delete("/pages/:id").handler(::apiDeletePage)
        router.mountSubRouter("/api", apiRouter) // <1>


        val portNumber = config().getInteger(CONFIG_HTTP_SERVER_PORT, 8080)
        server.requestHandler(router::accept).listen(portNumber) { ar ->
            if (ar.succeeded()) {
                LOGGER.info("HTTP server running on port " + portNumber)
                startFuture.complete()
            } else {
                LOGGER.error("Could not start a HTTP server", ar.cause())
                startFuture.fail(ar.cause())
            }
        }
    }

    private fun loginHandler(context: RoutingContext) {
        context.put("title", "Login")
        templateEngine.render(context, "templates", "/login.ftl") { ar ->
            if (ar.succeeded()) {
                context.response().putHeader("Content-Type", "text/html")
                context.response().end(ar.result())
            } else {
                context.fail(ar.cause())
            }
        }
    }

    private fun apiDeletePage(context: RoutingContext) {
        val id = Integer.valueOf(context.request().getParam("id"))
        dbService.deletePage(id, Handler { reply -> handleSimpleDbReply(context, reply) })
    }

    private fun handleSimpleDbReply(context: RoutingContext, reply: AsyncResult<Void>) {
        if (reply.succeeded()) {
            context.response().statusCode = 200
            context.response().putHeader("Content-Type", "application/json")
            context.response().end(JsonObject().put("success", true).encode())
        } else {
            context.response().statusCode = 500
            context.response().putHeader("Content-Type", "application/json")
            context.response().end(JsonObject()
                    .put("success", false)
                    .put("error", reply.cause().message).encode())
        }
    }

    private fun apiUpdatePage(context: RoutingContext) {
        val id = Integer.valueOf(context.request().getParam("id"))
        val page = context.bodyAsJson
        if (!validateJsonPageDocument(context, page, "markdown")) {
            return
        }
        dbService.savePage(id, page.getString("markdown"), Handler { handleSimpleDbReply(context, it) })
    }

    private fun apiCreatePage(context: RoutingContext) {
        val page = if (context.bodyAsString.isEmpty())
            JsonObject()
        else context.bodyAsJson

        if (!validateJsonPageDocument(context, page, "name", "markdown")) {
            return
        }
        dbService.createPage(page.getString("name"), page.getString("markdown"), Handler { reply ->
            if (reply.succeeded()) {
                context.response().statusCode = 201
                context.response().putHeader("Content-Type", "application/json")
                context.response().end(JsonObject().put("success", true).encode())
            } else {
                context.response().statusCode = 500
                context.response().putHeader("Content-Type", "application/json")
                context.response().end(JsonObject()
                        .put("success", false)
                        .put("error", reply.cause().message).encode())
            }
        })
    }

    private fun validateJsonPageDocument(context: RoutingContext, page: JsonObject, vararg expectedKeys: String): Boolean {
        if (!Arrays.stream(expectedKeys).allMatch(page::containsKey)) {
            LOGGER.error("Bad page creation JSON payload: " + page.encodePrettily() + " from " + context.request().remoteAddress())
            context.response().statusCode = 400
            context.response().putHeader("Content-Type", "application/json")
            context.response().end(JsonObject()
                    .put("success", false)
                    .put("error", "Bad request payload").encode())
            return false
        }
        return true
    }
    // end::validateJsonPageDocument[]

    // tag::apiGetPage[]
    private fun apiGetPage(context: RoutingContext) {
        val id = Integer.valueOf(context.request().getParam("id"))
        dbService.fetchPageById(id, Handler { reply ->
            val response = JsonObject()
            if (reply.succeeded()) {
                val dbObject = reply.result()
                if (dbObject.getBoolean("found")!!) {
                    val payload = JsonObject()
                            .put("name", dbObject.getString("name"))
                            .put("id", dbObject.getInteger("id"))
                            .put("markdown", dbObject.getString("content"))
                            .put("html", Processor.process(dbObject.getString("content")))
                    response
                            .put("success", true)
                            .put("page", payload)
                    context.response().statusCode = 200
                } else {
                    context.response().statusCode = 404
                    response
                            .put("success", false)
                            .put("error", "There is no page with ID " + id)
                }
            } else {
                response
                        .put("success", false)
                        .put("error", reply.cause().message)
                context.response().statusCode = 500
            }
            context.response().putHeader("Content-Type", "application/json")
            context.response().end(response.encode())
        })
    }
    // end::apiGetPage[]

    // tag::apiRoot[]
    private fun apiRoot(context: RoutingContext) {
        dbService.fetchAllPagesData(Handler { reply ->
            val response = JsonObject()
            if (reply.succeeded()) {
                val pages = reply.result()
                        .stream()
                        .map({ obj ->
                            JsonObject()
                                    .put("id", obj.getInteger("ID"))  // <1>
                                    .put("name", obj.getString("NAME"))
                        })
                        .collect(Collectors.toList())
                response
                        .put("success", true)
                        .put("pages", pages) // <2>
                context.response().statusCode = 200
                context.response().putHeader("Content-Type", "application/json")
                context.response().end(response.encode()) // <3>
            } else {
                response
                        .put("success", false)
                        .put("error", reply.cause().message)
                context.response().statusCode = 500
                context.response().putHeader("Content-Type", "application/json")
                context.response().end(response.encode())
            }
        })
    }


    private fun indexHandler(context: RoutingContext) {
        context.user().isAuthorised("create") {  // <1>
            res ->
            val canCreatePage = res.succeeded() && res.result()  // <2>
            dbService.fetchAllPages(Handler { reply ->
                if (reply.succeeded()) {
                    context.put("title", "Wiki home")
                    context.put("pages", reply.result().getList())
                    context.put("canCreatePage", canCreatePage)  // <3>
                    context.put("username", context.user().principal().getString("username"))  // <4>
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
    }

    private fun pageRenderingHandler(context: RoutingContext) {
        val requestedPage = context.request().getParam("page")
        dbService.fetchPage(requestedPage, Handler { reply ->
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
            dbService.createPage(title, markdown, handler)
        } else {
            dbService.savePage(Integer.valueOf(context.request().getParam("id")), markdown, handler)
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

//    private fun pageDeletionHandler(context: RoutingContext) {
//        dbService.deletePage(Integer.valueOf(context.request().getParam("id")), Handler { reply ->
//            if (reply.succeeded()) {
//                context.response().statusCode = 303
//                context.response().putHeader("Location", "/")
//                context.response().end()
//            } else {
//                context.fail(reply.cause())
//            }
//        })
//    }


    private fun pageDeletionHandler(context: RoutingContext) {
        context.user().isAuthorised("delete") { res ->
            if (res.succeeded() && res.result()) {
                // Original code:
                dbService.deletePage(Integer.valueOf(context.request().getParam("id")), Handler { reply ->
                    if (reply.succeeded()) {
                        context.response().statusCode = 303
                        context.response().putHeader("Location", "/")
                        context.response().end()
                    } else {
                        context.fail(reply.cause())
                    }
                })
            } else {
                context.response().setStatusCode(403).end()
            }
        }
    }

    private fun backupHandler(context: RoutingContext) {
        dbService.fetchAllPagesData(Handler { reply ->
            if (reply.succeeded()) {

                val filesObject = JsonObject()
                val gistPayload = JsonObject() // <1>
                        .put("files", filesObject)
                        .put("description", "A wiki backup")
                        .put("public", true)

                reply
                        .result()
                        .forEach { page ->
                            val fileObject = JsonObject() // <2>
                            filesObject.put(page.getString("NAME"), fileObject)
                            fileObject.put("content", page.getString("CONTENT"))
                        }

                webClient.post(443, "api.github.com", "/gists") // <3>
                        .putHeader("Accept", "application/vnd.github.v3+json") // <4>
                        .putHeader("Content-Type", "application/json")
                        .`as`(BodyCodec.jsonObject()) // <5>
                        .sendJsonObject(gistPayload, {
                            // <6>
                            if (it.succeeded()) {
                                val response = it.result()
                                if (response.statusCode() == 201) {
                                    context.put("backup_gist_url", response.body().getString("html_url"))  // <7>
                                    indexHandler(context)
                                } else {
                                    val message = StringBuilder()
                                            .append("Could not backup the wiki: ")
                                            .append(response.statusMessage())
                                            .append(System.getProperty("line.separator")).append(response.body()?.encodePrettily()
                                            ?: "")
                                    LOGGER.error(message.toString())
                                    context.fail(502)
                                }
                            } else {
                                val err = it.cause()
                                LOGGER.error("HTTP Client error", err)
                                context.fail(err)
                            }
                        })

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
