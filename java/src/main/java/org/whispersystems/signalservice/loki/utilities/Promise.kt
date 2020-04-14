@file:JvmName("PromiseUtil")
package org.whispersystems.signalservice.loki.utilities

import nl.komponents.kovenant.*
import org.whispersystems.libsignal.logging.Log
import java.util.concurrent.TimeoutException
import kotlin.math.max

// Try to use all available threads minus one for the callback
private val recommendedThreadCount: Int
    get() = Runtime.getRuntime().availableProcessors() - 1

fun Kovenant.createContext(contextName: String, threadCount: Int = max(recommendedThreadCount, 1)): Context {
    return createContext {
        callbackContext.dispatcher = buildDispatcher {
            name = "${contextName}CallbackDispatcher"
            // Ref: http://kovenant.komponents.nl/api/core_usage/#execution-order
            // Having 1 concurrent task ensures we have in-order callback handling
            concurrentTasks = 1
        }
        workerContext.dispatcher = buildDispatcher {
            name = "${contextName}WorkerDispatcher"
            concurrentTasks = threadCount
        }
        multipleCompletion = { lhs, rhs ->
            Log.d("Loki", "Promise resolved more than once (first with $lhs, then with $rhs); ignoring $rhs.")
        }
    }
}

fun <V, E : Throwable> Promise<V, E>.get(defaultValue: V): V {
  return try {
    get()
  } catch (e: Exception) {
    defaultValue
  }
}

fun <V, E> Promise<V, E>.successBackground(callback: (value: V) -> Unit): Promise<V, E> {
  Thread {
    try {
      callback(get())
    } catch (e: Exception) {
      Log.d("Loki", "Failed to execute task in background: ${e.message}.")
    }
  }.start()
  return this
}

fun <V, E : Throwable> Promise<V, E>.recover(callback: (exception: E) -> V): Promise<V, E> {
  val deferred = deferred<V, E>()
  success {
    deferred.resolve(it)
  }.fail {
    try {
      val recoveredValue = callback(it)
      deferred.resolve(recoveredValue)
    } catch (e: Throwable) {
      deferred.reject(it)
    }
  }
  return deferred.promise
}

fun <V> Promise<V, Exception>.timeout(millis: Long): Promise<V, Exception> {
  if (this.isDone()) { return this; }
  val deferred = deferred<V, Exception>()
  Thread {
    Thread.sleep(millis)
    if (!deferred.promise.isDone()) {
      deferred.reject(TimeoutException("Promise timed out."))
    }
  }.start()
  this.success {
    if (!deferred.promise.isDone()) { deferred.resolve(it) }
  }.fail {
    if (!deferred.promise.isDone()) { deferred.reject(it) }
  }
  return deferred.promise
}
