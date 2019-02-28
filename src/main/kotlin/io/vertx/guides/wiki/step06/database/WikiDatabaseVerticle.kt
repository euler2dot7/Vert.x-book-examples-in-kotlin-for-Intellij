package io.vertx.guides.wiki.step06.database

import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.Handler
import io.vertx.core.json.JsonObject
import io.vertx.ext.jdbc.JDBCClient
import io.vertx.serviceproxy.ServiceBinder
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

class WikiDatabaseVerticle : AbstractVerticle() {

    @Throws(Exception::class)
    override fun start(startFuture: Future<Void>) {
        val sqlQueries = loadSqlQueries()

        val dbClient = JDBCClient.createShared(vertx, JsonObject()
                .put("url", config().getString(CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:file:db/wiki"))
                .put("driver_class", config().getString(CONFIG_WIKIDB_JDBC_DRIVER_CLASS, "org.hsqldb.jdbcDriver"))
                .put("max_pool_size", config().getInteger(CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 30)))

        create(dbClient, sqlQueries, Handler {
            if (it.succeeded()) {
//                ProxyHelper.registerService(WikiDatabaseService::class.java, vertx, it.result(), CONFIG_WIKIDB_QUEUE) // <1>
                ServiceBinder(vertx).setAddress(CONFIG_WIKIDB_QUEUE).register(WikiDatabaseService::class.java,it.result())
                startFuture.complete()
            } else {
                startFuture.fail(it.cause())
            }
        })
    }

    /*
   * Note: this uses blocking APIs, but data is small...
   */
    @Throws(IOException::class)
    private fun loadSqlQueries(): HashMap<SqlQuery, String> {

        val queriesFile = config().getString(CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE)
        val queriesInputStream: InputStream
        if (queriesFile != null) {
            queriesInputStream = FileInputStream(queriesFile)
        } else {
            queriesInputStream = javaClass.getResourceAsStream("/db-queries.properties")
        }

        val queriesProps = Properties()
        queriesProps.load(queriesInputStream)
        queriesInputStream.close()

        val sqlQueries = HashMap<SqlQuery, String>()
        sqlQueries[SqlQuery.CREATE_PAGES_TABLE] = queriesProps.getProperty("create-pages-table")
        sqlQueries[SqlQuery.ALL_PAGES] = queriesProps.getProperty("all-pages")
        sqlQueries[SqlQuery.ALL_PAGES_DATA] = queriesProps.getProperty("all-pages-data")
        sqlQueries[SqlQuery.GET_PAGE] = queriesProps.getProperty("get-page")
        sqlQueries[SqlQuery.GET_PAGE_BY_ID] = queriesProps.getProperty("get-page-by-id")
        sqlQueries[SqlQuery.CREATE_PAGE] = queriesProps.getProperty("create-page")
        sqlQueries[SqlQuery.SAVE_PAGE] = queriesProps.getProperty("save-page")
        sqlQueries[SqlQuery.DELETE_PAGE] = queriesProps.getProperty("delete-page")
        return sqlQueries
    }

    companion object {
        const val CONFIG_WIKIDB_JDBC_URL = "wikidb.jdbc.url"
        const val CONFIG_WIKIDB_JDBC_DRIVER_CLASS = "wikidb.jdbc.driver_class"
        const val CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE = "wikidb.jdbc.max_pool_size"
        const val CONFIG_WIKIDB_SQL_QUERIES_RESOURCE_FILE = "wikidb.sqlqueries.resource.file"
        const val CONFIG_WIKIDB_QUEUE = "wikidb.queue"
    }
}
