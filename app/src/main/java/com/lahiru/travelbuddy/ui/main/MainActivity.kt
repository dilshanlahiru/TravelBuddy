package com.lahiru.travelbuddy.ui.main

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.lifecycle.lifecycleScope
import com.lahiru.travelbuddy.databinding.ActivityMainBinding
import com.lahiru.travelbuddy.firebase.AuthRepository
import com.lahiru.travelbuddy.ui.login.LoginActivity
import com.lahiru.travelbuddy.ui.room.CreateRoomActivity
import com.lahiru.travelbuddy.ui.room.JoinRoomActivity
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var binding: ActivityMainBinding
    private val authRepository = AuthRepository()
    private val credentialManager by lazy { CredentialManager.create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val user = authRepository.currentUser
        if (user == null) {
            openLoginActivity()
            return
        }

        binding.userNameText.text = user.displayName?.trim().orEmpty().ifBlank {
            user.email.orEmpty()
        }
        binding.createRoomButton.setOnClickListener {
            startActivity(Intent(this, CreateRoomActivity::class.java))
        }
        binding.joinRoomButton.setOnClickListener {
            startActivity(Intent(this, JoinRoomActivity::class.java))
        }
        binding.signOutButton.setOnClickListener { signOut() }
    }

    private fun signOut() {
        binding.signOutButton.isEnabled = false
        lifecycleScope.launch {
            runCatching {
                credentialManager.clearCredentialState(ClearCredentialStateRequest())
            }
            authRepository.signOut()
            openLoginActivity()
        }
    }

    private fun openLoginActivity() {
        startActivity(
            Intent(this, LoginActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }
}
