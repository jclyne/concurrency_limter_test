import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.parameters.types.enum
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.long
import com.netflix.concurrency.limits.limit.Gradient2Limit
import com.netflix.concurrency.limits.limit.GradientLimit
import com.netflix.concurrency.limits.limit.VegasLimit
import com.netflix.concurrency.limits.limiter.SimpleLimiter
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.random.Random

class ConcurrencyLimitsTest : CliktCommand() {
  enum class Algorithm {
    VEGAS,
    GRADIENT,
    GRADIENT2
  }

  private val algorithm by option( help = "Limit Algorithm to use")
    .enum<Algorithm>(ignoreCase = true)
    .default(Algorithm.VEGAS)

  private val initialLimit by option( help = "Initial Limit").int()
    .validate { require(it > 0) }
  private val minLimit by option( help = "Min Limit").int()
    .validate { require(it > 0) }
  private val maxConcurrency by option( help = "Max Concurrency").int()
    .validate { require(it > 0) }
  private val rttMin by option( help = "Min RTT in ms").long().default(100)
    .validate { require(it > 0) }
  private val rttMax by option( help = "Max RTT in ms").long().default(105)
    .validate { require(it > 0) }
  private val clients by option( help = "Number of clients").int().default(1000)
    .validate { require(it > 0) }
  private val qps by option( help = "Client QPS").int().default(10)
    .validate { require((1..1000).contains(it)) }

  override fun run() {
    val limit = when (algorithm) {
      Algorithm.VEGAS -> VegasLimit.newBuilder().apply {
        initialLimit?.let { initialLimit(it) }
        maxConcurrency?.let { maxConcurrency(it) }
      }.build()

      Algorithm.GRADIENT -> GradientLimit.newBuilder().apply {
        initialLimit?.let { initialLimit(it) }
        minLimit?.let { minLimit(it) }
        maxConcurrency?.let { maxConcurrency(it) }
      }.build()

      Algorithm.GRADIENT2 -> Gradient2Limit.newBuilder().apply {
        initialLimit?.let { initialLimit(it) }
        minLimit?.let { minLimit(it) }
        maxConcurrency?.let { maxConcurrency(it) }
      }.build()
    }

    val limiter = SimpleLimiter.Builder()
      .named(ACTION_NAME)
      .limit(limit)
      .build<String>()

    runBlocking {
      coroutineScope {
        Runtime.getRuntime().addShutdownHook(object : Thread() {
          override fun run() {
            try {
              println("Shutting down ...")
              cancel()
            } catch (e: InterruptedException) {
              currentThread().interrupt()
              e.printStackTrace()
            }
          }
        })

        (1..clients).forEach {
          launch {
            // Just to stagger initial requests
            delay(Random.nextLong(0, 1000))
            while (true) {
              launch {
                val rtt = Random.nextLong(rttMin, rttMax)
                val listener = limiter.acquire(ACTION_NAME).orElse(null)
                listener?.let {
                  delay(rtt)
                  listener.onSuccess()
                }
                println("Limit: ${limit.limit}, Inflight: ${limiter.inflight}, Drop: ${listener == null}")
              }
              delay(1000L / qps)
            }
          }
        }
      }
    }
  }

  companion object {
    const val ACTION_NAME = "test action"
  }

}

fun main(args: Array<String>) {
  ConcurrencyLimitsTest().main(args)
}