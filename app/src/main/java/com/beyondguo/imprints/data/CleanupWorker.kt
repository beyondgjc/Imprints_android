package com.beyondguo.imprints.data

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.beyondguo.imprints.security.ImprintSecurity

class CleanupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = runCatching {
        val database = ImprintDatabase.get(applicationContext)
        ImprintRepository(database, MediaFiles(applicationContext), ImprintSecurity(database)).cleanupExpired()
        Result.success()
    }.getOrElse { Result.retry() }
}
