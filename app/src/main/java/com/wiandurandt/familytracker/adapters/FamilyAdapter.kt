package com.wiandurandt.familytracker.adapters

import android.graphics.BitmapFactory
import android.util.Base64
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.wiandurandt.familytracker.R

class FamilyAdapter(
    private val members: List<Member>,
    private val onMemberClick: (String) -> Unit
) : RecyclerView.Adapter<FamilyAdapter.ViewHolder>() {

    data class Member(
        val uid: String,
        val email: String,
        val status: String,
        val lastSeen: Long,
        val profileBase64: String?,
        val isOnline: Boolean,
        val battery: Int // Added battery
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivMemberAvatar)
        val tvName: TextView = view.findViewById(R.id.tvMemberName)
        val tvStatus: TextView = view.findViewById(R.id.tvMemberStatus)
        val tvBattery: TextView = view.findViewById(R.id.tvMemberBattery)
        val ivDot: ImageView = view.findViewById(R.id.ivStatusDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_family_member, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = members[position]
        
        holder.itemView.setOnClickListener {
            onMemberClick(member.uid)
        }
        
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        holder.tvName.text = if (member.uid == currentUid) "You" else member.email.substringBefore("@").replaceFirstChar { it.uppercase() }
        holder.tvStatus.text = member.status
        holder.tvBattery.text = if (member.battery >= 0) "${member.battery}%" else ""
        
        // Online/Status Dot Color
        val dotColor = if (member.isOnline) 
            android.graphics.Color.GREEN 
        else 
            android.graphics.Color.GRAY
            
        holder.ivDot.setColorFilter(dotColor)

        if (!member.profileBase64.isNullOrEmpty()) {
            try {
                val imageBytes = Base64.decode(member.profileBase64, Base64.DEFAULT)
                val decodedImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                Glide.with(holder.itemView.context)
                    .load(decodedImage)
                    .circleCrop()
                    .placeholder(R.drawable.ic_avatar_placeholder)
                    .error(R.drawable.ic_avatar_placeholder)
                    .into(holder.ivAvatar)
                holder.ivAvatar.clearColorFilter()
            } catch (e: Exception) {
                holder.ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
            }
        } else {
            holder.ivAvatar.setImageResource(R.drawable.ic_avatar_placeholder)
            holder.ivAvatar.setColorFilter(android.graphics.Color.LTGRAY)
        }
    }

    override fun getItemCount() = members.size
}
