package com.suzukiscan.core.session

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Filesystem-safe, human-readable session identifier for naming per-session export files. */
fun newSessionId(): String = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
