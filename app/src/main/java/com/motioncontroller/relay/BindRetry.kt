package com.motioncontroller.relay

import java.net.BindException

/**
 * 绑定失败重试策略（问题4：引擎快速重启时端口偶发未释放 → `BindException` → APP「无法连接」）。
 *
 * - 只重试「端口占用」类失败：[BindException]，或消息含 `address already in use` / `EADDRINUSE`
 *   （沿 cause 链查找，容忍被包装的异常）；其它失败（非法参数、线程异常等）立即上抛，不浪费等待。
 * - 默认最多重试 [DEFAULT_MAX_RETRIES] 次（总尝试 1 + 3 = 4 次），退避 [DEFAULT_BACKOFF_MS]
 *   （250ms → 500ms，之后保持 500ms，即"250ms、500ms 退避"的指数封顶形式）。
 * - [sleeper] 可注入：单测不真睡，只记录退避序列；[run] 的 attempt 参数是尝试序号（0 起），
 *   测试可据此注入"前 N 次绑定失败"。
 */
internal class BindRetry(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,
    val backoffMs: List<Long> = DEFAULT_BACKOFF_MS,
    private val sleeper: (Long) -> Unit = { ms -> if (ms > 0) Thread.sleep(ms) },
) {

    /**
     * 执行一次绑定 [attempt]；「端口占用」类失败按退避重试，其它异常原样上抛。
     *
     * @param log 重试过程日志（一行中文，由调用方转发到协议日志）。
     * @param attempt 尝试一次绑定；参数为尝试序号（0 起）。成功直接返回，失败抛异常。
     * @return [attempt] 的返回值。
     */
    fun <T> run(log: (String) -> Unit, attempt: (Int) -> T): T {
        require(maxRetries >= 0) { "maxRetries 必须 >= 0" }
        var last: Throwable? = null
        for (index in 0..maxRetries) {
            try {
                return attempt(index)
            } catch (t: Throwable) {
                last = t
                if (index >= maxRetries || !isRetryableBindFailure(t)) throw t
                val waitMs = backoffMs.getOrElse(index) { backoffMs.lastOrNull() ?: 0L }
                log("绑定失败（第 ${index + 1} 次尝试）${describeThrowable(t)}，${waitMs}ms 后重试")
                sleeper(waitMs)
            }
        }
        // 理论上到不了这里（循环内必然 return 或 throw）
        throw last ?: IllegalStateException("绑定重试循环异常退出")
    }

    companion object {
        /** 最大重试次数（总尝试次数 = 1 + [DEFAULT_MAX_RETRIES]）。 */
        const val DEFAULT_MAX_RETRIES = 3

        /** 退避序列：250ms → 500ms（之后保持 500ms）。 */
        val DEFAULT_BACKOFF_MS: List<Long> = listOf(250L, 500L)

        /** 是否「端口占用」类绑定失败（可重试）；沿 cause 链查找。 */
        fun isRetryableBindFailure(t: Throwable): Boolean {
            var current: Throwable? = t
            val seen = HashSet<Throwable>()
            while (current != null && seen.add(current)) {
                if (current is BindException) return true
                val message = current.message ?: ""
                if (message.contains("address already in use", ignoreCase = true)) return true
                if (message.contains("EADDRINUSE", ignoreCase = true)) return true
                current = current.cause
            }
            return false
        }
    }
}
