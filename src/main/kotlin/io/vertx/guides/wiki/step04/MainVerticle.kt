package io.vertx.guides.wiki.step04


import io.vertx.core.AbstractVerticle
import io.vertx.core.DeploymentOptions
import io.vertx.core.Future
import io.vertx.guides.wiki.step04.http.HttpServerVerticle
import io.vertx.guides.wiki.step05.database.WikiDatabaseVerticle

/**
 * @author [Julien Ponge](https://julien.ponge.org/)
 */
class MainVerticle : AbstractVerticle() {

    @Throws(Exception::class)
    override fun start(startFuture: Future<Void>) {

        val dbVerticleDeployment = Future.future<String>()
        vertx.deployVerticle(WikiDatabaseVerticle(), dbVerticleDeployment.completer())

        dbVerticleDeployment.compose {

            val httpVerticleDeployment = Future.future<String>()
            vertx.deployVerticle(
                    HttpServerVerticle::class.java.name,
                    DeploymentOptions().setInstances(2),
                    httpVerticleDeployment.completer())

            httpVerticleDeployment

        }.setHandler { ar ->
                    if (ar.succeeded()) {
                        startFuture.complete()
                    } else {
                        startFuture.fail(ar.cause())
                    }
                }
    }
}