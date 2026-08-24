package com.lahiru.travelbuddy.firebase

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

class UserRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
) {
    fun registerOrUpdateUser(
        user: FirebaseUser,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val userRef = firestore.collection(USERS_COLLECTION).document(user.uid)

        firestore.runTransaction { transaction ->
            val existingUser = transaction.get(userRef)
            val values = mutableMapOf<String, Any?>(
                "uid" to user.uid,
                "displayName" to (user.displayName?.trim().orEmpty().ifBlank { "Travel Buddy User" }),
                "email" to user.email.orEmpty(),
                "photoUrl" to user.photoUrl?.toString(),
                "lastLoginAt" to FieldValue.serverTimestamp(),
            )
            if (!existingUser.exists()) {
                values["createdAt"] = FieldValue.serverTimestamp()
            }
            transaction.set(userRef, values, SetOptions.merge())
        }.addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    companion object {
        const val USERS_COLLECTION = "users"
    }
}
