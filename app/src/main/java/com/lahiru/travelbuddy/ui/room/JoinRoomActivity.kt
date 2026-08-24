package com.lahiru.travelbuddy.ui.room

import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.lahiru.travelbuddy.R
import com.lahiru.travelbuddy.data.model.Room
import com.lahiru.travelbuddy.databinding.ActivityJoinRoomBinding
import com.lahiru.travelbuddy.firebase.AuthRepository
import com.lahiru.travelbuddy.firebase.RoomRepository
import com.lahiru.travelbuddy.ui.comms.CommsRoomActivity
import com.lahiru.travelbuddy.ui.login.LoginActivity

class JoinRoomActivity : ComponentActivity() {

    private lateinit var binding: ActivityJoinRoomBinding
    private val authRepository = AuthRepository()
    private val roomRepository = RoomRepository()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityJoinRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.joinButton.setOnClickListener { joinRoom() }
        binding.cancelButton.setOnClickListener { finish() }
    }

    private fun joinRoom() {
        val user = authRepository.currentUser
        if (user == null) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        val roomCode = RoomCodeValidator.normalize(binding.roomCodeInput.text.toString())
        if (!RoomCodeValidator.isValid(roomCode)) {
            binding.roomCodeInput.error = getString(R.string.join_room_code_error)
            return
        }

        setLoading(true)
        roomRepository.joinRoom(roomCode, user) { result ->
            setLoading(false)
            result.onSuccess(::openRoom)
                .onFailure { error ->
                    binding.joinStatusText.text = error.localizedMessage
                    Toast.makeText(this, error.localizedMessage, Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun setLoading(loading: Boolean) {
        binding.joinButton.isEnabled = !loading
        binding.roomCodeInput.isEnabled = !loading
        binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        binding.joinStatusText.text = if (loading) {
            getString(R.string.join_room_searching)
        } else {
            ""
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
