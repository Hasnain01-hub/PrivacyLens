package com.example.privacyassistant

import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST
import java.util.concurrent.TimeUnit

// ── Request bodies matching FastAPI Pydantic models ──────────

data class ContactPayload(
    val contactKey: String,
    val nameEnc: String,
    val timestamp: Long
)

data class MessagePayload(
    val contactKey: String,
    val messageTextEnc: String,
    val date: String?,
    val time: String?,
    val groupLabelMessageId: Long?,
    val isSentByUser: Boolean,
    val assetType: String,
    val fingerprint: String,
    val duration: String?
)

data class ContactResponse(val ok: Boolean)
data class MessageResponse(val id: Long?, val inserted: Boolean)

// ── Retrofit interface ────────────────────────────────────────

interface InstagramApiService {

    @POST("/contacts")
    suspend fun addContact(
        @Body body: ContactPayload,
        @Header("Authorization") auth: String
    ): Response<ContactResponse>

    @POST("/messages")
    suspend fun addMessage(
        @Body body: MessagePayload,
        @Header("Authorization") auth: String
    ): Response<MessageResponse>
}

// ── Singleton factory ─────────────────────────────────────────

object RemoteApiService {

    // Set your server URL via BuildConfig or hardcode for dev
    private const val BASE_URL = "http://141.5.108.226:8080/"  // e.g. "https://your-server.com/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG)
                HttpLoggingInterceptor.Level.BODY
            else
                HttpLoggingInterceptor.Level.NONE
        })
        .build()

    val api: InstagramApiService by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(InstagramApiService::class.java)
    }
}