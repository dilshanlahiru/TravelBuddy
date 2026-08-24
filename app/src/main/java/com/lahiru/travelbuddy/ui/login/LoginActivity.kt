package com.lahiru.travelbuddy.ui.login

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.lifecycleScope
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.lahiru.travelbuddy.R
import com.lahiru.travelbuddy.databinding.ActivityLoginBinding
import com.lahiru.travelbuddy.firebase.AuthRepository
import com.lahiru.travelbuddy.firebase.UserRepository
import com.lahiru.travelbuddy.ui.main.MainActivity
import kotlinx.coroutines.launch

class LoginActivity : ComponentActivity() {

    private lateinit var binding: ActivityLoginBinding
    private val authRepository = AuthRepository()
    private val userRepository = UserRepository()
    private val credentialManager by lazy { CredentialManager.create(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (authRepository.currentUser != null) {
            openMainActivity()
            return
        }

        binding.googleSignInButton.setOnClickListener { startGoogleSignIn() }
    }

    private fun startGoogleSignIn() {
        setLoading(true, getString(R.string.login_status_opening_google))

        lifecycleScope.launch {
            try {
                val googleOption = GetSignInWithGoogleOption.Builder(
                    getString(R.string.default_web_client_id),
                ).build()
                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleOption)
                    .build()
                val result = credentialManager.getCredential(
                    context = this@LoginActivity,
                    request = request,
                )
                val credential = result.credential
                if (
                    credential !is CustomCredential ||
                    credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    throw IllegalStateException("Google returned an unsupported credential.")
                }

                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                authenticateWithFirebase(googleCredential.idToken)
            } catch (_: GetCredentialCancellationException) {
                setLoading(false, getString(R.string.login_status_cancelled))
            } catch (_: NoCredentialException) {
                setLoading(false, getString(R.string.login_status_no_account))
            } catch (error: GetCredentialException) {
                showLoginError(error)
            } catch (error: Exception) {
                showLoginError(error)
            }
        }
    }

    private fun authenticateWithFirebase(idToken: String) {
        binding.loginStatusText.text = getString(R.string.login_status_authenticating)
        authRepository.signInWithGoogle(idToken) { authResult ->
            authResult.onSuccess { firebaseUser ->
                binding.loginStatusText.text = getString(R.string.login_status_saving_profile)
                userRepository.registerOrUpdateUser(firebaseUser) { userResult ->
                    userResult.onSuccess { openMainActivity() }
                        .onFailure { error ->
                            authRepository.signOut()
                            showLoginError(error)
                        }
                }
            }.onFailure(::showLoginError)
        }
    }

    private fun showLoginError(error: Throwable) {
        val message = error.localizedMessage ?: getString(R.string.login_error_generic)
        setLoading(false, getString(R.string.login_status_failed))
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun setLoading(loading: Boolean, status: String) {
        binding.googleSignInButton.isEnabled = !loading
        binding.loginProgress.visibility = if (loading) android.view.View.VISIBLE else android.view.View.GONE
        binding.loginStatusText.text = status
    }

    private fun openMainActivity() {
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            },
        )
        finish()
    }
}
