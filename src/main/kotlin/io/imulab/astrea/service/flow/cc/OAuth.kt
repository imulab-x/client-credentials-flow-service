package io.imulab.astrea.service.flow.cc

import com.typesafe.config.Config
import io.imulab.astrea.sdk.commons.doNotCall
import io.imulab.astrea.sdk.event.RefreshTokenEvents
import io.imulab.astrea.sdk.oauth.assertType
import io.imulab.astrea.sdk.oauth.request.OAuthRequest
import io.imulab.astrea.sdk.oauth.reserved.AuthenticationMethod
import io.imulab.astrea.sdk.oauth.token.storage.AccessTokenRepository
import io.imulab.astrea.sdk.oauth.token.storage.RefreshTokenRepository
import io.imulab.astrea.sdk.oidc.discovery.Discovery
import io.imulab.astrea.sdk.oidc.discovery.OidcContext
import io.vertx.core.Vertx
import org.jose4j.jwk.JsonWebKeySet
import org.jose4j.keys.AesKey
import java.time.Duration
import java.util.*

class ServiceContext(discovery: Discovery, config: Config) : OidcContext, Discovery by discovery {
    override val idTokenLifespan: Duration = Duration.ZERO
    override val masterJsonWebKeySet: JsonWebKeySet = JsonWebKeySet(config.getString("service.jwks"))
    override val nonceEntropy: Int = 0
    override val issuerUrl: String = discovery.issuer
    override val authorizeEndpointUrl: String = discovery.authorizationEndpoint
    override val tokenEndpointUrl: String = discovery.tokenEndpoint
    override val defaultTokenEndpointAuthenticationMethod: String = AuthenticationMethod.clientSecretBasic
    override val authorizeCodeLifespan: Duration = Duration.ZERO
    override val accessTokenLifespan: Duration = config.getDuration("service.accessTokenLifespan")
    override val refreshTokenLifespan: Duration = config.getDuration("service.refreshTokenLifespan")
    override val stateEntropy: Int = 0

    val refreshTokenKey = AesKey(Base64.getDecoder().decode(config.getString("service.refreshTokenKey")))

    override fun validate() {
        super<OidcContext>.validate()
    }
}

object NoOpAccessTokenRepository : AccessTokenRepository {
    override suspend fun createAccessTokenSession(token: String, request: OAuthRequest) {}
    override suspend fun getAccessTokenSession(token: String): OAuthRequest = doNotCall()
    override suspend fun deleteAccessTokenSession(token: String) {}
    override suspend fun deleteAccessTokenAssociatedWithRequest(requestId: String) {}
}

class PublishingRefreshTokenRepository(private val vertx: Vertx) : RefreshTokenRepository {
    override suspend fun createRefreshTokenSession(token: String, request: OAuthRequest) {
        vertx.eventBus().publish(
            RefreshTokenEvents.refreshTokenCreatedEvent,
            RefreshTokenEvents.refreshTokenCreated(token, request.assertType())
        )
    }

    override suspend fun deleteRefreshTokenAssociatedWithRequest(requestId: String) {}
    override suspend fun deleteRefreshTokenSession(token: String) {}
    override suspend fun getRefreshTokenSession(token: String): OAuthRequest = doNotCall()
}