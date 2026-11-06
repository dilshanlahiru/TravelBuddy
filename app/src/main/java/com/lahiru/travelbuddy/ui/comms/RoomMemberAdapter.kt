package com.lahiru.travelbuddy.ui.comms

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import com.lahiru.travelbuddy.R
import com.lahiru.travelbuddy.data.model.RoomMember
import com.lahiru.travelbuddy.databinding.ItemRoomMemberBinding

class RoomMemberAdapter(
    context: Context,
) : BaseAdapter() {
    private val inflater = LayoutInflater.from(context)
    private var members: List<RoomMember> = emptyList()

    fun submitList(newMembers: List<RoomMember>) {
        members = newMembers
        notifyDataSetChanged()
    }

    override fun getCount(): Int = members.size

    override fun getItem(position: Int): RoomMember = members[position]

    override fun getItemId(position: Int): Long = getItem(position).uid.hashCode().toLong()

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val binding = if (convertView == null) {
            ItemRoomMemberBinding.inflate(inflater, parent, false).also {
                it.root.tag = it
            }
        } else {
            convertView.tag as ItemRoomMemberBinding
        }
        val member = getItem(position)
        binding.memberNameText.text = member.displayName
        binding.memberStatusText.text = when {
            member.isSpeaking && member.isNavigating ->
                binding.root.context.getString(R.string.member_status_speaking_navigating)
            member.isSpeaking -> binding.root.context.getString(R.string.member_status_speaking)
            member.isVoiceJoined && member.isNavigating ->
                binding.root.context.getString(R.string.member_status_voice_navigating)
            member.isVoiceJoined -> binding.root.context.getString(R.string.member_status_voice)
            member.isNavigating ->
                binding.root.context.getString(R.string.member_status_navigating)
            else -> binding.root.context.getString(R.string.member_status_in_room)
        }
        binding.speakingIndicator.visibility = if (member.isSpeaking) View.VISIBLE else View.INVISIBLE
        return binding.root
    }
}
