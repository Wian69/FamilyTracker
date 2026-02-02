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

class FamilyAdapter(private val members: List<Member>) : RecyclerView.Adapter<FamilyAdapter.ViewHolder>() {

    data class Member(
        val uid: String,
        val email: String,
        val status: String,
        val lastSeen: Long,
        val profileBase64: String?,
        val isOnline: Boolean
    )

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivAvatar: ImageView = view.findViewById(R.id.ivMemberAvatar)
        val tvName: TextView = view.findViewById(R.id.tvMemberName)
        val tvStatus: TextView = view.findViewById(R.id.tvMemberStatus)
        val ivDot: ImageView = view.findViewById(R.id.ivStatusDot)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_family_member, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val member = members[position]
        
        val currentUid = com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid
        holder.tvName.text = if (member.uid == currentUid) "You" else member.email.substringBefore("@").capitalize()
        holder.tvStatus.text = member.status
        
        // Online/Status Dot Color
        val color = if (member.isOnline) 
            android.graphics.Color.GREEN 
        else 
            android.graphics.Color.GRAY // or transparent
            
        holder.ivDot.setColorFilter(color)

        if (member.profileBase64 != null) {
            try {
                val imageBytes = Base64.decode(member.profileBase64, Base64.DEFAULT)
                val decodedImage = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                Glide.with(holder.itemView.context)
                    .load(decodedImage)
                    .circleCrop()
                    .into(holder.ivAvatar)
                holder.ivAvatar.clearColorFilter() // Remove the fallback tint
                holder.ivAvatar.setPadding(0,0,0,0) // Remove padding
            } catch (e: Exception) {
                holder.ivAvatar.setImageResource(R.mipmap.ic_launcher)
            }
        } else {
            holder.ivAvatar.setImageResource(R.mipmap.ic_launcher)
        }
    }

    override fun getItemCount() = members.size
}
