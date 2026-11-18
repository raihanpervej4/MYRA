package com.myra.assistant.ui.settings

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ImageButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.myra.assistant.R
import com.myra.assistant.service.AccessibilityHelperService
import com.myra.assistant.util.PrimeContact
import com.myra.assistant.util.Prefs

class SettingsActivity : AppCompatActivity() {

    private lateinit var apiKeyInput: EditText
    private lateinit var userNameInput: EditText
    private lateinit var modelSpinner: Spinner
    private lateinit var voiceSpinner: Spinner
    private lateinit var personalityGroup: RadioGroup
    private lateinit var accessibilityStatus: TextView
    private lateinit var primeRecycler: RecyclerView
    private lateinit var primeAdapter: PrimeContactAdapter
    private val contacts = mutableListOf<PrimeContact>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        apiKeyInput = findViewById(R.id.apiKeyInput)
        userNameInput = findViewById(R.id.userNameInput)
        modelSpinner = findViewById(R.id.modelSpinner)
        voiceSpinner = findViewById(R.id.voiceSpinner)
        personalityGroup = findViewById(R.id.personalityGroup)
        accessibilityStatus = findViewById(R.id.accessibilityStatus)
        primeRecycler = findViewById(R.id.primeRecycler)

        modelSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, Prefs.MODELS.map { it.first })
        voiceSpinner.adapter = ArrayAdapter(this, R.layout.spinner_item, Prefs.VOICES.map { it.first })

        apiKeyInput.setText(Prefs.apiKey(this))
        userNameInput.setText(Prefs.rawUserName(this))
        modelSpinner.setSelection(Prefs.MODELS.indexOfFirst { it.second == Prefs.model(this) }.coerceAtLeast(0))
        voiceSpinner.setSelection(Prefs.VOICES.indexOfFirst { it.second == Prefs.voice(this) }.coerceAtLeast(0))
        personalityGroup.check(
            when (Prefs.personality(this)) {
                Prefs.MODE_PRO -> R.id.radioPro
                Prefs.MODE_ASSISTANT -> R.id.radioAssistant
                else -> R.id.radioGF
            }
        )

        contacts.addAll(Prefs.getPrimeContacts(this))
        primeAdapter = PrimeContactAdapter(contacts) { pos ->
            contacts.removeAt(pos)
            primeAdapter.notifyDataSetChanged()
        }
        primeRecycler.layoutManager = LinearLayoutManager(this)
        primeRecycler.adapter = primeAdapter

        findViewById<View>(R.id.addPrimeBtn).setOnClickListener { showAddDialog() }
        accessibilityStatus.setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        findViewById<View>(R.id.saveBtn).setOnClickListener { save() }
    }

    override fun onResume() {
        super.onResume()
        val on = AccessibilityHelperService.isEnabled(this)
        accessibilityStatus.text = if (on) "✅ Accessibility ON" else "❌ Accessibility OFF (tap karke ON karo)"
        accessibilityStatus.setTextColor(if (on) 0xFF00E676.toInt() else 0xFFFF1744.toInt())
    }

    private fun showAddDialog() {
        val v = LayoutInflater.from(this).inflate(R.layout.dialog_add_prime_contact, null)
        AlertDialog.Builder(this)
            .setTitle("Add prime contact")
            .setView(v)
            .setPositiveButton("Add") { _, _ ->
                val n = v.findViewById<EditText>(R.id.dialogNameInput).text.toString().trim()
                val num = v.findViewById<EditText>(R.id.dialogNumberInput).text.toString().trim()
                if (n.isNotEmpty() && num.isNotEmpty()) {
                    contacts.add(PrimeContact(n, num))
                    primeAdapter.notifyDataSetChanged()
                } else Toast.makeText(this, "Name aur number dono chahiye", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun save() {
        val mode = when (personalityGroup.checkedRadioButtonId) {
            R.id.radioPro -> Prefs.MODE_PRO
            R.id.radioAssistant -> Prefs.MODE_ASSISTANT
            else -> Prefs.MODE_GF
        }
        Prefs.saveBasics(
            this, apiKeyInput.text.toString(), userNameInput.text.toString(), mode,
            Prefs.MODELS[modelSpinner.selectedItemPosition].second,
            Prefs.VOICES[voiceSpinner.selectedItemPosition].second
        )
        Prefs.savePrimeContacts(this, contacts)
        Toast.makeText(this, "Saved ✅ Restart app to apply changes", Toast.LENGTH_LONG).show()
        finish()
    }
}

class PrimeContactAdapter(
    private val items: List<PrimeContact>,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<PrimeContactAdapter.VH>() {

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val name: TextView = v.findViewById(R.id.primeItemName)
        val number: TextView = v.findViewById(R.id.primeItemNumber)
        val delete: ImageButton = v.findViewById(R.id.primeItemDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_prime_contact, parent, false))

    override fun onBindViewHolder(holder: VH, position: Int) {
        holder.name.text = items[position].name
        holder.number.text = items[position].number
        holder.delete.setOnClickListener {
            val p = holder.bindingAdapterPosition
            if (p >= 0) onDelete(p)
        }
    }

    override fun getItemCount() = items.size
}
