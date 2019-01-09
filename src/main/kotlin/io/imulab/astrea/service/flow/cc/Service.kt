package io.imulab.astrea.service.flow.cc

import com.typesafe.config.Config
import io.grpc.stub.StreamObserver
import io.imulab.astrea.sdk.commons.flow.cc.ClientCredentialsFlowServiceGrpc
import io.imulab.astrea.sdk.commons.flow.cc.ClientCredentialsTokenRequest
import io.imulab.astrea.sdk.commons.flow.cc.ClientCredentialsTokenResponse
import io.imulab.astrea.sdk.commons.toFailure
import io.imulab.astrea.sdk.flow.cc.toAccessRequest
import io.imulab.astrea.sdk.flow.cc.toClientCredentialsTokenResponse
import io.imulab.astrea.sdk.oauth.error.OAuthException
import io.imulab.astrea.sdk.oauth.error.ServerError
import io.imulab.astrea.sdk.oauth.handler.AccessRequestHandler
import io.imulab.astrea.sdk.oauth.response.TokenEndpointResponse
import io.imulab.astrea.sdk.oauth.validation.OAuthRequestValidationChain
import io.vertx.core.AbstractVerticle
import io.vertx.core.Future
import io.vertx.core.http.HttpServerOptions
import io.vertx.ext.healthchecks.HealthCheckHandler
import io.vertx.ext.healthchecks.Status
import io.vertx.ext.web.Router
import io.vertx.grpc.VertxServerBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.coroutines.CoroutineContext

class GrpcVerticle(
    private val flowService: ClientCredentialsFlowService,
    private val appConfig: Config,
    private val healthCheckHandler: HealthCheckHandler
) : AbstractVerticle() {

    private val logger = LoggerFactory.getLogger(GrpcVerticle::class.java)

    override fun start(startFuture: Future<Void>?) {
        val server = VertxServerBuilder
            .forPort(vertx, appConfig.getInt("service.port"))
            .addService(flowService)
            .build()

        Runtime.getRuntime().addShutdownHook(thread(start = false) {
            logger.info("client credentials flow service gRPC verticle shutting down...")
            server.shutdown()
            server.awaitTermination(10, TimeUnit.SECONDS)
        })

        server.start { ar ->
            if (ar.failed()) {
                logger.error("client credentials flow service gRPC verticle failed to start.", ar.cause())
                startFuture?.fail(ar.cause())
            } else {
                startFuture?.complete()
                logger.info("client credentials flow service gRPC verticle started...")
            }
        }

        healthCheckHandler.register("client_credentials_flow_service_grpc_api") { h ->
            if (server.isTerminated)
                h.complete(Status.KO())
            else
                h.complete(Status.OK())
        }
    }
}

class HealthVerticle(
    private val healthCheckHandler: HealthCheckHandler,
    private val appConfig: Config
) : AbstractVerticle() {
    override fun start() {
        val router = Router.router(vertx)
        router.get("/health").handler(healthCheckHandler)
        vertx.createHttpServer(HttpServerOptions().apply {
            port = appConfig.getInt("service.healthPort")
        }).requestHandler(router).listen()
    }
}

class ClientCredentialsFlowService(
    private val concurrency: Int = 4,
    private val handlers: List<AccessRequestHandler>,
    private val exchangeValidation: OAuthRequestValidationChain
) : ClientCredentialsFlowServiceGrpc.ClientCredentialsFlowServiceImplBase(), CoroutineScope {

    override val coroutineContext: CoroutineContext
        get() = Executors.newFixedThreadPool(concurrency).asCoroutineDispatcher()

    override fun exchange(
        request: ClientCredentialsTokenRequest?,
        responseObserver: StreamObserver<ClientCredentialsTokenResponse>?
    ) {
        if (request == null || responseObserver == null)
            return

        val job = Job()
        val accessRequest = request.toAccessRequest()
        val accessResponse = TokenEndpointResponse()

        launch(job) {
            exchangeValidation.validate(accessRequest)

            handlers.forEach { h -> h.updateSession(accessRequest) }
            handlers.forEach { h -> h.handleAccessRequest(accessRequest, accessResponse) }
        }.invokeOnCompletion { t ->
            if (t != null) {
                job.cancel()
                val e = if (t is OAuthException) t else ServerError.wrapped(t)
                responseObserver.onNext(
                    ClientCredentialsTokenResponse.newBuilder()
                        .setSuccess(false)
                        .setFailure(e.toFailure())
                        .build()
                )
            } else {
                responseObserver.onNext(accessResponse.toClientCredentialsTokenResponse())
            }
            responseObserver.onCompleted()
        }
    }
}