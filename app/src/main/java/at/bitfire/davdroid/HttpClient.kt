/*
 * Copyright © Ricki Hirner (bitfire web engineering).
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Public License v3.0
 * which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/gpl.html
 */

package at.bitfire.davdroid

import android.content.Context
import android.os.Build
import android.security.KeyChain
import at.bitfire.cert4android.CustomCertManager
import at.bitfire.dav4jvm.BasicDigestAuthHandler
import at.bitfire.dav4jvm.UrlUtils
import at.bitfire.davdroid.log.Logger
import at.bitfire.davdroid.model.Credentials
import at.bitfire.davdroid.settings.AccountSettings
import at.bitfire.davdroid.settings.Settings
import at.bitfire.davdroid.settings.SettingsManager
import okhttp3.*
import okhttp3.brotli.BrotliInterceptor
import okhttp3.internal.tls.OkHostnameVerifier
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.security.KeyStore
import java.security.Principal
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.logging.Level
import javax.net.ssl.*

class HttpClient private constructor(
        val okHttpClient: OkHttpClient,
        private val certManager: CustomCertManager?
): AutoCloseable {

    companion object {
        /** max. size of disk cache (10 MB) */
        const val DISK_CACHE_MAX_SIZE: Long = 10*1024*1024

        /** [OkHttpClient] singleton to build all clients from */
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
                // Set timeouts. According to [AbstractThreadedSyncAdapter], when there is no network
                // traffic within a minute, a sync will be cancelled.
                .connectTimeout(15, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .pingInterval(45, TimeUnit.SECONDS)     // avoid cancellation because of missing traffic; only works for HTTP/2

                // keep TLS 1.0 and 1.1 for now; remove when major browsers have dropped it (probably 2020)
                .connectionSpecs(listOf(
                        ConnectionSpec.CLEARTEXT,
                        ConnectionSpec.COMPATIBLE_TLS
                ))

                // don't allow redirects by default, because it would break PROPFIND handling
                .followRedirects(false)

                // add User-Agent to every request
                .addInterceptor(UserAgentInterceptor)

                .build()
    }


    override fun close() {
        okHttpClient.cache?.close()
        certManager?.close()
    }

    class Builder(
            val context: Context? = null,
            accountSettings: AccountSettings? = null,
            val logger: java.util.logging.Logger? = Logger.log,
            val loggerLevel: HttpLoggingInterceptor.Level = HttpLoggingInterceptor.Level.BODY
    ) {
        private var certManager: CustomCertManager? = null
        private var certificateAlias: String? = null
        private var offerCompression: Boolean = false

        // default cookie store for non-persistent cookies (some services like Horde use cookies for session tracking)
        private var cookieStore: CookieJar? = MemoryCookieStore()

        private val orig = sharedClient.newBuilder()

        init {
            // add network logging, if requested
            if (logger != null && logger.isLoggable(Level.FINEST)) {
                val loggingInterceptor = HttpLoggingInterceptor { message -> logger.finest(message) }
                loggingInterceptor.level = loggerLevel
                orig.addNetworkInterceptor(loggingInterceptor)
            }

            if (context != null) {
                val settings = SettingsManager.getInstance(context)

                // custom proxy support
                try {
                    if (settings.getBoolean(Settings.OVERRIDE_PROXY)) {
                        val address = InetSocketAddress(
                                settings.getString(Settings.OVERRIDE_PROXY_HOST),
                                settings.getInt(Settings.OVERRIDE_PROXY_PORT)
                        )

                        val proxy = Proxy(Proxy.Type.HTTP, address)
                        orig.proxy(proxy)
                        Logger.log.log(Level.INFO, "Using proxy", proxy)
                    }
                } catch (e: Exception) {
                    Logger.log.log(Level.SEVERE, "Can't set proxy, ignoring", e)
                }

                // TODO don't instantiate CustomCertManager in .Builder (causes service leaks)
                customCertManager(CustomCertManager(context, true /*BuildConfig.customCertsUI*/,
                        !(settings.getBoolean(Settings.DISTRUST_SYSTEM_CERTIFICATES))))
            }

            // use account settings for authentication and cookies
            accountSettings?.let {
                addAuthentication(null, it.credentials())
            }
        }

        constructor(context: Context, host: String?, credentials: Credentials?): this(context) {
            if (credentials != null)
                addAuthentication(host, credentials)
        }

        fun addAuthentication(host: String?, credentials: Credentials, insecurePreemptive: Boolean = false): Builder {
            if (credentials.userName != null && credentials.password != null) {
                val authHandler = BasicDigestAuthHandler(UrlUtils.hostToDomain(host), credentials.userName, credentials.password, insecurePreemptive)
                orig.addNetworkInterceptor(authHandler)
                    .authenticator(authHandler)
            }
            if (credentials.certificateAlias != null)
                certificateAlias = credentials.certificateAlias
            return this
        }

        fun allowCompression(allow: Boolean): Builder {
            offerCompression = allow
            return this
        }

        fun cookieStore(store: CookieJar?): Builder {
            cookieStore = store
            return this
        }

        fun followRedirects(follow: Boolean): Builder {
            orig.followRedirects(follow)
            return this
        }

        fun customCertManager(manager: CustomCertManager) {
            certManager = manager
        }
        fun setForeground(foreground: Boolean): Builder {
            certManager?.appInForeground = foreground
            return this
        }

        fun withDiskCache(context: Context): Builder {
            for (dir in arrayOf(context.externalCacheDir, context.cacheDir).filterNotNull()) {
                if (dir.exists() && dir.canWrite()) {
                    val cacheDir = File(dir, "HttpClient")
                    cacheDir.mkdir()
                    Logger.log.fine("Using disk cache: $cacheDir")
                    orig.cache(Cache(cacheDir, DISK_CACHE_MAX_SIZE))
                    break
                }
            }
            return this
        }

        fun build(): HttpClient {
            cookieStore?.let {
                orig.cookieJar(it)
            }

            if (offerCompression)
                // offer Brotli and gzip compression
                orig.addInterceptor(BrotliInterceptor)

            val trustManager = certManager ?: run {
                val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
                factory.init(null as KeyStore?)
                factory.trustManagers.first() as X509TrustManager
            }

            val hostnameVerifier = certManager?.hostnameVerifier(OkHostnameVerifier)
                    ?: OkHostnameVerifier

            var keyManager: KeyManager? = null
            certificateAlias?.let { alias ->
                val context = requireNotNull(context)

                // get provider certificate and private key
                val certs = KeyChain.getCertificateChain(context, alias) ?: return@let
                val key = KeyChain.getPrivateKey(context, alias) ?: return@let
                logger?.fine("Using provider certificate $alias for authentication (chain length: ${certs.size})")

                // create KeyManager
                keyManager = object : X509ExtendedKeyManager() {
                    override fun getServerAliases(p0: String?, p1: Array<out Principal>?): Array<String>? = null
                    override fun chooseServerAlias(p0: String?, p1: Array<out Principal>?, p2: Socket?) = null

                    override fun getClientAliases(p0: String?, p1: Array<out Principal>?) =
                            arrayOf(alias)

                    override fun chooseClientAlias(p0: Array<out String>?, p1: Array<out Principal>?, p2: Socket?) =
                            alias

                    override fun getCertificateChain(forAlias: String?) =
                            certs.takeIf { forAlias == alias }

                    override fun getPrivateKey(forAlias: String?) =
                            key.takeIf { forAlias == alias }
                }

                // HTTP/2 doesn't support client certificates (yet)
                // see https://tools.ietf.org/html/draft-ietf-httpbis-http2-secondary-certs-04
                orig.protocols(listOf(Protocol.HTTP_1_1))
            }

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(
                    if (keyManager != null) arrayOf(keyManager) else null,
                    arrayOf(trustManager),
                    null)
            orig.sslSocketFactory(sslContext.socketFactory, trustManager)
            orig.hostnameVerifier(hostnameVerifier)

            return HttpClient(orig.build(), certManager)
        }

    }


    private object UserAgentInterceptor: Interceptor {
        // use Locale.ROOT because numbers may be encoded as non-ASCII characters in other locales
        private val userAgentDateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.ROOT)
        private val userAgentDate = userAgentDateFormat.format(Date(BuildConfig.buildTime))
        private val userAgent = "${BuildConfig.userAgent}/${BuildConfig.VERSION_NAME} ($userAgentDate; dav4jvm; " +
                "okhttp/${OkHttp.VERSION}) Android/${Build.VERSION.RELEASE}"

        init {
            Logger.log.info("Will set \"User-Agent: $userAgent\" for further requests")
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val locale = Locale.getDefault()
            val request = chain.request().newBuilder()
                    .header("User-Agent", userAgent)
                    .header("Accept-Language", "${locale.language}-${locale.country}, ${locale.language};q=0.7, *;q=0.5")
                    .build()
            return chain.proceed(request)
        }

    }

}
