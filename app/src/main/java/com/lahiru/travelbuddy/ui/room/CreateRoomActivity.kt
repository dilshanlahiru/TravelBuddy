package com.lahiru.travelbuddy.ui.room

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.lahiru.travelbuddy.R
import com.lahiru.travelbuddy.data.model.Room
import com.lahiru.travelbuddy.databinding.ActivityCreateRoomBinding
import com.lahiru.travelbuddy.firebase.AuthRepository
import com.lahiru.travelbuddy.firebase.RoomRepository
import com.lahiru.travelbuddy.ui.comms.CommsRoomActivity
import com.lahiru.travelbuddy.ui.login.LoginActivity

class CreateRoomActivity : ComponentActivity() {

    private lateinit var binding: ActivityCreateRoomBinding
    private val authRepository = AuthRepository()
    private val roomRepository = RoomRepository()
    private var createdRoom: Room? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCreateRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.retryButton.setOnClickListener { createRoom() }
        binding.enterRoomButton.setOnClickListener {
            createdRoom?.let(::openRoom)
        }
        binding.cancelButton.setOnClickListener { finish() }
        createRoom()
    }

    private fun createRoom() {
        val user = authRepository.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        createdRoom = null
        binding.progressBar.visibility = View.VISIBLE
        binding.roomCodeText.text = getString(R.string.room_code_placeholder)
        binding.createRoomStatusText.text = getString(R.string.create_room_status)
        binding.retryButton.visibility = View.GONE
        binding.enterRoomButton.isEnabled = false

        roomRepository.createRoom(user) { result ->
            binding.progressBar.visibility = View.GONE
            result.onSuccess { room ->
                createdRoom = room
                binding.roomCodeText.text = room.roomCode
                binding.createRoomStatusText.text = getString(R.string.create_room_ready)
                binding.enterRoomButton.isEnabled = true
            }.onFailure { error ->
                binding.createRoomStatusText.text = getString(R.string.create_room_failed)
                binding.retryButton.visibility = View.VISIBLE
                Toast.makeText(this, error.localizedMessage, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun openRoom(room: Room) {
        startActivity(
            Intent(this, CommsRoomActivity::class.java).apply {
                putExtra(RoomNavigation.EXTRA_ROOM_ID, room.roomId)
                putExtra(RoomNavigation.EXTRA_ROOM_CODE, room.roomCode)
            },
        )
        finish()
    }
}
