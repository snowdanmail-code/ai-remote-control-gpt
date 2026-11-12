package com.example.airemote

import android.content.ActivityNotFoundException
import android.net.ConnectivityManager
import androidx.browser.customtabs.CustomTabsIntent
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URL
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * ChatGPT OAuth / direct Responses transport for a *local* Android OSS client.
 * Secrets are never put into logs, source code, URLs (except OpenAI's id_token_hint
 * during explicit reauth, which this first iteration intentionally omits), or reports.
 * Cloud output is advice and a connection test; it has no gesture dispatch authority.
 */
object ChatGptCloudBridge {
    private const val PREFS = "chatgpt_sign_in_private"
    private const val ENCRYPTED = "sealed_credential"
    private const val REGISTRATION = "sealed_registration"
    @Volatile private var storageFault = false
    private const val HOST = "stable_host_id"
    private const val REGISTERED_CLIENT = "oauth_registration_client_id"
    private const val KEY_ALIAS = "ai_remote_chatgpt_session_v1"
    private val operations = ChatGptSessionGate()
    private val networkMutex = Any() // rotating refresh tokens must never race
    private val storageMutex = Any() // UI state reads must not wait for HTTPS
    
    @Volatile private var appContext: Context? = null
    @Volatile private var activeServer: ServerSocket? = null

    @Volatile private var lastNetworkDiagnostic = "Сеть OpenAI из приложения ещё не проверялась"
    @Volatile private var lastLoginDiagnostic = "Вход пока не выполнялся"
    @Volatile private var lastCloudDiagnostic = "Проверка облачной модели пока не запускалась"

    internal class CloudHttpError(val httpCode: Int, val publicCode: String? = null, val requestId: String? = null, val shape: String = "UNKNOWN") : IllegalStateException()
    internal class CloudProblemError(val problem: ChatGptCloudProblem, val publicCode: String? = null) : IllegalStateException()

    fun statusDetail(context: Context): String =
        "${connectionLabel(context)}\nАвторизация: $lastLoginDiagnostic\nМодель: $lastCloudDiagnostic\nСеть: $lastNetworkDiagnostic"

    internal fun describe(ex: Exception, step: ChatGptCloudStep): String {
        val code = when (ex) {
            is CloudProblemError -> ex.problem
            is ChatGptIdTokenVerifier.Rejection -> ex.problem
            is java.security.GeneralSecurityException, is SecurityException -> if (step == ChatGptCloudStep.STORE) ChatGptCloudProblem.BAD_STORAGE else ChatGptCloudProblem.BAD_IDENTITY
            else -> ChatGptTransportDiagnostics.problem(ex)
        }
        val mapped = if (code != ChatGptCloudProblem.OTHER || ex is CloudHttpError) code else when (step) {
            ChatGptCloudStep.CALLBACK -> ChatGptCloudProblem.BAD_CALLBACK
            ChatGptCloudStep.TOKEN -> ChatGptCloudProblem.BAD_TOKEN_RESPONSE
            ChatGptCloudStep.IDENTITY -> ChatGptCloudProblem.BAD_IDENTITY
            ChatGptCloudStep.STORE -> ChatGptCloudProblem.BAD_STORAGE
            ChatGptCloudStep.REFRESH -> ChatGptCloudProblem.BAD_REFRESH_RESPONSE
            else -> ChatGptCloudProblem.OTHER
        }
        val base = ChatGptCloudDiagnostics.description(step, mapped, (ex as? CloudHttpError)?.httpCode)
        return if (ex is CloudHttpError) base + " Форма: ${ex.shape}." +
            (ex.publicCode?.let { " Код: $it." } ?: "") + (ex.requestId?.let { " Request ID: $it." } ?: "") else base + ((ex as? CloudProblemError)?.publicCode?.let { " Код: $it." } ?: "")
    }


    fun isBusy(): Boolean = operations.isBusy()
    fun isAuthorizing(): Boolean = operations.isSigningIn()

    fun connected(context: Context): Boolean = try { readRecord(context)?.let { !it.optBoolean("refresh_pending") } ?: false } catch (_: Exception) { false }
    fun connectionLabel(context: Context): String {
        if (operations.isSigningIn()) return "ChatGPT: вход выполняется; дождитесь AUTH_OK в приложении"
        val record = try { readRecord(context) } catch (ex: Exception) { return describe(ex, ChatGptCloudStep.STORE) }
            ?: return "ChatGPT: подтверждённого сеанса нет"
        if (record.optBoolean("refresh_pending")) return "ChatGPT: AUTH_REFRESH_REAUTH_REQUIRED — результат обновления не подтверждён; нужен новый вход"
        val scope = record.optString("scope", "")
        val enabled = ChatGptAuthProtocol.scopeAllowsInference(scope)
        val name = record.optString("email", "учётная запись подтверждена")
        return "ChatGPT: $name · облачный ИИ ${if (enabled) "разрешён" else "без разрешения на запросы"}"
    }

    private fun hostId(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val previous = prefs.getString(HOST, null)
        if (previous != null) return previous
        val newId = "urn:uuid:${UUID.randomUUID()}"
        check(prefs.edit().putString(HOST, newId).commit())
        return newId
    }

    private fun registeredClientId(context: Context): String? =
        readProtected(context, REGISTRATION)?.getString("client_id") ?: context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(REGISTERED_CLIENT, null)
            ?.takeIf { it.length in 8..511 && it != ChatGptAuthProtocol.INITIAL_CLIENT }

