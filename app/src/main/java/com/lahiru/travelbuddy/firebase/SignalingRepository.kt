package com.lahiru.travelbuddy.firebase

import com.google.firebase.database.ChildEventListener
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import com.lahiru.travelbuddy.data.model.SignalDescription
import com.lahiru.travelbuddy.data.model.SignalIceCandidate
import com.lahiru.travelbuddy.data.model.RoomLiveStatus

class SignalingRepository(
    private val database: FirebaseDatabase = FirebaseDatabase.getInstance(),
) {
    fun pairReference(roomId: String, firstUid: String, secondUid: String): DatabaseReference {
        val pairKey = listOf(firstUid, secondUid).sorted().joinToString("_")
        return database.reference.child(SIGNALING_PATH).child(roomId).child(pairKey)
    }

    fun clearPair(pairReference: DatabaseReference, onComplete: (Result<Unit>) -> Unit) {
        pairReference.removeValue()
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun sendDescription(
        pairReference: DatabaseReference,
        childName: String,
        description: SignalDescription,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val values = mapOf(
            "sessionId" to description.sessionId,
            "fromUid" to description.fromUid,
            "toUid" to description.toUid,
            "type" to description.type,
            "sdp" to description.sdp,
            "createdAt" to ServerValue.TIMESTAMP,
        )
        pairReference.child(childName).setValue(values)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun sendCandidate(
        pairReference: DatabaseReference,
        candidate: SignalIceCandidate,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val values = mapOf(
            "sessionId" to candidate.sessionId,
            "fromUid" to candidate.fromUid,
            "sdpMid" to candidate.sdpMid,
            "sdpMLineIndex" to candidate.sdpMLineIndex,
            "candidate" to candidate.candidate,
            "createdAt" to ServerValue.TIMESTAMP,
        )
        pairReference.child(CANDIDATES_PATH).child(candidate.fromUid).push().setValue(values)
            .addOnSuccessListener { onComplete(Result.success(Unit)) }
            .addOnFailureListener { onComplete(Result.failure(it)) }
    }

    fun observeDescription(
        pairReference: DatabaseReference,
        childName: String,
        onDescription: (SignalDescription?) -> Unit,
        onError: (Exception) -> Unit,
    ): ValueEventListener {
        val reference = pairReference.child(childName)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                onDescription(snapshot.getValue(SignalDescription::class.java))
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        return listener
    }

    fun removeDescriptionListener(
        pairReference: DatabaseReference,
        childName: String,
        listener: ValueEventListener,
    ) {
        pairReference.child(childName).removeEventListener(listener)
    }

    fun observeCandidates(
        pairReference: DatabaseReference,
        remoteUid: String,
        onCandidate: (SignalIceCandidate) -> Unit,
        onError: (Exception) -> Unit,
    ): ChildEventListener {
        val reference = pairReference.child(CANDIDATES_PATH).child(remoteUid)
        val listener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                snapshot.getValue(SignalIceCandidate::class.java)?.let(onCandidate)
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.toException())
            }

            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: DataSnapshot) = Unit
        }
        reference.addChildEventListener(listener)
        return listener
    }

    fun removeCandidateListener(
        pairReference: DatabaseReference,
        remoteUid: String,
        listener: ChildEventListener,
    ) {
        pairReference.child(CANDIDATES_PATH).child(remoteUid).removeEventListener(listener)
    }

    fun updateRoomStatus(
        roomId: String,
        uid: String,
        isConnected: Boolean,
        isSpeaking: Boolean,
        micMode: String,
    ) {
        val memberReference = database.reference.child(ROOM_STATUS_PATH)
            .child(roomId).child(MEMBERS_PATH).child(uid)
        memberReference.updateChildren(
            mapOf(
                "isConnected" to isConnected,
                "isSpeaking" to isSpeaking,
                "micMode" to micMode,
                "updatedAt" to ServerValue.TIMESTAMP,
            ),
        )
    }

    fun registerRoomPresence(
        roomId: String,
        uid: String,
        micMode: String,
        onComplete: (Result<Unit>) -> Unit,
    ) {
        val memberReference = database.reference.child(ROOM_STATUS_PATH)
            .child(roomId).child(MEMBERS_PATH).child(uid)
        memberReference.onDisconnect().removeValue { error, _ ->
            if (error == null) {
                memberReference.setValue(
                    mapOf(
                        "isConnected" to false,
                        "isSpeaking" to false,
                        "micMode" to micMode,
                        "updatedAt" to ServerValue.TIMESTAMP,
                    ),
                ).addOnSuccessListener { onComplete(Result.success(Unit)) }
                    .addOnFailureListener { onComplete(Result.failure(it)) }
            } else {
                onComplete(Result.failure(error.toException()))
            }
        }
    }

    fun observeRoomStatuses(
        roomId: String,
        onStatusesChanged: (Map<String, RoomLiveStatus>) -> Unit,
        onError: (Exception) -> Unit,
    ): ValueEventListener {
        val reference = database.reference.child(ROOM_STATUS_PATH)
            .child(roomId).child(MEMBERS_PATH)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val statuses = snapshot.children.mapNotNull { child ->
                    val uid = child.key.orEmpty()
                    if (uid.isBlank()) return@mapNotNull null
                    val status = child.getValue(RoomLiveStatus::class.java) ?: RoomLiveStatus()
                    uid to status.copy(uid = uid)
                }.toMap()
                onStatusesChanged(statuses)
            }

            override fun onCancelled(error: DatabaseError) {
                onError(error.toException())
            }
        }
        reference.addValueEventListener(listener)
        return listener
    }

    fun removeRoomStatusesListener(roomId: String, listener: ValueEventListener) {
        database.reference.child(ROOM_STATUS_PATH).child(roomId).child(MEMBERS_PATH)
            .removeEventListener(listener)
    }

    fun removeRoomStatus(
        roomId: String,
        uid: String,
        onComplete: () -> Unit = {},
    ) {
        val reference = database.reference.child(ROOM_STATUS_PATH)
            .child(roomId).child(MEMBERS_PATH).child(uid)
        reference.onDisconnect().cancel { _, _ ->
            reference.removeValue { _, _ -> onComplete() }
        }
    }

    companion object {
        const val OFFER_CHILD = "offer"
        const val ANSWER_CHILD = "answer"
        private const val SIGNALING_PATH = "signaling"
        private const val CANDIDATES_PATH = "candidates"
        private const val ROOM_STATUS_PATH = "room_status"
        private const val MEMBERS_PATH = "members"
    }
}
