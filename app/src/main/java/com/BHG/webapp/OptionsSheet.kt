package com.BHG.webapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.transition.AutoTransition
import androidx.transition.TransitionManager
import com.BHG.webapp.databinding.SheetOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * Floating options bottom sheet with accordion sections. Tapping a section header
 * opens that section (its body slides in, chevron flips up) and closes any other
 * open one — so only a single section is expanded at a time. The expand/collapse is
 * animated by running an [AutoTransition] on the shared root before toggling
 * visibility, giving the smooth "title stays, body reveals, others close" motion.
 */
class OptionsSheet : BottomSheetDialogFragment() {

    private var _binding: SheetOptionsBinding? = null
    private val binding get() = _binding!!

    /** Index of the currently open section, or -1 when all are collapsed. */
    private var openIndex = -1

    override fun getTheme(): Int = R.style.ThemeOverlay_App_FloatingSheet

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = SheetOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.sheetClose.setOnClickListener { dismiss() }

        val sections = listOf(
            Section(binding.section1Body, binding.section1Chevron),
            Section(binding.section2Body, binding.section2Chevron),
            Section(binding.section3Body, binding.section3Chevron)
        )
        binding.section1Header.setOnClickListener { toggle(0, sections) }
        binding.section2Header.setOnClickListener { toggle(1, sections) }
        binding.section3Header.setOnClickListener { toggle(2, sections) }

        // Open the first section by default so the sheet reveals content on show.
        toggle(0, sections)
    }

    private fun toggle(index: Int, sections: List<Section>) {
        val transition = AutoTransition().apply { duration = 220 }
        TransitionManager.beginDelayedTransition(binding.animatedRoot, transition)

        val willOpen = openIndex != index
        sections.forEachIndexed { i, section ->
            val open = i == index && willOpen
            section.body.visibility = if (open) View.VISIBLE else View.GONE
            section.chevron.rotation = if (open) 180f else 0f
        }
        openIndex = if (willOpen) index else -1
    }

    private data class Section(val body: View, val chevron: View)

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    companion object {
        const val TAG = "OptionsSheet"
    }
}
