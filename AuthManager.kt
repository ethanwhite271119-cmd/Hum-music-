// AuthManager.kt — Google + Spotify login for an Android app (Kotlin)
//
// ---------- build.gradle.kts (app) ----------
// plugins { id("com.google.gms.google-services") }
// android {
//     defaultConfig {
//         // Must match SPOTIFY_REDIRECT_URI below and be registered in the Spotify dashboard
//         manifestPlaceholders["appAuthRedirectScheme"] = "com.example.hum"
//     }
// }
// dependencies {
//     implementation(platform("com.google.firebase:firebase-bom:33.1.2"))
//     implementation("com.google.firebase:firebase-auth")
//     implementation("androidx.credentials:credentials:1.3.0")
//     implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
//     implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
//     implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.8.1")
//     implementation("net.openid:appauth:0.11.1")
// }
// (Check for newer versions of each library before shipping.)

package com.example.hum.auth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.ktx.auth
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.tasks.await
import net.openid.appauth.*

// ================= GOOGLE =================
// Setup: Firebase console -> add your Android app (with SHA-1) -> enable Google
// sign-in. Use the *Web* client ID shown there.
private const val GOOGLE_WEB_CLIENT_ID = "YOUR_WEB_CLIENT_ID.apps.googleusercontent.com"

/** Call from a coroutine (e.g. lifecycleScope.launch). Pass an Activity. */
suspend fun signInWithGoogle(activity: Activity): FirebaseUser? {
    val credentialManager = CredentialManager.create(activity)

    val googleIdOption = GetGoogleIdOption.Builder()
        .setFilterByAuthorizedAccounts(false) // show all Google accounts on device
        .setServerClientId(GOOGLE_WEB_CLIENT_ID)
        .build()

    val request = GetCredentialRequest.Builder()
        .addCredentialOption(googleIdOption)
        .build()

    val result = credentialManager.getCredential(activity, request)
    val credential = result.credential

    if (credential is CustomCredential &&
        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
    ) {
        val idToken = GoogleIdTokenCredential.createFrom(credential.data).idToken
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        return Firebase.auth.signInWithCredential(firebaseCredential).await().user
    }
    return null
}

// ================= SPOTIFY =================
// Setup: developer.spotify.com/dashboard -> create app -> add the redirect URI
// below -> copy the Client ID. No client secret is needed (PKCE).
private const val SPOTIFY_CLIENT_ID = "YOUR_SPOTIFY_CLIENT_ID"
private const val SPOTIFY_REDIRECT_URI = "com.example.hum://callback"

class SpotifyAuth(activity: Activity) {
    private val authService = AuthorizationService(activity)

    private val config = AuthorizationServiceConfiguration(
        Uri.parse("https://accounts.spotify.com/authorize"),
        Uri.parse("https://accounts.spotify.com/api/token")
    )

    /** Launch this with an ActivityResultLauncher<Intent>. */
    fun buildLoginIntent(): Intent {
        val request = AuthorizationRequest.Builder(
            config,
            SPOTIFY_CLIENT_ID,
            ResponseTypeValues.CODE, // PKCE verifier is generated automatically
            Uri.parse(SPOTIFY_REDIRECT_URI)
        )
            .setScope("user-read-email user-read-private")
            .build()
        return authService.getAuthorizationRequestIntent(request)
    }

    /** Call from the launcher's result callback. */
    fun handleResult(data: Intent?, onToken: (String?) -> Unit) {
        val response = data?.let { AuthorizationResponse.fromIntent(it) }
        val error = data?.let { AuthorizationException.fromIntent(it) }
        if (response == null) {
            onToken(null) // user cancelled or error; inspect `error`
            return
        }
        authService.performTokenRequest(response.createTokenExchangeRequest()) { tokenResponse, _ ->
            onToken(tokenResponse?.accessToken) // store securely (e.g. EncryptedSharedPreferences)
        }
    }

    fun dispose() = authService.dispose()
}

// ---------- Usage in an Activity ----------
// Google:
//   lifecycleScope.launch { val user = signInWithGoogle(this@MainActivity) }
//
// Spotify:
//   private lateinit var spotify: SpotifyAuth
//   private val spotifyLauncher = registerForActivityResult(
//       ActivityResultContracts.StartActivityForResult()
//   ) { spotify.handleResult(it.data) { token -> /* use token */ } }
//   ...
//   spotify = SpotifyAuth(this)
//   loginButton.setOnClickListener { spotifyLauncher.launch(spotify.buildLoginIntent()) }
