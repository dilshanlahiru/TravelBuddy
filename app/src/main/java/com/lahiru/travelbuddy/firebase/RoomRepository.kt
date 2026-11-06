package com.lahiru.travelbuddy.firebase

import com.google.firebase.auth.FirebaseUser
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.functions.FirebaseFunctions
import com.lahiru.travelbuddy.data.model.Room
import com.lahiru.travelbuddy.data.model.RoomMember

class RoomRepository(
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance(),
    private val functions: FirebaseFunctions = FirebaseFunctions.getInstance(FUNCTIONS_REGION),
) {
    fun createRoom(
        @Suppress("UNUSED_PARAMETER") user: FirebaseUser,
        onComplete: (Result<Room>) -> Unit,
    ) {
        functions.getHttpsCallable(CREATE_ROOM_FUNCTION)
            .call()
            .addOnSuccessListener { result ->
                runCatching { result.data.toRoom() }
                    .fold(
                        onSuccess = { onComplete(Result.success(it)) },
                        onFailure = { onComplete(Result.failure(it)) },
                    )
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun joinRoom(
        roomCode: String,
        @Suppress("UNUSED_PARAMETER") user: FirebaseUser,
        onComplete: (Result<Room>) -> Unit,
    ) {
        functions.getHttpsCallable(JOIN_ROOM_FUNCTION)
            .call(mapOf("roomId" to roomCode))
            .addOnSuccessListener { result ->
                runCatching { result.data.toRoom() }
                    .fold(
                        onSuccess = { onComplete(Result.success(it)) },
                        onFailure = { onComplete(Result.failure(it)) },
                    )
            }
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

    fun getRoom(
        roomId: String,
        onComplete: (Result<Room>) -> Unit,
    ) {
        firestore.collection(ROOMS_COLLECTION).document(roomId).get()
            .addOnSuccessListener { snapshot ->
                val room = snapshot.toObject(Room::class.java)
                if (room == null) {
                    onComplete(Result.failure(IllegalStateException("The room is unavailable.")))
                } else {
                    onComplete(Result.success(room))
                }
            }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun leaveRoom(
        roomId: String,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        functions.getHttpsCallable(LEAVE_ROOM_FUNCTION)
            .call(mapOf("roomId" to roomId))
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun closeRoom(
        roomId: String,
        onComplete: (Result<Unit>) -> Unit = {},
    ) {
        functions.getHttpsCallable(CLOSE_ROOM_FUNCTION)
            .call(mapOf("roomId" to roomId))
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    private fun Any?.toRoom(): Room {
        val values = this as? Map<*, *>
            ?: throw IllegalStateException("The room service returned invalid data.")
        val roomId = values["roomId"] as? String
            ?: throw IllegalStateException("The room service did not return a room ID.")
        return Room(
            roomId = roomId,
            roomCode = values["roomCode"] as? String ?: roomId,
            createdBy = values["createdBy"] as? String ?: "",
            status = values["status"] as? String ?: Room.STATUS_ACTIVE,
            memberCount = (values["memberCount"] as? Number)?.toLong() ?: 0L,
        )
    }

    companion object {
        const val ROOMS_COLLECTION = "rooms"
        const val MEMBERS_COLLECTION = "members"
        const val MAX_ROOM_MEMBERS = 6
        private const val FUNCTIONS_REGION = "asia-southeast1"
        private const val CREATE_ROOM_FUNCTION = "createRoom"
        private const val JOIN_ROOM_FUNCTION = "joinRoom"
        private const val LEAVE_ROOM_FUNCTION = "leaveRoom"
        private const val CLOSE_ROOM_FUNCTION = "closeRoom"
    }
}
