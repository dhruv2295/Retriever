package app.retriever.search

import android.app.Activity
import android.util.Log
import com.google.android.play.core.review.ReviewManagerFactory

/** Asks Google Play for its rating dialog; Play decides whether to show it, and a failure only goes to the log. */
fun requestPlayReview(activity: Activity) {
    val manager = ReviewManagerFactory.create(activity)
    manager.requestReviewFlow().addOnCompleteListener { request ->
        if (request.isSuccessful) {
            manager.launchReviewFlow(activity, request.result)
        } else {
            Log.w("Retriever", "Play review flow unavailable", request.exception)
        }
    }
}
