package com.lahiru.travelbuddy.ui.comms

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.net.Uri
import android.view.MotionEvent
import android.widget.Toast
import android.app.AlertDialog
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.google.firebase.firestore.ListenerRegistration
import com.lahiru.travelbuddy.R
import com.lahiru.travelbuddy.databinding.ActivityCommsRoomBinding
import com.lahiru.travelbuddy.firebase.RoomRepository
import com.lahiru.travelbuddy.data.model.RoomMember
import com.lahiru.travelbuddy.data.model.RoomLiveStatus
import com.lahiru.travelbuddy.audio.AudioRoute
import com.lahiru.travelbuddy.webrtc.WebRtcManager
import com.lahiru.travelbuddy.service.CommsForegroundService
import com.lahiru.travelbuddy.ui.room.RoomNavigation

class CommsRoomActivity : ComponentActivity(), CommsForegroundService.StateListener {

    private lateinit var binding: ActivityCommsRoomBinding
    private val roomRepository = RoomRepository()
    private lateinit var memberAdapter: RoomMemberAdapter
    private var memberListener: ListenerRegistration? = null
    private var commsService: CommsForegroundService? = null
    private var isServiceBound = false
    private var permissionRequestStarted = false
    private var roomId: String = ""
    private var roomCode: String = ""
    private var micMode = MicMode.PTT
    private var isAudioReady = false
    private var roomMembers: List<RoomMember> = emptyList()
    private var roomStatuses: Map<String, RoomLiveStatus> = emptyMap()
    private var peerStatuses: Map<String, WebRtcManager.PeerStatus> = emptyMap()
    private var availableAudioRoutes: Set<AudioRoute> = emptySet()
    private var selectedAudioRoute: AudioRoute = AudioRoute.EARPIECE
    private var updatingFloatingPttCheckBox = false
    private var updatingMicModeSelection = false

