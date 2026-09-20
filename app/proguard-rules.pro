# ---- Credential Manager + Sign in with Google ----
# The Play Services credential provider is resolved reflectively; keep the credentials API and
# the googleid helper so R8 does not strip classes needed at runtime in release builds.
-keep class androidx.credentials.** { *; }
-keep interface androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# Credential provider implementations are discovered via reflection.
-keep class * extends androidx.credentials.CredentialProvider { *; }

# ---- Firebase Auth ----
# Firebase/GMS already ship consumer ProGuard rules, but keep GoogleAuthProvider defensively.
-keep class com.google.firebase.auth.** { *; }
