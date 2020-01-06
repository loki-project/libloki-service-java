package org.whispersystems.signalservice.loki.utilities

import nl.komponents.kovenant.Context
import nl.komponents.kovenant.Kovenant
import nl.komponents.kovenant.buildDispatcher
import org.whispersystems.libsignal.logging.Log

fun Kovenant.createContext(contextName: String, threads: Int): Context {
  return createContext {
    callbackContext.dispatcher = buildDispatcher {
      name = "${contextName}_callback_dispatcher"
      // Ref: http://kovenant.komponents.nl/api/core_usage/#execution-order
      // Having 1 concurrent task ensures we have in-order callback handling
      concurrentTasks = 1
    }
    workerContext.dispatcher = buildDispatcher {
      name = "${contextName}_worker_dispatcher"
      concurrentTasks = threads
    }
    multipleCompletion = { lhs, rhs ->
      Log.d("Loki", "Promise resolved more than once (first with $lhs, then with $rhs); ignoring $rhs.")
    }
  }
}