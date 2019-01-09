package io.imulab.astrea.service.flow.cc

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import io.grpc.ManagedChannelBuilder
import io.imulab.astrea.sdk.discovery.RemoteDiscoveryService
import io.imulab.astrea.sdk.discovery.SampleDiscovery
import io.imulab.astrea.sdk.oauth.handler.OAuthClientCredentialsHandler
import io.imulab.astrea.sdk.oauth.handler.helper.AccessTokenHelper
import io.imulab.astrea.sdk.oauth.handler.helper.RefreshTokenHelper
import io.imulab.astrea.sdk.oauth.token.JwtSigningAlgorithm
import io.imulab.astrea.sdk.oauth.token.strategy.HmacSha2RefreshTokenStrategy
import io.imulab.astrea.sdk.oauth.token.strategy.JwtAccessTokenStrategy
import io.imulab.astrea.sdk.oauth.validation.OAuthGrantTypeValidator
import io.imulab.astrea.sdk.oauth.validation.OAuthRequestValidationChain
import io.imulab.astrea.sdk.oauth.validation.ScopeValidator
import io.imulab.astrea.sdk.oidc.discovery.Discovery
import io.vertx.core.Vertx
import io.vertx.ext.healthchecks.HealthCheckHandler
import kotlinx.coroutines.runBlocking
import org.kodein.di.Kodein
import org.kodein.di.generic.bind
import org.kodein.di.generic.eagerSingleton
import org.kodein.di.generic.instance
import org.kodein.di.generic.singleton
import org.slf4j.Logger
import org.slf4j.LoggerFactory

private val logger: Logger = LoggerFactory.getLogger("io.imulab.astrea.service.flow.cc.AppKt")

fun main(args: Array<String>) {
    val vertx = Vertx.vertx()
    val config = ConfigFactory.load()
    val components = App(vertx, config).bootstrap()

    val grpcVerticle by components.instance<GrpcVerticle>()
    vertx.deployVerticle(grpcVerticle) { ar ->
        if (ar.succeeded()) {
            logger.info("Client credentials flow service successfully deployed with id {}", ar.result())
        } else {
            logger.error("Client credentials flow service failed to deploy.", ar.cause())
        }
    }

    val healthVerticle by components.instance<HealthVerticle>()
    vertx.deployVerticle(healthVerticle) { ar ->
        if (ar.succeeded()) {
            logger.info("Client credentials flow service health information available.")
        } else {
            logger.error("Client credentials flow service health information unavailable.", ar.cause())
        }
    }
}

@Suppress("MemberVisibilityCanBePrivate")
open class App(private val vertx: Vertx, private val config: Config) {

    open fun bootstrap(): Kodein {
        return Kodein {
            importOnce(discovery)
            importOnce(app)

            bind<GrpcVerticle>() with singleton {
                GrpcVerticle(
                    flowService = instance(),
                    appConfig = config,
                    healthCheckHandler = instance()
                )
            }

            bind<HealthVerticle>() with singleton {
                HealthVerticle(
                    appConfig = config,
                    healthCheckHandler = instance()
                )
            }
        }
    }

    val app = Kodein.Module("app") {
        bind<HealthCheckHandler>() with singleton { HealthCheckHandler.create(vertx) }

        bind<ServiceContext>() with singleton {
            ServiceContext(instance(), config)
        }

        bind<OAuthClientCredentialsHandler>() with singleton {
            OAuthClientCredentialsHandler(
                accessTokenHelper = AccessTokenHelper(
                    oauthContext = instance(),
                    accessTokenStrategy = JwtAccessTokenStrategy(
                        oauthContext = instance(),
                        signingAlgorithm = JwtSigningAlgorithm.RS256,
                        serverJwks = instance<ServiceContext>().masterJsonWebKeySet
                    ),
                    accessTokenRepository = NoOpAccessTokenRepository
                ),
                refreshTokenHelper = RefreshTokenHelper(
                    refreshTokenStrategy = HmacSha2RefreshTokenStrategy(
                        key = instance<ServiceContext>().refreshTokenKey,
                        signingAlgorithm = JwtSigningAlgorithm.HS256
                    ),
                    refreshTokenRepository = PublishingRefreshTokenRepository(vertx)
                )
            )
        }

        bind<ClientCredentialsFlowService>() with singleton {
            ClientCredentialsFlowService(
                handlers = listOf(
                    instance<OAuthClientCredentialsHandler>()
                ),
                exchangeValidation = OAuthRequestValidationChain(listOf(
                    ScopeValidator,
                    OAuthGrantTypeValidator
                ))
            )
        }
    }

    val discovery = Kodein.Module("discovery") {
        bind<Discovery>() with eagerSingleton {
            if (config.getBoolean("discovery.useSample")) {
                logger.info("Using default discovery instead of remote.")
                SampleDiscovery.default()
            } else {
                runBlocking {
                    RemoteDiscoveryService(
                        ManagedChannelBuilder.forAddress(
                            config.getString("discovery.host"),
                            config.getInt("discovery.port")
                        ).enableRetry().maxRetryAttempts(10).usePlaintext().build()
                    ).getDiscovery()
                }.also { logger.info("Acquired discovery from remote.") }
            }
        }
    }
}