package io.vertx.guides.wiki.step04.database

import io.vertx.core.AsyncResult
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonArray
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import org.slf4j.LoggerFactory
import java.util.*
import java.util.stream.Collectors

internal class WikiDatabaseServiceImpl(private val dbClient: JDBCClient, private val sqlQueries: HashMap<SqlQuery, String>, readyHandler: Handler<AsyncResult<WikiDatabaseService>>) : WikiDatabaseService {

    init {

        dbClient.getConnection { ar ->
            if (ar.failed()) {
                LOGGER.error("Could not open a database connection", ar.cause())
                readyHandler.handle(Future.failedFuture(ar.cause()))
            } else {
                val connection = ar.result()
                connection.execute(sqlQueries[SqlQuery.CREATE_PAGES_TABLE]) { create ->
                    connection.close()
                    if (create.failed()) {
                        LOGGER.error("Database preparation error", create.cause())
                        readyHandler.handle(Future.failedFuture(create.cause()))
                    } else {
                        readyHandler.handle(Future.succeededFuture(this))
                    }
                }
            }
        }
    }

    override fun fetchAllPages(resultHandler: Handler<AsyncResult<JsonArray>>): WikiDatabaseService {
        dbClient.query(sqlQueries[SqlQuery.ALL_PAGES]) { res ->
            if (res.succeeded()) {
                val pages = res.result()
                        .results
                        .stream()
                        .map { json -> json.getString(0) }
                        .sorted()
                        .collect(Collectors.toList())
                resultHandler.handle(Future.succeededFuture(JsonArray(pages)))
            } else {
                LOGGER.error("Database query error", res.cause())
                resultHandler.handle(Future.failedFuture(res.cause()))
            }
        }
        return this
    }

    override fun fetchPage(name: String, resultHandler: Handler<AsyncResult<JsonObject>>): WikiDatabaseService {
        dbClient.queryWithParams(sqlQueries[SqlQuery.GET_PAGE], JsonArray().add(name)) { fetch ->
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
                resultHandler.handle(Future.succeededFuture(response))
            } else {
                LOGGER.error("Database query error", fetch.cause())
                resultHandler.handle(Future.failedFuture(fetch.cause()))
            }
        }
        return this
    }

    override fun createPage(title: String, markdown: String, resultHandler: Handler<AsyncResult<Void>>): WikiDatabaseService {
        val data = JsonArray().add(title).add(markdown)
        dbClient.updateWithParams(sqlQueries[SqlQuery.CREATE_PAGE], data) { res ->
            if (res.succeeded()) {
                resultHandler.handle(Future.succeededFuture())
            } else {
                LOGGER.error("Database query error", res.cause())
                resultHandler.handle(Future.failedFuture(res.cause()))
            }
        }
        return this
    }

    override fun savePage(id: Int, markdown: String, resultHandler: Handler<AsyncResult<Void>>): WikiDatabaseService {
        val data = JsonArray().add(markdown).add(id)
        dbClient.updateWithParams(sqlQueries[SqlQuery.SAVE_PAGE], data) { res ->
            if (res.succeeded()) {
                resultHandler.handle(Future.succeededFuture())
            } else {
                LOGGER.error("Database query error", res.cause())
                resultHandler.handle(Future.failedFuture(res.cause()))
            }
        }
        return this
    }

    override fun deletePage(id: Int, resultHandler: Handler<AsyncResult<Void>>): WikiDatabaseService {
        val data = JsonArray().add(id)
        dbClient.updateWithParams(sqlQueries[SqlQuery.DELETE_PAGE], data) { res ->
            if (res.succeeded()) {
                resultHandler.handle(Future.succeededFuture())
            } else {
                LOGGER.error("Database query error", res.cause())
                resultHandler.handle(Future.failedFuture(res.cause()))
            }
        }
        return this
    }

    companion object {

        private val LOGGER = LoggerFactory.getLogger(WikiDatabaseServiceImpl::class.java)
    }
}