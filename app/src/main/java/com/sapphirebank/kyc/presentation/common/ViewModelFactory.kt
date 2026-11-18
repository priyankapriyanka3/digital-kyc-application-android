package com.sapphirebank.kyc.presentation.common

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.sapphirebank.kyc.AppContainer
import com.sapphirebank.kyc.presentation.application.ApplicationViewModel
import com.sapphirebank.kyc.presentation.review.ReviewViewModel
import com.sapphirebank.kyc.presentation.tracker.TrackerViewModel

class ViewModelFactory(private val c: AppContainer) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T = when {
        modelClass.isAssignableFrom(ApplicationViewModel::class.java) -> ApplicationViewModel(c) as T
        modelClass.isAssignableFrom(TrackerViewModel::class.java) -> TrackerViewModel(c) as T
        modelClass.isAssignableFrom(ReviewViewModel::class.java) -> ReviewViewModel(c) as T
        else -> throw IllegalArgumentException("Unknown ViewModel ${modelClass.name}")
    }
}
