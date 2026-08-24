package com.lahiru.travelbuddy.firebase

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.lahiru.travelbuddy.data.model.Room
import com.lahiru.travelbuddy.data.model.RoomMember
import java.security.SecureRandom
import kotlin.math.max

class RoomRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val random: SecureRandom = SecureRandom(),
) {
    fun createRoom(
        user: FirebaseUser,
        onComplete: (Result<Room>) -> Unit,
    ) {
        createRoomAttempt(user, MAX_CREATE_ATTEMPTS, onComplete)
    }

    private fun createRoomAttempt(
        user: FirebaseUser,
        attemptsRemaining: Int,
        onComplete: (Result<Room>) -> Unit,
    ) {
        val roomCode = random.nextInt(900_000).plus(100_000).toString()
        val roomRef = firestore.collection(ROOMS_COLLECTION).document(roomCode)
        val memberRef = roomRef.collection(MEMBERS_COLLECTION).document(user.uid)

        firestore.runTransaction { transaction ->
            if (transaction.get(roomRef).exists()) {
                throw RoomCodeCollisionException()
            }

            transaction.set(
                roomRef,
                mapOf(
                    "roomId" to roomCode,
                    "roomCode" to roomCode,
                    "createdBy" to user.uid,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "status" to Room.STATUS_ACTIVE,
                    "memberCount" to 1L,
                ),
            )
            transaction.set(memberRef, memberValues(user))
        }.addOnSuccessListener {
            onComplete(
                Result.success(
                    Room(
                        roomId = roomCode,
                        roomCode = roomCode,
                        createdBy = user.uid,
                        status = Room.STATUS_ACTIVE,
                        memberCount = 1,
                    ),
                ),
            )
        }.addOnFailureListener { error ->
            if (error is RoomCodeCollisionException && attemptsRemaining > 1) {
                createRoomAttempt(user, attemptsRemaining - 1, onComplete)
            } else {
                onComplete(Result.failure(error))
            }
        }
    }

    fun joinRoom(
        roomCode: String,
        user: FirebaseUser,
        onComplete: (Result<Room>) -> Unit,
    ) {
        firestore.collection(ROOMS_COLLECTION)
            .whereEqualTo("roomCode", roomCode)
            .whereEqualTo("status", Room.STATUS_ACTIVE)
            .limit(1)
            .get()
            .addOnSuccessListener { result ->
                val roomDocument = result.documents.firstOrNull()
                if (roomDocument == null) {
                    onComplete(Result.failure(RoomNotFoundException()))
                    return@addOnSuccessListener
                }
                addMember(roomDocument.id, user, onComplete)
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    private fun addMember(
        roomId: String,
        user: FirebaseUser,
        onComplete: (Result<Room>) -> Unit,
    ) {
        val roomRef = firestore.collection(ROOMS_COLLECTION).document(roomId)
        val memberRef = roomRef.collection(MEMBERS_COLLECTION).document(user.uid)

        firestore.runTransaction { transaction ->
            val roomSnapshot = transaction.get(roomRef)
            val memberSnapshot = transaction.get(memberRef)
            val room = roomSnapshot.toObject(Room::class.java)
                ?: throw RoomNotFoundException()
            if (room.status != Room.STATUS_ACTIVE) {
                throw RoomClosedException()
            }

            if (!memberSnapshot.exists()) {
                transaction.update(roomRef, "memberCount", FieldValue.increment(1L))
                transaction.set(memberRef, memberValues(user))
            } else {
                transaction.set(
                    memberRef,
                    mapOf(
                        "displayName" to displayName(user),
                        "photoUrl" to user.photoUrl?.toString(),
                    ),
                    SetOptions.merge(),
                )
            }
            room
        }.addOnSuccessListener { room -> onComplete(Result.success(room)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun listenToMembers(
        roomId: String,
        onMembersChanged: (List<RoomMember>) -> Unit,
        onError: (Exception) -> Unit,
    ): ListenerRegistration = firestore.collection(ROOMS_COLLECTION)
        .document(roomId)
        .collection(MEMBERS_COLLECTION)
        .orderBy("joinedAt")
        .addSnapshotListener { snapshot, error ->
            if (error != null) {
                onError(error)
                return@addSnapshotListener
            }
            val members = snapshot?.documents
                ?.mapNotNull { it.toObject(RoomMember::class.java) }
                .orEmpty()
            onMembersChanged(members)
        }

    fun leaveRoom(
        roomId: String,
        uid: String,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        val roomRef = firestore.collection(ROOMS_COLLECTION).document(roomId)
        val memberRef = roomRef.collection(MEMBERS_COLLECTION).document(uid)
        firestore.runTransaction { transaction ->
            val roomSnapshot = transaction.get(roomRef)
            val memberSnapshot = transaction.get(memberRef)
            if (!memberSnapshot.exists()) return@runTransaction

            val currentCount = roomSnapshot.getLong("memberCount") ?: 1L
            val nextCount = max(0L, currentCount - 1L)
            transaction.delete(memberRef)
            if (nextCount == 0L) {
                transaction.update(
                    roomRef,
                    mapOf("memberCount" to 0L, "status" to Room.STATUS_CLOSED),
                )
            } else {
                transaction.update(roomRef, "memberCount", nextCount)
            }
        }.addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    private fun memberValues(user: FirebaseUser): Map<String, Any?> = mapOf(
        "uid" to user.uid,
        "displayName" to displayName(user),
        "photoUrl" to user.photoUrl?.toString(),
        "joinedAt" to FieldValue.serverTimestamp(),
        "isConnected" to false,
        "isSpeaking" to false,
        "micMode" to RoomMember.MIC_MODE_PTT,
    )

    private fun displayName(user: FirebaseUser): String =
        user.displayName?.trim().orEmpty().ifBlank { "Travel Buddy User" }

    companion object {
        const val ROOMS_COLLECTION = "rooms"
        const val MEMBERS_COLLECTION = "members"
        private const val MAX_CREATE_ATTEMPTS = 5
    }
}

class RoomNotFoundException : Exception("No active room was found for that code.")
class RoomClosedException : Exception("This room is no longer active.")
private class RoomCodeCollisionException : Exception("The generated room code is already in use.")
