package com.BHG.webapp

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import com.BHG.webapp.databinding.FragmentLandingBinding

/**
 * Home landing tab: a welcome hero with a primary "Build App" call to action and
 * quick-action cards that jump to the other tabs. Navigation is delegated to
 * [MainActivity.goToTab] so the bottom nav stays the single source of truth for
 * which tab is selected.
 */
class LandingFragment : Fragment() {

    private var _binding: FragmentLandingBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLandingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val main = activity as? MainActivity
        binding.topBarMenu.setOnClickListener { main?.openDrawer() }
        binding.landingBuildCta.setOnClickListener { main?.goToTab(R.id.nav_home) }
        binding.landingViewApps.setOnClickListener { main?.goToTab(R.id.nav_history) }
        binding.landingViewAccount.setOnClickListener { main?.goToTab(R.id.nav_profile) }
        binding.landingOptions.setOnClickListener { main?.openOptionsSheet() }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