    private val bluetoothPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        commsService?.refreshAudioRoutes()
        binding.root.post {
            if (!granted) {
                Toast.makeText(
                    this,
                    R.string.bluetooth_permission_denied,
                    Toast.LENGTH_LONG,
                ).show()
            }
            showAudioRouteDialog()
        }
    }

    private val overlayPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val granted = Settings.canDrawOverlays(this)
        setFloatingPttChecked(granted)
        commsService?.setFloatingPttEnabled(granted)
        if (!granted) {
            Toast.makeText(
                this,
                R.string.floating_ptt_permission_denied,
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { results ->
        permissionRequestStarted = false
        if (results[Manifest.permission.RECORD_AUDIO] == true || hasAudioPermission()) {
            startAndBindCommsService()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                results[Manifest.permission.POST_NOTIFICATIONS] == false
            ) {
                Toast.makeText(this, R.string.notification_permission_denied, Toast.LENGTH_LONG).show()
            }
        } else {
            showPermissionDeniedState()
        }
    }

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? CommsForegroundService.LocalBinder ?: return
            commsService = localBinder.getService()
            isServiceBound = true
            commsService?.setRoomUiVisible(true)
            syncMicModeFromService(localBinder.getService())
            commsService?.setStateListener(this@CommsRoomActivity)
            val overlayEnabled = commsService?.isFloatingPttEnabled == true &&
                Settings.canDrawOverlays(this@CommsRoomActivity)
            setFloatingPttChecked(overlayEnabled)
            if (!overlayEnabled) commsService?.setFloatingPttEnabled(false)
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            commsService = null
            isServiceBound = false
            showAudioUnavailableState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityCommsRoomBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomId = intent.getStringExtra(RoomNavigation.EXTRA_ROOM_ID).orEmpty()
        roomCode = intent.getStringExtra(RoomNavigation.EXTRA_ROOM_CODE).orEmpty()
        if (roomId.isBlank() || roomCode.isBlank()) {
            Toast.makeText(this, R.string.room_details_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        binding.roomCodeText.text = roomCode
        memberAdapter = RoomMemberAdapter(this)
        binding.memberList.adapter = memberAdapter
        binding.micModeGroup.setOnCheckedChangeListener { _, checkedId ->
            if (updatingMicModeSelection) return@setOnCheckedChangeListener
            val selectedMode = when (checkedId) {
                R.id.toggleModeButton -> MicMode.TOGGLE
                R.id.pttModeButton -> MicMode.PTT
                else -> return@setOnCheckedChangeListener
            }
            // RadioGroup can re-emit its current selection while the window is losing focus or
            // restoring state. Only a real user mode change may mute an active microphone.
            if (!hasWindowFocus() || selectedMode == micMode) {
                return@setOnCheckedChangeListener
            }
            micMode = selectedMode
            commsService?.setMicMode(
                if (micMode == MicMode.TOGGLE) RoomMember.MIC_MODE_TOGGLE else RoomMember.MIC_MODE_PTT,
            )
            if (commsService?.isMicEnabled == true) {
                setMicEnabled(false)
            }
            updateMicButton(false)
            updateControlAvailability()
        }
        binding.micButton.setOnClickListener {
            if (micMode == MicMode.TOGGLE) {
                MicControlPolicy.desiredState(
                    mode = MicMode.TOGGLE,
                    gesture = MicGesture.RELEASE,
                    currentlyEnabled = commsService?.isMicEnabled == true,
                )?.let(::setMicEnabled)
            }
        }
        binding.micButton.setOnTouchListener { view, event ->
            if (micMode != MicMode.PTT) return@setOnTouchListener false
            val gesture = when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> MicGesture.PRESS
                MotionEvent.ACTION_UP -> MicGesture.RELEASE
                MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_OUTSIDE -> MicGesture.CANCEL
                else -> return@setOnTouchListener true
            }
            MicControlPolicy.desiredState(
                mode = MicMode.PTT,
                gesture = gesture,
                currentlyEnabled = commsService?.isMicEnabled == true,
            )?.let(::setMicEnabled)
            if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
            true
        }
        binding.permissionButton.setOnClickListener {
            permissionRequestStarted = false
            requestPermissionsOrStart()
        }
        binding.leaveRoomButton.setOnClickListener { leaveRoom() }
        binding.audioOutputButton.setOnClickListener { requestRoutesAndShowDialog() }
        binding.floatingPttCheckBox.setOnCheckedChangeListener { _, checked ->
            if (updatingFloatingPttCheckBox) return@setOnCheckedChangeListener
            if (!checked) {
                commsService?.setFloatingPttEnabled(false)
            } else if (Settings.canDrawOverlays(this)) {
                commsService?.setFloatingPttEnabled(true)
            } else {
                requestOverlayPermission()
            }
        }
        showAudioUnavailableState()
    }

    override fun onStart() {
        super.onStart()
        if (roomId.isBlank()) return
        listenToMembers()
        requestPermissionsOrStart()
    }

    override fun onStop() {
        memberListener?.remove()
        memberListener = null
        if (isServiceBound) {
            commsService?.setRoomUiVisible(false)
            commsService?.setStateListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
            commsService = null
        }
        super.onStop()
    }

    override fun onAudioReady(micEnabled: Boolean) {
        isAudioReady = true
        binding.permissionButton.visibility = android.view.View.GONE
        updateControlAvailability()
        renderConnectionStatus()
        updateMicButton(micEnabled)
    }

    override fun onMicStateChanged(enabled: Boolean) {
        updateMicButton(enabled)
    }

    override fun onPeerStatusesChanged(statuses: Map<String, WebRtcManager.PeerStatus>) {
        peerStatuses = statuses
        updateControlAvailability()
        renderConnectionStatus()
    }

    override fun onRoomStatusesChanged(statuses: Map<String, RoomLiveStatus>) {
        roomStatuses = statuses
        renderMembers()
    }

    override fun onAudioRoutesChanged(available: Set<AudioRoute>, selected: AudioRoute) {
        availableAudioRoutes = available
        selectedAudioRoute = selected
        binding.audioOutputButton.setText(
            when (selected) {
                AudioRoute.EARPIECE -> R.string.audio_output_earpiece
                AudioRoute.SPEAKER -> R.string.audio_output_speaker
                AudioRoute.BLUETOOTH -> R.string.audio_output_bluetooth
            },
        )
    }

    override fun onCommsError(message: String, fatal: Boolean) {
        if (fatal) showAudioUnavailableState()
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun requestPermissionsOrStart() {
        if (hasAudioPermission()) {
            startAndBindCommsService()
            requestNotificationPermissionIfNeeded()
            return
        }
        if (permissionRequestStarted) return
        permissionRequestStarted = true
        val permissions = buildList {
            add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED || permissionRequestStarted
        ) return
        permissionRequestStarted = true
        permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
    }

    private fun startAndBindCommsService() {
        if (!hasAudioPermission()) return
        val serviceIntent = Intent(this, CommsForegroundService::class.java).apply {
            action = CommsForegroundService.ACTION_START
            putExtra(RoomNavigation.EXTRA_ROOM_ID, roomId)
            putExtra(RoomNavigation.EXTRA_ROOM_CODE, roomCode)
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
            if (!isServiceBound) {
                bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            }
            binding.connectionStatusText.text = getString(R.string.local_audio_starting)
        } catch (error: RuntimeException) {
            onCommsError(
                error.localizedMessage ?: getString(R.string.audio_start_failed),
                fatal = true,
            )
        }
    }

    private fun setMicEnabled(enabled: Boolean) {
        val service = commsService
        if (service?.isAudioReady != true) {
            Toast.makeText(this, R.string.local_audio_not_ready, Toast.LENGTH_SHORT).show()
            return
        }
        if (enabled && peerStatuses.values.none { it == WebRtcManager.PeerStatus.CONNECTED }) {
            Toast.makeText(this, R.string.no_peer_connected, Toast.LENGTH_SHORT).show()
            return
        }
        service.setMicEnabled(enabled)
    }

    private fun updateMicButton(enabled: Boolean) {
        binding.micButton.isActivated = enabled
        binding.micButton.text = when {
            enabled -> getString(R.string.mic_live)
            micMode == MicMode.PTT -> getString(R.string.hold_to_talk)
            else -> getString(R.string.tap_to_unmute)
        }
        binding.micStatusText.text = if (enabled) {
            getString(R.string.microphone_on)
        } else {
            getString(R.string.microphone_muted)
        }
    }

    private fun showPermissionDeniedState() {
        binding.connectionStatusText.text = getString(R.string.audio_permission_required)
        binding.permissionButton.visibility = android.view.View.VISIBLE
        binding.micButton.isEnabled = false
        binding.pttModeButton.isEnabled = false
        binding.toggleModeButton.isEnabled = false
        binding.audioOutputButton.isEnabled = false
        binding.floatingPttCheckBox.isEnabled = false
        updateMicButton(false)
    }

    private fun showAudioUnavailableState() {
        isAudioReady = false
        binding.connectionStatusText.text = getString(R.string.local_audio_not_ready)
        binding.micButton.isEnabled = false
        binding.pttModeButton.isEnabled = false
        binding.toggleModeButton.isEnabled = false
        binding.audioOutputButton.isEnabled = false
        binding.floatingPttCheckBox.isEnabled = false
        updateMicButton(false)
    }

    private fun hasAudioPermission(): Boolean = ContextCompat.checkSelfPermission(
        this,
        Manifest.permission.RECORD_AUDIO,
    ) == PackageManager.PERMISSION_GRANTED

    private fun listenToMembers() {
        memberListener?.remove()
        memberListener = roomRepository.listenToMembers(
            roomId = roomId,
            onMembersChanged = { members ->
                roomMembers = members
                renderMembers()
                binding.memberCountText.text = resources.getQuantityString(
                    R.plurals.member_count,
                    members.size,
                    members.size,
                )
            },
            onError = { error ->
                Toast.makeText(this, error.localizedMessage, Toast.LENGTH_LONG).show()
            },
        )
    }

    private fun renderMembers() {
        memberAdapter.submitList(
            roomMembers.map { member ->
                val liveStatus = roomStatuses[member.uid]
                member.copy(
                    isConnected = liveStatus?.isConnected == true,
                    isSpeaking = liveStatus?.isSpeaking == true,
                    micMode = liveStatus?.micMode ?: member.micMode,
                )
            },
        )
    }

    private fun updateControlAvailability() {
        val hasConnectedPeer = peerStatuses.values.any {
            it == WebRtcManager.PeerStatus.CONNECTED
        }
        binding.micButton.isEnabled = isAudioReady && hasConnectedPeer
        binding.pttModeButton.isEnabled = isAudioReady
        binding.toggleModeButton.isEnabled = isAudioReady
        binding.audioOutputButton.isEnabled = isAudioReady
        binding.floatingPttCheckBox.isEnabled = isAudioReady && micMode == MicMode.PTT
    }

    private fun requestRoutesAndShowDialog() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            bluetoothPermissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
            return
        }
        commsService?.refreshAudioRoutes()
        showAudioRouteDialog()
    }

    private fun showAudioRouteDialog() {
        val routes = listOf(
            AudioRoute.EARPIECE,
            AudioRoute.SPEAKER,
            AudioRoute.BLUETOOTH,
        ).filter { it in availableAudioRoutes }
        if (routes.isEmpty()) {
            Toast.makeText(this, R.string.audio_route_failed, Toast.LENGTH_SHORT).show()
            return
        }
        val labels = routes.map { route ->
            getString(
                when (route) {
                    AudioRoute.EARPIECE -> R.string.audio_route_earpiece
                    AudioRoute.SPEAKER -> R.string.audio_route_speaker
                    AudioRoute.BLUETOOTH -> R.string.audio_route_bluetooth
                },
            )
        }.toTypedArray()
        val checkedIndex = routes.indexOf(selectedAudioRoute)
        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.audio_output_title)
            .setNegativeButton(R.string.cancel, null)
            .setSingleChoiceItems(labels, checkedIndex) { activeDialog, which ->
                commsService?.selectAudioRoute(routes[which])
                activeDialog.dismiss()
            }
            .create()
        dialog.show()
    }

    private fun requestOverlayPermission() {
        runCatching {
            overlayPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName"),
                ),
            )
        }.onFailure {
            setFloatingPttChecked(false)
            Toast.makeText(this, R.string.floating_ptt_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun setFloatingPttChecked(checked: Boolean) {
        updatingFloatingPttCheckBox = true
        binding.floatingPttCheckBox.isChecked = checked
        updatingFloatingPttCheckBox = false
    }

    private fun syncMicModeFromService(service: CommsForegroundService) {
        val serviceMode = if (service.currentMicMode == RoomMember.MIC_MODE_TOGGLE) {
            MicMode.TOGGLE
        } else {
            MicMode.PTT
        }
        micMode = serviceMode
        val checkedId = if (serviceMode == MicMode.TOGGLE) {
            R.id.toggleModeButton
        } else {
            R.id.pttModeButton
        }
        if (binding.micModeGroup.checkedRadioButtonId != checkedId) {
            updatingMicModeSelection = true
            binding.micModeGroup.check(checkedId)
            updatingMicModeSelection = false
        }
        updateMicButton(service.isMicEnabled)
        updateControlAvailability()
    }

    private fun renderConnectionStatus() {
        if (!isAudioReady) return
        val total = peerStatuses.size
        val connected = peerStatuses.values.count { it == WebRtcManager.PeerStatus.CONNECTED }
        val reconnecting = peerStatuses.values.any {
            it == WebRtcManager.PeerStatus.DISCONNECTED || it == WebRtcManager.PeerStatus.FAILED
        }
        binding.connectionStatusText.text = when {
            total == 0 -> getString(R.string.waiting_for_peer)
            reconnecting -> getString(R.string.peers_reconnecting, connected, total)
            connected == total -> resources.getQuantityString(
                R.plurals.peers_connected,
                total,
                total,
            )
            connected > 0 -> getString(R.string.peers_partially_connected, connected, total)
            peerStatuses.values.any { it == WebRtcManager.PeerStatus.CONNECTING } ->
                getString(R.string.connecting_peers, total)
            else -> getString(R.string.signaling_peers, total)
        }
    }

    private fun leaveRoom() {
        if (isServiceBound) {
            commsService?.stopComms()
            commsService?.setStateListener(null)
            unbindService(serviceConnection)
            isServiceBound = false
            commsService = null
        } else {
            startService(
                Intent(this, CommsForegroundService::class.java).apply {
                    action = CommsForegroundService.ACTION_LEAVE
                },
            )
        }
        finish()
    }
}
