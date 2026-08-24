package com.lahiru.travelbuddy.firebase

import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider

class AuthRepository(
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
) {
    val currentUser: FirebaseUser?
        get() = auth.currentUser

    fun signInWithGoogle(
        idToken: String,
        onComplete: (Result<FirebaseUser>) -> Unit,
    ) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        auth.signInWithCredential(credential)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    onComplete(Result.failure(IllegalStateException("Google sign-in returned no user.")))
                } else {
                    onComplete(Result.success(user))
                }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun signOut() {
        auth.signOut()
    }
}
