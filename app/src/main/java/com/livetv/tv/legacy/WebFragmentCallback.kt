package com.livetv.tv.legacy

interface WebFragmentCallback {
    fun onPlaybackStarted()
    fun onPlaybackStopped()
    fun onPlaybackError(error: String)
}