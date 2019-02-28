package io.vertx.guides.wiki.step03.database

import io.vertx.codegen.annotations.Fluent
import io.vertx.codegen.annotations.ProxyGen
import io.vertx.core.AsyncResult
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import java.util.*

@ProxyGen
interface WikiDatabaseService {

    @Fluent
    fun fetchAllPages(resultHandler: Handler<AsyncResult<JsonArray>>): WikiDatabaseService

    @Fluent
    fun fetchPage(name: String, resultHandler: Handler<AsyncResult<JsonObject>>): WikiDatabaseService

    @Fluent
    fun createPage(title: String, markdown: String, resultHandler: Handler<AsyncResult<Void>>): WikiDatabaseService

    @Fluent
    fun savePage(id: Int, markdown: String, resultHandler: Handler<AsyncResult<Void>>): WikiDatabaseService

    @Fluent
    fun deletePage(id: Int, resultHandler: Handler<AsyncResult<Void>>): WikiDatabaseService
}


fun create(
        dbClient: JDBCClient,
        sqlQueries: HashMap<SqlQuery, String>,
        readyHandler: Handler<AsyncResult<WikiDatabaseService>>
): WikiDatabaseService {
    return WikiDatabaseServiceImpl(dbClient, sqlQueries, readyHandler)
}

fun createProxy(vertx: Vertx, address: String): WikiDatabaseService {
    return WikiDatabaseServiceVertxEBProxy(vertx, address)
}

enum class SqlQuery {
    CREATE_PAGES_TABLE,
    ALL_PAGES,
    GET_PAGE,
    CREATE_PAGE,
    SAVE_PAGE,
    DELETE_PAGE
}

enum class ErrorCodes {
    NO_ACTION_SPECIFIED,
    BAD_ACTION,
    DB_ERROR
}