package io.vertx.guides.wiki.step04.database


import io.vertx.core.DeploymentOptions
import io.vertx.core.Handler
import io.vertx.core.Vertx
import io.vertx.core.json.JsonObject
import io.vertx.ext.unit.TestContext
import io.vertx.ext.unit.junit.VertxUnitRunner
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(VertxUnitRunner::class)
class WikiDatabaseVerticleTest {

    private lateinit var vertx: Vertx
    private lateinit var service: io.vertx.guides.wiki.step04.database.WikiDatabaseService

    @Before
    @Throws(InterruptedException::class)
    fun prepare(context: TestContext) {
        vertx = Vertx.vertx()

        val conf = JsonObject()  // <1>
                .put(WikiDatabaseVerticle.CONFIG_WIKIDB_JDBC_URL, "jdbc:hsqldb:mem:testdb;shutdown=true")
                .put(WikiDatabaseVerticle.CONFIG_WIKIDB_JDBC_MAX_POOL_SIZE, 4)

        vertx.deployVerticle(WikiDatabaseVerticle(), DeploymentOptions().setConfig(conf),
                context.asyncAssertSuccess {
                    service = createProxy(vertx, WikiDatabaseVerticle.CONFIG_WIKIDB_QUEUE)
                })
    }

    @After
    fun finish(context: TestContext) {
        vertx.close(context.asyncAssertSuccess())
    }

    @Test /*(timeout=5000)*/  // <8>
    fun async_behavior(context: TestContext) { // <1>
        val vertx = Vertx.vertx()  // <2>
        context.assertEquals("foo", "foo")  // <3>
        val a1 = context.async()   // <4>
        val a2 = context.async(3)  // <5>
        vertx.setTimer(100) { a1.complete() }  // <6>
        vertx.setPeriodic(100) { a2.countDown() }  // <7>
    }

    @Test
    fun crud_operations(context: TestContext) {
        val async = context.async()

        service.createPage("Test", "Some content", context.asyncAssertSuccess {

            service.fetchPage("Test", context.asyncAssertSuccess { json1 ->
                context.assertTrue(json1.getBoolean("found") ?: false)
                context.assertTrue(json1.containsKey("id"))
                context.assertEquals("Some content", json1.getString("rawContent"))

                service.savePage(json1.getInteger("id"), "Yo!", context.asyncAssertSuccess {

                    service.fetchAllPages(context.asyncAssertSuccess { array1 ->
                        context.assertEquals(1, array1.size())

                        service.fetchPage("Test", context.asyncAssertSuccess { json2 ->
                            context.assertEquals("Yo!", json2.getString("rawContent"))

                            service.deletePage(json1.getInteger("id"), Handler {

                                service.fetchAllPages(context.asyncAssertSuccess { array2 ->
                                    context.assertTrue(array2.isEmpty)
                                    async.complete()  // <1>
                                })
                            })
                        })
                    })
                })
            })
        })
        async.awaitSuccess(5000) // <2>
    }
}