    private fun rememberRegisteredClientId(context: Context, id: String): Boolean {
        require(id.length in 8..511 && id != ChatGptAuthProtocol.INITIAL_CLIENT)
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(REGISTERED_CLIENT, id).commit()
    }

    private fun loginDiagnostic(generation: Int, text: String) = synchronized(storageMutex) {
        if (generation == operations.generation()) lastLoginDiagnostic = text
    }
    private fun cloudDiagnostic(generation: Int, text: String) = synchronized(storageMutex) {
        if (generation == operations.generation()) lastCloudDiagnostic = text
    }

    /** Browser based, PKCE + 127.0.0.1 listener, no embedded WebView. */
    fun startSignIn(activity: Activity, requestPlanConsent: Boolean = false, report: (String) -> Unit) {
        appContext = activity.applicationContext
        if (!operations.tryBeginSignIn()) { report("AUTH_BUSY: дождитесь окончания текущей операции"); return }
        val generation = operations.generation()
        Thread({
            var verifyAfterSignIn = false
            var phase = ChatGptCloudStep.PREFLIGHT
            loginDiagnostic(generation, "AUTH_PREFLIGHT: проверяется сеть перед входом")
            try {
                val authProbe = probeEndpoint("auth.openai.com", "/.well-known/jwks.json")
                val apiProbe = probeEndpoint("api.openai.com", "/v1/models")
                val readiness = ChatGptLoginPreflight.assess(authProbe, apiProbe)
                lastNetworkDiagnostic = "AUTH: $authProbe; API: $apiProbe"
                if (!readiness.canOpenBrowser) throw CloudProblemError(readiness.problem ?: ChatGptCloudProblem.TRANSPORT_FAILURE)
                check(generation == operations.generation()) { "Sign-in cancelled" }
                phase = ChatGptCloudStep.BROWSER
                loginDiagnostic(generation, "AUTH_BROWSER: сеть доступна, ожидается вход через браузер")
                ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
                    synchronized(storageMutex) {
                        check(generation == operations.generation()) { "Sign-in cancelled" }
                        activeServer = server
                    }
                    val redirect = "http://127.0.0.1:${server.localPort}${ChatGptAuthProtocol.CALLBACK_PATH}"
                    val state = ChatGptAuthProtocol.randomUrlSafe()
                    val nonce = ChatGptAuthProtocol.randomUrlSafe()
                    val verifier = ChatGptAuthProtocol.randomUrlSafe(48)
                    val old = readRecord(activity)
                    val identity = old ?: readProtected(activity, REGISTRATION)
                    val issued = old?.optString("client_id")?.takeUnless { it.isNullOrBlank() }
                        ?: registeredClientId(activity)
                        ?: activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("issued_client_id", null)
                    val url = ChatGptAuthProtocol.authorizationUrl(hostId(activity), redirect, state, nonce, verifier, issued, requestPlanConsent || (old != null && !ChatGptAuthProtocol.scopeAllowsInference(old.optString("scope"))))
                    activity.runOnUiThread {
                        if (generation != operations.generation()) return@runOnUiThread
                        try {
                            launchSignInBrowser(activity, url)
                            report("В браузере ChatGPT подтвердите вход и использование подписки. Ожидаю возврата…")
                        } catch (_: Exception) { report("Не удалось открыть системный браузер") ; try { server.close() } catch (_:Exception) {} }
                    }
                    phase = ChatGptCloudStep.CALLBACK
                    loginDiagnostic(generation, "AUTH_CALLBACK: ожидается ответ браузера")
                    server.soTimeout = 600_000
                    val path = server.accept().use { socket ->
                        socket.soTimeout = 4500
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII))
                        val first = reader.readLine() ?: error("Empty callback")
                        require(first.startsWith("GET /auth/callback?") && first.endsWith(" HTTP/1.1")) { "Invalid callback path" }
                        require(first.length <= 8192)
                        // Bounded header handling. Socket was bound to 127.0.0.1 only.
                        var total = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            total += line.length
                            require(total < 16384) { "Headers too long" }
                            if (line.isEmpty()) break
                        }
                        val callbackPath = first.substring(4, first.length - 9)
                        val html = "<html><meta charset='UTF-8'><body><h3>Ответ OpenAI получен.</h3>Это ещё не подтверждение входа. Вернитесь в AI Remote Control GPT и дождитесь результата обмена токенов.</body></html>"
                        val bytes = html.toByteArray(Charsets.UTF_8)
                        val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nCache-Control: no-store\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        socket.getOutputStream().write(headers.toByteArray(Charsets.US_ASCII)); socket.getOutputStream().write(bytes)
                        callbackPath
                    }
                    val returned = ChatGptAuthProtocol.validateCallback(path, redirect, state, issued)
                    synchronized(storageMutex) {
                        check(generation == operations.generation()) { "Sign-in cancelled" }
                        if (!rememberRegisteredClientId(activity, returned.issuedClientId)) throw CloudProblemError(ChatGptCloudProblem.BAD_STORAGE)
                    }
                    phase = ChatGptCloudStep.TOKEN
                    loginDiagnostic(generation, "AUTH_TOKEN: выполняется обмен кода")
                    val exchange = postForm(ChatGptAuthProtocol.TOKEN, listOf(
                        "grant_type" to "authorization_code", "client_id" to returned.issuedClientId,
                        "code" to returned.code, "code_verifier" to verifier,
                        "redirect_uri" to redirect, "resource" to ChatGptAuthProtocol.RESOURCE
                    ))
                    val idToken = exchange.getString("id_token")
                    require(exchange.optString("token_type").equals("Bearer", ignoreCase = true))
                    require(exchange.getString("access_token").isNotBlank())
                    val expiresAt = ChatGptTokenResponsePolicy.expiresAt(System.currentTimeMillis(), exchange.getLong("expires_in"), exchange.getString("token_type"))
                    phase = ChatGptCloudStep.IDENTITY
                    loginDiagnostic(generation, "AUTH_IDENTITY: проверка подписи OpenAI")
                    val claims = verifyIdToken(idToken, returned.issuedClientId, nonce)
                    if (identity != null) {
                        require(ChatGptTokenResponsePolicy.sameIdentity(returned.issuedClientId, claims.getString("sub"), identity.getString("client_id"), identity.getString("sub"))) { "Account identity mismatch" }
                    }
                    val record = JSONObject().apply {
                        put("client_id", returned.issuedClientId)
                        put("sub", claims.getString("sub"))
                        put("email", claims.optString("email", ""))
                        put("id_token", idToken)
                        put("access_token", exchange.getString("access_token"))
                        put("refresh_token", exchange.optString("refresh_token", ""))
                        put("scope", exchange.optString("scope", ""))
                        if (exchange.has("earliest_refresh_at")) put("earliest_refresh_at", exchange.get("earliest_refresh_at"))
                        put("expires_at_ms", expiresAt)
                    }
                    phase = ChatGptCloudStep.STORE
                    loginDiagnostic(generation, "AUTH_STORE: защищённое сохранение сеанса")
                    synchronized(storageMutex) {
                        check(generation == operations.generation()) { "Sign-in cancelled" }
                        require(saveRecord(activity, record)) { "Could not securely save credentials" }
                        require(activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("issued_client_id", returned.issuedClientId).commit())
                    }
                    val usable = ChatGptAuthProtocol.scopeAllowsInference(record.optString("scope"))
                    verifyAfterSignIn = usable
                    loginDiagnostic(generation, if (usable) "AUTH_OK: сеанс сохранён; разрешение модели выдано" else "AUTH_SCOPE_MISSING: сеанс сохранён без разрешения модели")
                    activity.runOnUiThread { if (generation == operations.generation()) report(if (usable)
                        "AUTH_OK: подтверждённый сеанс сохранён. Проверяю завершённый ответ облачной модели…"
                        else "ChatGPT: личность подтверждена, но разрешение на использование модели не предоставлено.") }
                }
            } catch (ex: Exception) {
                if (generation == operations.generation()) {
                    val message = describe(ex, phase)
                    loginDiagnostic(generation, message)
                    activity.runOnUiThread { if (generation == operations.generation()) report("Вход не завершён: $message") }
                }
            } finally {
                activeServer = null
                operations.finishSignIn()
                if (verifyAfterSignIn && generation == operations.generation()) {
                    testInference(activity.applicationContext) { result ->
                        activity.runOnUiThread { if (generation == operations.generation()) report(result) }
                    }
                }
            }
        }, "chatgpt-login").start()
    }

    /** Browser owns the page; this internal seam also permits isolated loopback instrumentation. */
    internal fun launchSignInBrowser(activity: Activity, url: String) {
        try {
            CustomTabsIntent.Builder().setShowTitle(true).build()
                .launchUrl(activity, Uri.parse(url))
        } catch (_: ActivityNotFoundException) {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE))
        }
    }

    fun cancelSignIn() {
        if (!operations.isSigningIn()) return
        synchronized(storageMutex) { operations.invalidate() }
        lastLoginDiagnostic = "AUTH_CANCELLED: вход отменён"
        try { activeServer?.close() } catch (_: Exception) {}
    }

    /** Safe connectivity probe: no OAuth tokens, authorization codes, passwords or personal data.
     *  The probe checks the SAME Android network stack as the token exchange; Chrome can differ.
     *  A 401/403 response is transport-reachable, not authorization success.
     */
    fun diagnoseOpenAiNetwork(report: (String) -> Unit) {
        Thread({
            lastNetworkDiagnostic = "Проверяю DNS и HTTPS из Android-приложения…"
            val auth = probeEndpoint("auth.openai.com", "/.well-known/jwks.json")
            val api = probeEndpoint("api.openai.com", "/v1/models")
            val verdict = "AUTH: $auth\nAPI: $api"
            lastNetworkDiagnostic = verdict
            report("Проверка сети OpenAI (без входа):\n$verdict\nHTTP 401/403 означает доступность сервера, а не успешную авторизацию.")
        }, "openai-network-diagnostic").start()
    }

    private fun probeEndpoint(host: String, path: String): String {
        // Check the REAL HTTPS request, not a separate InetAddress lookup.
        // On Android the independent DNS lookup can fail even if the HTTPS
        // transport to exactly this host succeeds. A reachable HTTP 401/403
        // only proves transport availability, never a valid OAuth session.
        require(host == "auth.openai.com" || host == "api.openai.com")
        require(path == "/.well-known/jwks.json" || path == "/v1/models")
        return try {
            val conn = request("https://$host$path", "GET")
            conn.connectTimeout = 5_000
            conn.readTimeout = 7_000
            try {
                val code = conn.responseCode
                if (code in 100..599) "DNS_OK HTTPS_REACHABLE HTTP_$code"
                else "HTTPS_NO_RESPONSE"
            } finally { conn.disconnect() }
        } catch (e: Exception) {
            val kind = ChatGptTransportDiagnostics.problem(e)
            if (kind == ChatGptCloudProblem.DNS_FAILURE) {
                try {
                    // Public unauthenticated GET only: no OAuth codes, tokens or
                    // private identifiers ever sent to a DNS provider.
                    val reply = ChatGptDohTransport.call("https://$host$path", "GET")
                    "DNS_OK HTTPS_REACHABLE HTTP_${reply.status} SECURE_DNS_FALLBACK"
                } catch (_: Exception) {
                    "DNS_FAILURE SECURE_DNS_FALLBACK_UNAVAILABLE"
                }
            } else "HTTPS_${kind.name}"
        }
    }

    /** HTTPS only; connect before body, fixed streaming, no retry after sending. */
    internal fun request(url: String, method: String, bearer: String? = null,
                        contentType: String? = null, body: ByteArray? = null,
                        connectionFactory: ((URL, Boolean) -> HttpURLConnection)? = null,
                        traceSink: ((String) -> Unit)? = null,
                        connectTimeoutMs: Int = 12_000, readTimeoutMs: Int = 35_000): HttpURLConnection {
        val uri = URL(url)
        require(uri.protocol == "https" && (uri.host == "auth.openai.com" || uri.host == "api.openai.com"))
        require(uri.userInfo == null && uri.ref == null && uri.query == null && uri.port in listOf(-1,443))
        require(method == "GET" || method == "POST")
        val trace = ChatGptTransportTrace()
        val emit: (Throwable?) -> Unit = { ex ->
            val line = trace.snapshot(ex)
            traceSink?.invoke(line) ?: recordTransport(line)
        }
        if (body != null) require(body.size < 128_000 && method == "POST")
        var openCount = 0
        val newConnection = {
            // On DNS failure BEFORE any grant bytes are sent, the second attempt
            // uses the Android active Network resolver rather than repeating the
            // identical system-DNS lookup. TLS hostname verification is unchanged.
            val useActiveNetwork = (body != null && uri.host == "auth.openai.com" && openCount++ > 0)
            trace.phase = ChatGptTransportTrace.Phase.CREATE
            trace.attempt = openCount.coerceAtLeast(1)
            val connectivity = appContext?.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val active = connectivity?.activeNetwork
            val capabilities = active?.let { connectivity.getNetworkCapabilities(it) }
            trace.network = when {
                capabilities == null -> ChatGptTransportTrace.Network.UNKNOWN
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) -> ChatGptTransportTrace.Network.VPN
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> ChatGptTransportTrace.Network.WIFI
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> ChatGptTransportTrace.Network.CELLULAR
                capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> ChatGptTransportTrace.Network.ETHERNET
                else -> ChatGptTransportTrace.Network.OTHER
            }
            trace.proxy = connectivity?.defaultProxy != null || !System.getProperty("https.proxyHost").isNullOrBlank()
            val activeConnection: HttpURLConnection = if (connectionFactory != null) connectionFactory(uri, useActiveNetwork) else if (useActiveNetwork) {
                (active?.openConnection(uri) ?: uri.openConnection()) as HttpURLConnection
            } else uri.openConnection() as HttpURLConnection
            activeConnection.apply {
                instanceFollowRedirects = false
                requestMethod = method
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", if (url.endsWith("/v1/responses")) "text/event-stream" else "application/json")
                if (bearer != null) setRequestProperty("Authorization", "Bearer $bearer")
                if (contentType != null) setRequestProperty("Content-Type", contentType)
                if (body != null) {
                    doOutput = true
                    // Never let HttpURLConnection buffer/replay a sensitive grant body.
                    setFixedLengthStreamingMode(body.size)
                }
            }
        }
        val conn = try { if (body != null && uri.host == "auth.openai.com") {
            ChatGptTokenConnect.openBeforeBody(
                create = newConnection,
                establish = { trace.phase = ChatGptTransportTrace.Phase.CONNECT_TLS; it.connect(); emit(null) },
                discard = { it.disconnect() },
                onFailure = { emit(it) }
            )
        } else newConnection() } catch (ex: Exception) { emit(ex); throw ex }
        transportTraces[conn] = trace
        // The body is written ONCE. No retry of write/response/HTTP error.
        if (body != null) {
            try {
                // Obtaining the stream can itself transmit bytes: disallow replay from this point.
                trace.phase = ChatGptTransportTrace.Phase.BODY_OPEN
                trace.bodyStarted = true
                conn.outputStream.use { trace.phase = ChatGptTransportTrace.Phase.BODY_WRITE; it.write(body) }
                emit(null)
            } catch (ex: Exception) {
                emit(ex)
                conn.disconnect()
                // postForm may use DoH only for a pre-body UnknownHostException.
                // Once outputStream was attempted, preserve the DNS cause but
                // prevent the outer fallback from replaying this one-use grant.
                if (ex is java.net.UnknownHostException)
                    throw java.io.IOException("OAuth body transmission started; replay forbidden", ex)
                throw ex
            }
        }
        return conn
    }

    private val transportTraces = java.util.Collections.synchronizedMap(java.util.WeakHashMap<HttpURLConnection, ChatGptTransportTrace>())
    private val traceMutex = Any()
    private fun recordTransport(line: String) {
        lastNetworkDiagnostic = line
        val context = appContext ?: return
        try { synchronized(traceMutex) {
            val file = java.io.File(context.filesDir, "oauth_transport.log")
            if (file.length() > 65536) file.writeText("")
            file.appendText(line + "\n")
        } } catch (_: Exception) { /* Never replace the original transport failure. */ }
    }
    private fun tracePhase(c: HttpURLConnection, phase: ChatGptTransportTrace.Phase, error: Throwable? = null) {
        transportTraces[c]?.let { it.phase = phase; recordTransport(it.snapshot(error)) }
    }

    @Volatile private var lastModelsUsedDoh = false

    private fun dohJson(url: String, method: String, bearer: String? = null,
                        contentType: String? = null, body: ByteArray? = null): JSONObject {
        val result = ChatGptDohTransport.call(url, method, bearer, contentType, body)
        if (result.status !in 200..299) {
            throw classifiedHttpError(result.status, result.body, result.requestId)
        }
        lastNetworkDiagnostic = "SECURE_DNS_FALLBACK: защищённый DNS использован для ${URL(url).host}"
        return JSONObject(result.body)
    }

    private fun getJson(url: String, bearer: String? = null): JSONObject {
        val c = request(url, "GET", bearer)
        try {
            return JSONObject(readBody(c))
        } catch (ex: java.net.UnknownHostException) {
            // GET is credential-safe to repeat. For model listing, select the
            // same working DNS path BEFORE attempting inference POST.
            val json = dohJson(url, "GET", bearer)
            if (url == "https://api.openai.com/v1/models") lastModelsUsedDoh = true
            return json
        } finally { c.disconnect() }
    }

    private fun postForm(url: String, values: List<Pair<String, String>>): JSONObject {
        val body = ChatGptAuthProtocol.form(values).toByteArray(Charsets.UTF_8)
        // For auth.openai.com, request() completes TLS handshake before writing
        // anything. UnknownHostException HERE means ZERO grant bytes were sent.
        // DoH transport then submits the grant ONCE, never replaying a POST.
        val c = try {
            request(url, "POST", contentType = "application/x-www-form-urlencoded", body = body)
        } catch (ex: java.net.UnknownHostException) {
            return dohJson(url, "POST", contentType = "application/x-www-form-urlencoded", body = body)
        }
        try { return JSONObject(readBody(c)) } finally { c.disconnect() }
    }

    // Server messages/bodies may contain secrets. Only known machine codes are public.
    internal fun classifiedHttpError(status: Int, body: String, requestId: String?): CloudHttpError {
        val known = setOf("invalid_grant", "invalid_client", "invalid_request", "access_denied",
            "invalid_refresh_token", "token_expired", "refresh_token_expired", "refresh_token_invalidated",
            "refresh_token_reused", "subscription_sharing_user_not_eligible", "subscription_sharing_usage_limit_exceeded",
            "subscription_sharing_usage_unavailable", "subscription_sharing_unsupported_capability",
            "subscription_sharing_route_not_supported", "subscription_sharing_invalid_user",
            "subscription_sharing_user_unavailable", "chatpass_v2_scope_not_authorized",
            "chatpass_v2_invalid_authorization_context", "invalid_api_key", "model_not_found", "rate_limit_exceeded")
        val json = try { JSONObject(body) } catch (_: Exception) { null }
        val raw = json?.optJSONObject("error")?.optString("code") ?: (json?.opt("error") as? String)
        val code = raw?.takeIf { it in known }
        val id = requestId?.takeIf { it.matches(Regex("req_[A-Za-z0-9]{5,100}")) }
        val shape = when { json?.optJSONObject("error") != null -> "ERROR_OBJECT"
            json?.opt("error") is String -> "OAUTH_ERROR"
            json?.has("detail") == true -> "DETAIL"
            json != null -> "JSON_OTHER"
            else -> "NON_JSON" }
        return CloudHttpError(status, code, id, shape)
    }

    private fun httpError(c: HttpURLConnection): CloudHttpError {
        val body = try { c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            val chars = CharArray(8192)
            val out = StringBuilder()
            while (out.length < chars.size) {
                val n = reader.read(chars, 0, chars.size - out.length)
                if (n < 0) break
                out.append(chars, 0, n)
            }
            out.toString()
        } ?: "" } catch (_: Exception) { "" }
        return classifiedHttpError(c.responseCode, body, c.getHeaderField("x-request-id"))
    }

    private fun streamError(event: JSONObject): Exception {
        val error = event.optJSONObject("response")?.optJSONObject("error") ?: event.optJSONObject("error")
        val body = if (error != null) JSONObject().put("error", error).toString() else event.toString()
        val parsed = classifiedHttpError(200, body, null)
        // A stream can fail after HTTP 200. Do not label HTTP 200 as an HTTP failure.
        return CloudProblemError(ChatGptCloudProblem.INFERENCE_FAILED, parsed.publicCode)
    }

    internal fun chooseModel(response: JSONObject): String {
        val models = response.optJSONArray("models") ?: throw CloudProblemError(ChatGptCloudProblem.NO_MODELS_ARRAY)
        for (i in 0 until models.length()) {
            val model = models.optJSONObject(i) ?: continue
            val slug = model.optString("slug")
            if (model.optString("visibility") == "list" && slug.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}"))) return slug
        }
        throw CloudProblemError(ChatGptCloudProblem.NO_VISIBLE_MODEL)
    }

    private fun readBody(c: HttpURLConnection): String {
        tracePhase(c, ChatGptTransportTrace.Phase.RESPONSE_HEADERS)
        val status = try { c.responseCode } catch (ex: Exception) { tracePhase(c, ChatGptTransportTrace.Phase.RESPONSE_HEADERS, ex); throw ex }
        if (status !in 200..299) throw httpError(c)
        tracePhase(c, ChatGptTransportTrace.Phase.RESPONSE_READ)
        try { c.inputStream.use { input ->
            val b = ByteArray(16384)
            val out = java.io.ByteArrayOutputStream()
            while (true) {
                val n = input.read(b)
                if (n < 0) break
                require(out.size() + n < 1_000_000) { "Response too large" }
                out.write(b, 0, n)
            }
            return out.toString("UTF-8")
        } } catch (ex: Exception) { tracePhase(c, ChatGptTransportTrace.Phase.RESPONSE_READ, ex); throw ex }
    }

    /** RS256 signature + issuer, audience, expiry, nonce (JWKS retrieved from OpenAI). */
    private fun verifyIdToken(jwt: String, expectedClient: String, nonce: String): JSONObject =
        ChatGptIdTokenVerifier.verifyWithKeys(jwt, expectedClient, nonce) {
            // Network failures are deliberately not wrapped as an identity rejection.
            getJson(ChatGptAuthProtocol.JWKS).getJSONArray("keys")
        }

    private fun cipherKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = ks.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
        return generator.generateKey()
    }

    private fun sealed(record: JSONObject): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, cipherKey())
        val bytes = cipher.iv + cipher.doFinal(record.toString().toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }
    private fun registration(record: JSONObject): JSONObject = JSONObject()
        .put("client_id", record.getString("client_id")).put("sub", record.getString("sub"))
    private fun saveRecord(context: Context, record: JSONObject): Boolean = synchronized(storageMutex) {
        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(ENCRYPTED, sealed(record)).putString(REGISTRATION, sealed(registration(record))).commit()
        if (!ok) storageFault = true
        ok
    }
    private fun readRecord(context: Context): JSONObject? = readProtected(context, ENCRYPTED)
    private fun readProtected(context: Context, field: String): JSONObject? = synchronized(storageMutex) {
        if (storageFault) throw CloudProblemError(ChatGptCloudProblem.BAD_STORAGE)
        try {
            val encoded = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(field, null)
                ?: return@synchronized null
            val packed = Base64.decode(encoded, Base64.NO_WRAP)
            require(packed.size > 28)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, cipherKey(), GCMParameterSpec(128, packed.copyOfRange(0, 12)))
            JSONObject(String(cipher.doFinal(packed.copyOfRange(12, packed.size)), Charsets.UTF_8))
        } catch (_: Exception) { throw CloudProblemError(ChatGptCloudProblem.BAD_STORAGE) }
    }

    /** Serialized token refresh; never reuse rotating refresh credentials. */
    private fun validAccessToken(context: Context, refreshPhase: () -> Unit = {}): String = synchronized(networkMutex) {
        val generation = operations.generation()
        val saved = readRecord(context) ?: throw CloudProblemError(ChatGptCloudProblem.NO_SESSION)
        if (ChatGptTokenResponsePolicy.requiresReauthorization(saved.optBoolean("refresh_pending"))) throw CloudProblemError(ChatGptCloudProblem.REFRESH_REAUTH_REQUIRED)
        if (!ChatGptAuthProtocol.scopeAllowsInference(saved.optString("scope"))) throw CloudProblemError(ChatGptCloudProblem.MISSING_PERMISSION)
        if (saved.getLong("expires_at_ms") > System.currentTimeMillis() + 120_000) {
            return@synchronized saved.getString("access_token")
        }
        val refresh = saved.optString("refresh_token")
        if (refresh.isBlank()) throw CloudProblemError(ChatGptCloudProblem.NO_REFRESH_TOKEN)
        refreshPhase()
        // Durable reservation: after a crash or ambiguous POST, never reuse the rotating token.
        synchronized(storageMutex) {
            check(generation == operations.generation()) { "Session changed" }
            require(saveRecord(context, JSONObject(saved.toString()).put("refresh_pending", true)))
        }
        val updated = try { postForm(ChatGptAuthProtocol.TOKEN, listOf(
            "grant_type" to "refresh_token", "client_id" to saved.getString("client_id"),
            "refresh_token" to refresh, "resource" to ChatGptAuthProtocol.RESOURCE
        )) } catch (ex: CloudHttpError) {
            if (ChatGptTokenResponsePolicy.unusableRefresh(ex.publicCode)) {
                synchronized(storageMutex) {
                    if (generation == operations.generation()) {
                        val ok = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                            .putString("issued_client_id", saved.getString("client_id")).remove(ENCRYPTED).commit()
                        if (!ok) { storageFault = true; throw CloudProblemError(ChatGptCloudProblem.BAD_STORAGE) }
                    }
                }
            }
            throw ex
        }
        require(updated.optString("token_type").equals("Bearer", ignoreCase = true))
        require(updated.getString("access_token").isNotBlank())
        val expiresAt = ChatGptTokenResponsePolicy.expiresAt(System.currentTimeMillis(), updated.getLong("expires_in"), updated.getString("token_type"))
        val replacement = updated.optString("refresh_token")
        require(replacement.isNotBlank()) { "No refresh token returned" }
        saved.put("access_token", updated.getString("access_token"))
            .put("refresh_token", replacement)
            .put("scope", updated.optString("scope", saved.optString("scope")))
            .put("expires_at_ms", expiresAt)
        saved.remove("refresh_pending")
        if (updated.has("earliest_refresh_at")) saved.put("earliest_refresh_at", updated.get("earliest_refresh_at")) else saved.remove("earliest_refresh_at")
        synchronized(storageMutex) {
            check(generation == operations.generation()) { "Session changed" }
            require(saveRecord(context, saved))
        }
        if (!ChatGptAuthProtocol.scopeAllowsInference(saved.optString("scope"))) throw CloudProblemError(ChatGptCloudProblem.MISSING_PERMISSION)
        saved.getString("access_token")
    }

    /** Cloud connection proof, then text-only diagnostic. Does NOT authorize taps. */
    fun testInference(context: Context, report: (String) -> Unit) {
        askPrompt(context,
            "Ответь одним коротким предложением по-русски: тест подключения AI Remote Control GPT успешен.",
            report)
    }

    private data class CloudTextReply(val model: String, val text: String)

    /**
     * One existing OAuth/Responses request path for both manual advisory text
     * and a typed decision. This never dispatches an Android gesture.
     */
    private fun requestModelText(context: Context, prompt: String,
                                 completed: (CloudTextReply?, String?) -> Unit) {
        appContext = context.applicationContext
        if (prompt.isBlank() || prompt.length > 1600) {
            completed(null, "Введите запрос до 1600 символов")
            return
        }
        if (!operations.tryBeginRequest()) {
            completed(null, "AUTH_IN_PROGRESS_OR_CLOUD_BUSY")
            return
        }
        val generation = operations.generation()
        Thread({
            var phase = ChatGptCloudStep.SESSION
            cloudDiagnostic(generation, "CLOUD_SESSION: проверяется сеанс")
            try {
                val bearer = validAccessToken(context) {
                    phase = ChatGptCloudStep.REFRESH
                    cloudDiagnostic(generation, "AUTH_REFRESH: обновление сеанса")
                }
                check(generation == operations.generation()) { "Session changed" }
                phase = ChatGptCloudStep.MODELS
                cloudDiagnostic(generation, "CLOUD_MODELS: загрузка моделей")
                lastModelsUsedDoh = false
                val slug = chooseModel(getJson("https://api.openai.com/v1/models", bearer))
                phase = ChatGptCloudStep.RESPONSES
                cloudDiagnostic(generation, "CLOUD_RESPONSE: ожидается завершённый ответ")
                val input = JSONArray().put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject().put("model", slug).put("store", false)
                    .put("stream", true).put("input", input)
                check(generation == operations.generation()) { "Session changed" }
                val answer = if (lastModelsUsedDoh) {
                    val response = ChatGptDohTransport.call("https://api.openai.com/v1/responses",
                        "POST", bearer, "application/json", body.toString().toByteArray(Charsets.UTF_8))
                    if (response.status !in 200..299)
                        throw classifiedHttpError(response.status, response.body, response.requestId)
                    java.io.BufferedReader(java.io.StringReader(response.body)).use(::readCompletedResponse)
                } else {
                    val c = request("https://api.openai.com/v1/responses", "POST", bearer,
                        "application/json", body.toString().toByteArray(Charsets.UTF_8))
                    try {
                        if (c.responseCode !in 200..299) throw httpError(c)
                        c.inputStream.bufferedReader(Charsets.UTF_8).use(::readCompletedResponse)
                    } finally { c.disconnect() }
                }
                if (generation == operations.generation()) {
                    cloudDiagnostic(generation, "CLOUD_OK: подтверждён завершённый ответ модели $slug")
                    completed(CloudTextReply(slug, answer), null)
                }
            } catch (ex: Exception) {
                if (generation == operations.generation()) {
                    val message = describe(ex, phase)
                    val priorAuth = if (phase == ChatGptCloudStep.SESSION &&
                        (ex as? CloudProblemError)?.problem == ChatGptCloudProblem.NO_SESSION &&
                        lastLoginDiagnostic.contains("AUTH_")) lastLoginDiagnostic else null
                    val usefulMessage = if (priorAuth == null) message
                        else "$message | Последняя ошибка входа: $priorAuth"
                    cloudDiagnostic(generation, usefulMessage)
                    completed(null, usefulMessage)
                }
            } finally {
                operations.finishRequest()
            }
        }, "chatgpt-inference").start()
    }

    /** Manual cloud advisor test. The response remains display-only. */
    fun askPrompt(context: Context, prompt: String, report: (String) -> Unit) {
        requestModelText(context, prompt) { reply, error ->
            if (reply != null)
                report("CLOUD_OK · модель ${reply.model} · ответ: ${reply.text.take(1500)}")
            else
                report("Проверка облачного ИИ не пройдена: ${error ?: "UNKNOWN"}")
        }
    }

    /**
     * Typed GPT decision path. Only independently verified, fresh observations
     * may generate requests. A returned candidate is placed in the existing
     * mailbox; the game-source, rules, amount, STOP and gesture gates still
     * decide if it can ever be acted on. There is no blind action on a callback.
     */
    fun askVerifiedTurnDecision(
        context: Context, observation: AutonomousObservation,
        cards: StableCardReading,
        report: (T043GptDecisionContract.Review) -> Unit
    ) {
        val prompt = T043GptDecisionContract.buildPrompt(observation, cards,
            android.os.SystemClock.elapsedRealtime())
        if (prompt == null) {
            report(T043GptDecisionContract.Review(false, "UNVERIFIED_OR_STALE_TURN"))
            return
        }
        requestModelText(context, prompt) { reply, error ->
            if (reply == null) {
                report(T043GptDecisionContract.Review(false, "CLOUD_FAILURE: ${error ?: "UNKNOWN"}"))
                return@requestModelText
            }
            val review = T043GptDecisionContract.review(reply.text, observation,
                android.os.SystemClock.elapsedRealtime())
            if (!review.accepted || review.proposal == null) {
                report(review)
                return@requestModelText
            }
            val offered = OnDeviceAutonomousRuntime.offerFrameBoundModelAdvice(
                context.applicationContext, review.proposal)
            report(if (offered) review else
                T043GptDecisionContract.Review(false, "MODEL_MAILBOX_REJECTED"))
        }
    }

    /** A completed terminal event is mandatory; never wait past it or accept partial output. */
    internal fun readCompletedResponse(reader: BufferedReader): String {
        val answer = StringBuilder()
        var consumed = 0
        while (true) {
            val line = reader.readLine() ?: throw CloudProblemError(ChatGptCloudProblem.INCOMPLETE_STREAM)
            consumed += line.length
            require(consumed < 1_000_000) { "Stream too large" }
            if (!line.startsWith("data:")) continue
            val content = line.substring(5).trimStart()
            if (content == "[DONE]") throw CloudProblemError(ChatGptCloudProblem.INCOMPLETE_STREAM)
            val event = JSONObject(content)
            when (event.optString("type")) {
                "response.output_text.delta" -> {
                    val delta = event.optString("delta")
                    require(answer.length + delta.length <= 10000) { "Answer too large" }
                    answer.append(delta)
                }
                "response.completed" -> {
                    if (answer.isEmpty()) {
                        val output = event.optJSONObject("response")?.optJSONArray("output")
                        if (output != null) for (i in 0 until output.length()) {
                            val parts = output.optJSONObject(i)?.optJSONArray("content") ?: continue
                            for (j in 0 until parts.length()) {
                                val part = parts.optJSONObject(j) ?: continue
                                if (part.optString("type") != "output_text") continue
                                val text = part.optString("text")
                                require(answer.length + text.length <= 10000) { "Answer too large" }
                                answer.append(text)
                            }
                        }
                    }
                    if (answer.isBlank()) throw CloudProblemError(ChatGptCloudProblem.EMPTY_RESPONSE)
                    return answer.toString()
                }
                "response.failed", "response.incomplete", "error" -> throw streamError(event)
            }
        }
    }

    fun signOut(context: Context, report: (String) -> Unit) {
        var localCleared = false
        val old = synchronized(storageMutex) {
            operations.invalidate()
            lastLoginDiagnostic = "AUTH_SIGNED_OUT: локальный выход"
            lastCloudDiagnostic = "CLOUD_SESSION_NO_SESSION: сеанс завершён"
            try { activeServer?.close() } catch (_: Exception) {}
            val record = try { readRecord(context) } catch (_: Exception) { null }
            val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(ENCRYPTED)
            if (record != null) editor.putString(REGISTRATION, sealed(registration(record)))
            localCleared = editor.commit()
            if (!localCleared) storageFault = true
            record
        }
        val generation = operations.generation()
        Thread({
            var revoked = false
            try {
                if (old != null && old.optString("refresh_token").isNotBlank()) {
                    val body = ChatGptAuthProtocol.form(listOf(
                        "token" to old.getString("refresh_token"), "token_type_hint" to "refresh_token",
                        "client_id" to old.getString("client_id")
                    )).toByteArray(Charsets.UTF_8)
                    val discovery = getJson("${ChatGptAuthProtocol.ISSUER}/.well-known/openid-configuration")
                    require(discovery.getString("issuer") == ChatGptAuthProtocol.ISSUER)
                    val endpoint = URL(discovery.getString("revocation_endpoint"))
                    require(endpoint.protocol == "https" && endpoint.host == "auth.openai.com" && endpoint.userInfo == null && endpoint.query == null && endpoint.ref == null && endpoint.port in listOf(-1,443))
                    val c = request(endpoint.toString(), "POST",
                        contentType = "application/x-www-form-urlencoded", body = body)
                    try { revoked = c.responseCode == 200 && !old.optBoolean("refresh_pending") } finally { c.disconnect() }
                }
            } catch (_:Exception) { /* Report remote revocation uncertainty. */ }
            if (generation == operations.generation()) report(if (!localCleared)
                   "Не удалось подтвердить удаление локальных токенов. Отключите доступ приложения в настройках ChatGPT."
                   else if (revoked) "Сеанс ChatGPT завершён и локальные токены удалены."
                   else "Локальный выход выполнен. Отзыв токена на сервере не подтверждён; доступ можно отключить в ChatGPT → Настройки.")
        }, "chatgpt-logout").start()
    }
}
