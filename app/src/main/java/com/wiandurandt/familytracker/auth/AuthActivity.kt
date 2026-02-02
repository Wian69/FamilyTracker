package com.wiandurandt.familytracker.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.view.View
import com.google.firebase.auth.FirebaseAuth
import com.wiandurandt.familytracker.MainActivity
import com.wiandurandt.familytracker.databinding.ActivityAuthBinding

class AuthActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAuthBinding
    private lateinit var auth: FirebaseAuth

    private val TAG = "AuthActivity"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityAuthBinding.inflate(layoutInflater)
        setContentView(binding.root)

        auth = FirebaseAuth.getInstance()

        if (auth.currentUser != null) {
            startMainActivity()
            return
        }

        binding.btnLogin.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()

            if (email.isNotEmpty() && password.isNotEmpty()) {
                showLoading(true)
                auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        showLoading(false)
                        if (task.isSuccessful) {
                            android.util.Log.d(TAG, "signIn:success")
                            startMainActivity()
                        } else {
                            android.util.Log.w(TAG, "signIn:failure", task.exception)
                            Toast.makeText(this, "Login Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            }
        }

        binding.btnRegister.setOnClickListener {
            val email = binding.etEmail.text.toString()
            val password = binding.etPassword.text.toString()
            val familyId = binding.etFamilyCode.text.toString().trim()

            if (email.isNotEmpty() && password.isNotEmpty() && familyId.isNotEmpty()) {
                showLoading(true)
                android.util.Log.d(TAG, "Attempting registration for $email")
                
                auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            android.util.Log.d(TAG, "createUser:success")
                            saveUserToDatabase(task.result.user?.uid, familyId)
                        } else {
                            showLoading(false)
                            android.util.Log.w(TAG, "createUser:failure", task.exception)
                            Toast.makeText(this, "Registration Failed: ${task.exception?.message}", Toast.LENGTH_LONG).show()
                        }
                    }
            } else {
                Toast.makeText(this, "Please enter Email, Password AND Family Code", Toast.LENGTH_SHORT).show()
            }
        }
    }
    
    private fun saveUserToDatabase(uid: String?, familyId: String) {
        if (uid == null) {
            showLoading(false)
            return
        }
        
        android.util.Log.d(TAG, "Saving user to database...")
        val ref = com.google.firebase.database.FirebaseDatabase.getInstance().getReference("users").child(uid)
        val updates = mapOf(
            "familyId" to familyId,
            "email" to binding.etEmail.text.toString()
        )
        
        // Add failure listener here too
        ref.updateChildren(updates)
            .addOnSuccessListener {
                android.util.Log.d(TAG, "Database update success")
                showLoading(false)
                startMainActivity()
            }
            .addOnFailureListener { e ->
                showLoading(false)
                android.util.Log.w(TAG, "Database update failure", e)
                Toast.makeText(this, "Failed to save profile: ${e.message}", Toast.LENGTH_LONG).show()
            }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.visibility = if (isLoading) View.VISIBLE else View.GONE
        binding.btnLogin.isEnabled = !isLoading
        binding.btnRegister.isEnabled = !isLoading
    }

    private fun startMainActivity() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
