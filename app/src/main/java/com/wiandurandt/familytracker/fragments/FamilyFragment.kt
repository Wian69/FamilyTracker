package com.wiandurandt.familytracker.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.*
import com.wiandurandt.familytracker.R
import com.wiandurandt.familytracker.adapters.FamilyAdapter

class FamilyFragment : Fragment() {

    private lateinit var rvFamily: RecyclerView
    private val membersList = ArrayList<FamilyAdapter.Member>()
    private lateinit var adapter: FamilyAdapter
    private val DB_URL = "https://familiy-tracker-default-rtdb.firebaseio.com/"
    
    private var currentFamilyId: String? = null
    private var usersListener: ChildEventListener? = null
    private var database: DatabaseReference? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_family, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        rvFamily = view.findViewById(R.id.rvFamily)
        rvFamily.layoutManager = LinearLayoutManager(context)
        adapter = FamilyAdapter(membersList) { uid ->
            val intent = android.content.Intent(requireContext(), com.wiandurandt.familytracker.MemberDetailActivity::class.java)
            intent.putExtra("UID", uid)
            startActivity(intent)
        }
        rvFamily.adapter = adapter
        
        fetchFamilyId()
    }
    
    private fun fetchFamilyId() {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val db = FirebaseDatabase.getInstance(DB_URL)
        
        db.getReference("users").child(uid).child("familyId").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val newFamilyId = snapshot.getValue(String::class.java)
                if (newFamilyId != null && newFamilyId != currentFamilyId) {
                    currentFamilyId = newFamilyId
                    listenForMembers(newFamilyId)
                }
            }
            override fun onCancelled(error: DatabaseError) {}
        })
    }
    
    private fun listenForMembers(familyId: String) {
        database = FirebaseDatabase.getInstance(DB_URL).getReference("users")
        
        // Clean up old listener if exists (not implemented here for simplicity as fragment re-creates usually)
        
        usersListener = object : ChildEventListener {
            override fun onChildAdded(snapshot: DataSnapshot, previousChildName: String?) {
                updateMember(snapshot, familyId)
            }
            override fun onChildChanged(snapshot: DataSnapshot, previousChildName: String?) {
                updateMember(snapshot, familyId)
            }
            override fun onChildRemoved(snapshot: DataSnapshot) {
                val uid = snapshot.key
                membersList.removeAll { it.uid == uid }
                adapter.notifyDataSetChanged()
            }
            override fun onChildMoved(snapshot: DataSnapshot, previousChildName: String?) {}
            override fun onCancelled(error: DatabaseError) {}
        }
        database!!.addChildEventListener(usersListener!!)
    }
    
    private fun updateMember(snapshot: DataSnapshot, myFamilyId: String) {
        val userFamilyId = snapshot.child("familyId").getValue(String::class.java)
        if (userFamilyId != myFamilyId) return 
        
        val uid = snapshot.key ?: return
        var email = snapshot.child("email").getValue(String::class.java)
        
        // Fallback for myself if DB is missing email
        if (email == null && uid == FirebaseAuth.getInstance().currentUser?.uid) {
            email = FirebaseAuth.getInstance().currentUser?.email
        }
        
        val displayName = email ?: "Unknown"
        val profileBase64 = snapshot.child("profileBase64").getValue(String::class.java)
        
        val currentPlace = snapshot.child("currentPlace").getValue(String::class.java)
        val lastUpdated = snapshot.child("lastUpdated").getValue(Long::class.java) ?: 0L
        val speed = snapshot.child("speed").getValue(Float::class.java) ?: 0f
        val battery = snapshot.child("batteryLevel").getValue(Int::class.java) ?: -1
        
        // Status Logic
        val now = System.currentTimeMillis()
        val isOnline = (now - lastUpdated) < (5 * 60 * 1000) // Online if update in last 5 mins
        
        var status = "Offline"
        if (isOnline) {
            status = if (!currentPlace.isNullOrEmpty()) {
                "At $currentPlace"
            } else {
                if (speed > 200/3.6) "Flying ✈️ (${(speed*3.6).toInt()} km/h)"
                else if (speed > 35/3.6) "Driving 🚗 (${(speed*3.6).toInt()} km/h)" 
                else if (speed > 10/3.6) "Cycling 🚴 (${(speed*3.6).toInt()} km/h)"
                else if (speed > 2) "Walking 🚶 (${(speed*3.6).toInt()} km/h)" 
                else "Stationary"
            }
        } else {
            // Format time ago (simple)
            val diffMin = (now - lastUpdated) / 60000
            status = "Last seen ${diffMin}m ago"
        }
        
        val member = FamilyAdapter.Member(uid, displayName, status, lastUpdated, profileBase64, isOnline, battery)
        
        // Update list
        val index = membersList.indexOfFirst { it.uid == uid }
        if (index != -1) {
            membersList[index] = member
            adapter.notifyItemChanged(index)
        } else {
            membersList.add(member)
            adapter.notifyItemInserted(membersList.size - 1)
        }
    }
}
